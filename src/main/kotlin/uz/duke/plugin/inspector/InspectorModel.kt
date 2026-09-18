package uz.duke.plugin.inspector

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import uz.duke.plugin.engine.BlockSpec
import uz.duke.plugin.engine.DukeModules
import uz.duke.plugin.engine.DukeSchemas
import uz.duke.plugin.engine.FieldKind
import uz.duke.plugin.engine.FieldSpec
import uz.duke.plugin.ini.AssetKind
import uz.duke.plugin.ini.DukeAssets
import uz.duke.plugin.ini.DukeIniBlock
import uz.duke.plugin.ini.DukeIniField
import uz.duke.plugin.ini.DukeIniModule
import uz.duke.plugin.ini.DukeIniFile
import uz.duke.plugin.ini.DukeIniProject
import uz.duke.plugin.ini.DukeIniSection
import uz.duke.plugin.ini.DukeIniSubsection

/** How a value is edited. */
sealed interface ValueEditor {
    /** A text field; a number kind is checked before it is written. */
    data class Text(val kind: FieldKind?) : ValueEditor

    /** `Yes` or `No`. */
    data object Check : ValueEditor

    /** A list to pick from; an editable one takes anything typed as well. */
    data class Choice(val options: List<String>, val editable: Boolean) : ValueEditor
}

class FieldRow(val key: String, val value: String, val field: SmartPsiElementPointer<DukeIniField>, val editor: ValueEditor, val hint: String)

class SectionView(
    val title: String,
    val section: SmartPsiElementPointer<DukeIniSection>,
    val range: TextRange,
    val fields: List<FieldRow>,
    /** Fields the code reads that the section does not write yet, and lists, which take another line. */
    val addable: List<FieldSpec>,
    /** Its modules and sections, in the order written. */
    val parts: List<SectionView>,
    /** Whether it is a unit's block, and so may carry modules. */
    val takesModules: Boolean,
    val sectionType: String,
)

/** A block the unit is missing: its `DungeonMonster`, its death sound. [secondNames] is set when the header needs another word. */
class Suggestion(val label: String, val type: String, val name: String, val secondNames: List<String>?, val fields: List<Pair<String, String>>)

/** A block of the unit's that lives in another file. */
class Elsewhere(val label: String, val file: VirtualFile, val offset: Int)

class ModuleChoice(val name: String, val key: String, val isPrefix: Boolean, val fields: List<Pair<String, String>>)

class InspectorModel(
    val file: SmartPsiElementPointer<DukeIniFile>,
    val title: String,
    val sections: List<SectionView>,
    val suggestions: List<Suggestion>,
    val elsewhere: List<Elsewhere>,
    /** Group -> the modules in it; a module in several groups is in each. */
    val modules: Map<String, List<ModuleChoice>>,
    val blockTypes: List<BlockSpec>,
    val index: DukeIniProject,
)

/** Reads a file, the game's code and every other INI file into what the Inspector shows. Call under a read action. */
object InspectorModels {
    fun build(file: DukeIniFile): InspectorModel {
        val project = file.project
        val pointers = SmartPointerManager.getInstance(project)
        val schema = DukeSchemas.of(file)
        val engine = DukeModules.of(file)
        val index = DukeIniProject.of(project)
        val root = DukeAssets.rootOf(file)
        val assets by lazy { root?.let(DukeAssets::filesUnder).orEmpty().sorted() }
        val own = index.blocks.filter { it.file == file.virtualFile }

        fun editorOf(sectionType: String, key: String, value: String, spec: FieldSpec?, block: DukeIniProject.Block?): Pair<ValueEditor, String> {
            if (spec?.kind == FieldKind.BOOL) return ValueEditor.Check to "Yes / No"
            if (spec?.kind == FieldKind.ENUM && spec.choices.isNotEmpty()) return ValueEditor.Choice(spec.choices, false) to "one of"
            val kinds = assetKinds(index, sectionType, key, value)
            if (kinds.isNotEmpty() && root != null) {
                return ValueEditor.Choice(assets.filter { AssetKind.of(it) in kinds }, true) to kinds.joinToString(" or ")
            }
            index.referenceOf(sectionType, key)?.let { type -> return ValueEditor.Choice(index.namesOf(type).sorted(), true) to "a $type" }
            if (index.isClip(sectionType, key)) return ValueEditor.Choice(block?.let(index::clipsFor) ?: index.clips.sorted(), true) to "a clip"
            return ValueEditor.Text(spec?.kind) to (spec?.kind?.name?.lowercase() ?: "")
        }

        fun rows(section: DukeIniSection, specs: List<FieldSpec>?, block: DukeIniProject.Block?) = section.fields.map { field ->
            val spec = specs?.firstOrNull { it.name.equals(field.keyText, ignoreCase = true) }
            val (editor, hint) = editorOf(section.sectionType, field.keyText, field.value, spec, block)
            FieldRow(field.keyText, field.value, pointers.createSmartPsiElementPointer(field), editor, hint + if (spec?.list == true) ", a list" else "")
        }

        fun addable(section: DukeIniSection, specs: List<FieldSpec>?) = specs.orEmpty().filter { spec ->
            val written = if (spec.section == null) section.fields.map { it.keyText }
            else section.parts.filterIsInstance<DukeIniSubsection>().map { it.key }
            spec.list || written.none { it.equals(spec.name, ignoreCase = true) }
        }

        /** A module is read by its class; a section by what the field that opens it reads. */
        fun partsOf(section: DukeIniSection, specs: List<FieldSpec>?, indexed: DukeIniProject.Block?): List<SectionView> = section.parts.map { part ->
            val partSpecs = when (part) {
                is DukeIniModule -> part.moduleName?.text?.let { engine?.find(it) }?.specs
                is DukeIniSubsection -> specs?.firstOrNull { it.name.equals(part.key, ignoreCase = true) }?.section?.fields
                else -> null
            }
            SectionView(
                part.presentableText, pointers.createSmartPsiElementPointer(part), part.textRange,
                rows(part, partSpecs, indexed), addable(part, partSpecs), partsOf(part, partSpecs, indexed), false, part.sectionType,
            )
        }

        val sections = file.blocks.map { block ->
            val indexed = own.firstOrNull { it.offset == block.textRange.startOffset }
            val specs = schema?.block(block.blockType)?.fields
            SectionView(
                block.presentableText, pointers.createSmartPsiElementPointer(block), block.textRange,
                rows(block, specs, indexed), addable(block, specs), partsOf(block, specs, indexed),
                block.blockType in index.unitTypes || schema?.block(block.blockType)?.template == true, block.sectionType,
            )
        }

        val units = file.blocks.filter { it.blockType in index.unitTypes }.mapNotNull(DukeIniBlock::getName)
        val modules = engine?.modules.orEmpty()
            .flatMap { module -> module.groups.ifEmpty { listOf("Other") }.map { it to module } }
            .groupBy({ it.first }, { it.second }).toSortedMap()
            .mapValues { (_, of) ->
                of.sortedBy { it.name }.map { module ->
                    val fields = if (module.isPrefix) emptyList() else index.defaults("module " + module.name.lowercase())
                    ModuleChoice(module.name, index.moduleKey(module.name) ?: module.key, module.isPrefix, fields)
                }
            }
        return InspectorModel(
            pointers.createSmartPsiElementPointer(file), file.name, sections,
            units.flatMap { suggestionsFor(it, index) },
            units.flatMap { unit ->
                index.about(unit).filter { it.file != file.virtualFile }.map { block ->
                    val where = index.rootOf(block.file)?.let { VfsUtilCore.getRelativePath(block.file, it) } ?: block.file.name
                    Elsewhere("${block.label} — $where", block.file, block.offset)
                }
            },
            modules, schema?.blocks.orEmpty(), index,
        )
    }

    /**
     * What the unit [name] is missing, judged by the units like it — those of its own type, and
     * when it has companions, those sharing one: a companion they have alongside (a hero's
     * portrait), a pointer type they have (a skill), and a block for each moment (a sound for when
     * it dies). A name no unit has yet is judged by every unit.
     */
    fun suggestionsFor(name: String, index: DukeIniProject): List<Suggestion> {
        fun companionsOf(unit: String?) = index.blocks.filter { it.name == unit && it.type in index.companions }.map { it.type }.toSet()
        val has = companionsOf(name)
        val alike = (index.unit(name)?.let { index.unitsOf(it.type) } ?: index.blocks.filter { index.isUnit(it) && it.name != null })
            .filter { unit -> has.isEmpty() || companionsOf(unit.name).any { it in has } }
        val together = alike.flatMap { companionsOf(it.name) }.toSet()
        val companions = index.companions.filter { it !in has && it in together }
            .map { Suggestion(it, it, name, null, index.defaults(it.lowercase())) }
        val pointers = index.pointers
            .filter { type -> alike.any { unit -> index.blocks.any { it.type == type && it.names.firstOrNull() == unit.name } } }
            .map { Suggestion("$it …", it, name, index.secondNames(it), index.defaults(it.lowercase())) }
        val moments = index.moments.flatMap { (type, moments) ->
            moments.filter { moment -> "$moment.$name" !in index.namesOf(type) }
                .map { moment -> Suggestion("$type $moment", type, "$moment.$name", null, index.defaults(type.lowercase())) }
        }
        return companions + pointers + moments
    }

    /** The asset kinds a field takes: what its key promises, else what its values are; none when its values are not paths. */
    private fun assetKinds(index: DukeIniProject, sectionType: String, key: String, value: String): Set<AssetKind> {
        val values = index.valuesOf(sectionType, key)
        if (AssetKind.of(value) == null && values.isNotEmpty() && values.none { AssetKind.of(it) != null }) return emptySet()
        return AssetKind.named(key)?.let(::setOf) ?: (values + value).mapNotNull(AssetKind::of).toSet()
    }
}
