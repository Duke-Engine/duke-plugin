package uz.duke.plugin.inspector

import com.intellij.openapi.util.TextRange
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import uz.duke.plugin.engine.BlockSpec
import uz.duke.plugin.engine.DukeSchemas
import uz.duke.plugin.engine.FieldKind
import uz.duke.plugin.engine.FieldSpec
import uz.duke.plugin.ini.AssetKind
import uz.duke.plugin.ini.DukeAssets
import uz.duke.plugin.ini.DukeIniField
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
    /** Its sections, in the order written. */
    val parts: List<SectionView>,
    val sectionType: String,
)

class InspectorModel(
    val file: SmartPsiElementPointer<DukeIniFile>,
    val title: String,
    val sections: List<SectionView>,
    val blockTypes: List<BlockSpec>,
    val index: DukeIniProject,
)

/** Reads a file, the game's code and every other INI file into what the Inspector shows. Call under a read action. */
object InspectorModels {
    fun build(file: DukeIniFile): InspectorModel {
        val project = file.project
        val pointers = SmartPointerManager.getInstance(project)
        val schema = DukeSchemas.of(file)
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

        /** A section is read by what the field that opens it reads. */
        fun partsOf(section: DukeIniSection, specs: List<FieldSpec>?, indexed: DukeIniProject.Block?): List<SectionView> =
            section.parts.filterIsInstance<DukeIniSubsection>().map { part ->
                val partSpecs = specs?.firstOrNull { it.name.equals(part.key, ignoreCase = true) }?.section?.fields
                SectionView(
                    part.presentableText, pointers.createSmartPsiElementPointer(part), part.textRange,
                    rows(part, partSpecs, indexed), addable(part, partSpecs), partsOf(part, partSpecs, indexed), part.sectionType,
                )
            }

        val sections = file.blocks.map { block ->
            val indexed = own.firstOrNull { it.offset == block.textRange.startOffset }
            val specs = schema?.block(block.blockType)?.fields
            SectionView(
                block.presentableText, pointers.createSmartPsiElementPointer(block), block.textRange,
                rows(block, specs, indexed), addable(block, specs), partsOf(block, specs, indexed), block.sectionType,
            )
        }
        return InspectorModel(pointers.createSmartPsiElementPointer(file), file.name, sections, schema?.blocks.orEmpty(), index)
    }

    /** The asset kinds a field takes: what its key promises, else what its values are; none when its values are not paths. */
    private fun assetKinds(index: DukeIniProject, sectionType: String, key: String, value: String): Set<AssetKind> {
        val values = index.valuesOf(sectionType, key)
        if (AssetKind.of(value) == null && values.isNotEmpty() && values.none { AssetKind.of(it) != null }) return emptySet()
        return AssetKind.named(key)?.let(::setOf) ?: (values + value).mapNotNull(AssetKind::of).toSet()
    }
}
