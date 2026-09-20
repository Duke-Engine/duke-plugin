package uz.duke.plugin.inspector

import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiPrefixExpression
import com.intellij.psi.PsiRecordComponent
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import uz.duke.plugin.assets.AssetKind
import uz.duke.plugin.assets.DukeAssets
import uz.duke.plugin.duke.DukeBlock
import uz.duke.plugin.duke.DukeField
import uz.duke.plugin.duke.DukeFile
import uz.duke.plugin.duke.DukeLinks
import uz.duke.plugin.duke.DukeRecords

/** How a value is edited, as its component's type and marks say. */
sealed interface ValueEditor {
    /** A text box; a [numeric] one is checked as a number before it is written. */
    data class Text(val numeric: Boolean) : ValueEditor

    /** `Yes` or `No`. */
    data object Check : ValueEditor

    /** One of an enum's constants. */
    data class Choice(val options: List<String>) : ValueEditor

    /** The `Name` of a block of [record]: one of [names]. */
    data class Link(val record: String, val names: List<String>) : ValueEditor

    /** A clip in the files the block is drawn from; [inherited] when not written, taken from what the block links. */
    data class Clip(val clips: List<ClipOption>, val inherited: String?, val from: String?) : ValueEditor

    /** A file under the resource root, of the kinds the key takes. */
    data class Path(val files: List<String>) : ValueEditor

    /** A colour, packed `0xRRGGBB`. */
    data object Colour : ValueEditor

    /** `[a, b]`: each chosen from [options] when there are some, else typed. */
    data class Values(val options: List<String>?) : ValueEditor

    /** A small record of numbers, written `[a, b]`: a box for each of [labels]. */
    data class Tuple(val labels: List<String>) : ValueEditor

    /** A sealed or open type's word — `Geometry = Cylinder` — its own fields in the rows under it. */
    data class Variant(val options: List<String>) : ValueEditor

    /** A map written as a block of its own, `BossGuards … End`: its keys chosen from [keys] when there are some. */
    data class Entries(val keys: List<String>?, val numeric: Boolean) : ValueEditor
}

data class ClipOption(val name: String, val file: String)

/** What the help under the form says of a field: its Javadoc, its type and its default. */
class Help(val title: String, val key: String, val type: String, val default: String, val doc: String)

/** Where a field's line is written: the block it goes in, its key, and the record's order, for where a new line goes. */
class Place(val owner: SmartPsiElementPointer<DukeBlock>, val key: String, val order: List<String>)

sealed interface Row {
    val id: String
    val depth: Int

    /** What the filter looks for: its label, key and help, in lower case. */
    val match: String
}

/** One field: its value as written, or its default when it is not. */
class FieldRow(
    override val id: String,
    val label: String,
    override val depth: Int,
    /** As written, `[a, b]` for a list; null when the line is not written. */
    val written: String?,
    /** A list's items, or a map's entries as `key = value`, as written. */
    val items: List<String>,
    val default: String,
    val editor: ValueEditor,
    val place: Place,
    val type: PsiType,
    val problem: String?,
    /** The line it is written on, from 0; null when it is not written. */
    val line: Int?,
    val help: Help,
) : Row {
    override val match = "$label ${help.key} ${help.doc}".lowercase()
    val isWritten get() = written != null
    val shown get() = written ?: default
}

/** A record written inside another, `Held = Held … End`: its fields are the rows under it. [word] is null when not written. */
class RecordRow(
    override val id: String,
    val label: String,
    override val depth: Int,
    val word: String?,
    val words: List<String>,
    val place: Place,
    val line: Int?,
    val help: Help,
) : Row {
    override val match = "$label ${help.key} ${help.doc}".lowercase()
}

/** One block of a list of blocks — a module, a skill, a layer — shown as a card whose fields follow it. */
class CardRow(
    override val id: String,
    override val depth: Int,
    val word: String,
    val groups: List<String>,
    val block: SmartPsiElementPointer<DukeBlock>,
    val index: Int,
    val count: Int,
    val line: Int?,
    val help: Help,
) : Row {
    override val match = word.lowercase()
}

class NoteRow(override val id: String, override val depth: Int, val text: String) : Row {
    override val match = ""
}

/** What a list of blocks may take one more of, each with the groups its class says it is in. */
class AddRow(override val id: String, override val depth: Int, val label: String, val place: Place, val choices: List<BlockChoice>) : Row {
    override val match = ""
}

data class BlockChoice(val word: String, val groups: List<String>)

class GroupView(val title: String, val rows: List<Row>)

class InspectorModel(
    val file: SmartPsiElementPointer<DukeFile>,
    val block: SmartPsiElementPointer<DukeBlock>?,
    /** Every block at the top of the file, as the switcher offers them. */
    val blocks: List<String>,
    val index: Int,
    val title: String,
    val word: String,
    val path: String,
    val groups: List<GroupView>,
    val problems: List<FieldRow>,
    /** Said instead of a form: the file is empty, or its block is no record the project knows. */
    val note: String?,
)

/**
 * A `.duke` block as a form: every component of its record, grouped as the record's `@Group` marks say, each
 * with the editor its type takes, its default out of the record's `DEFAULTS` and its help out of its Javadoc.
 * Everything is read from the game's own records, so nothing here names a game. Call under a read action.
 */
object InspectorModels {
    private const val GROUP = "uz.duke.core.data.Group"
    private const val MODULE_GROUP = "uz.duke.core.module.ModuleGroup"

    fun build(file: DukeFile, wanted: Int, caret: Int?): InspectorModel {
        val pointers = SmartPointerManager.getInstance(file.project)
        val blocks = file.blocks
        val filePointer = pointers.createSmartPsiElementPointer(file)
        val path = pathOf(file)
        if (blocks.isEmpty()) {
            return InspectorModel(filePointer, null, emptyList(), 0, file.name, "", path, emptyList(), emptyList(), "This file holds no block yet.")
        }
        val index = caret?.let { at -> blocks.indexOfFirst { it.textRange.containsOffset(at) } }?.takeIf { it >= 0 }
            ?: wanted.coerceIn(0, blocks.lastIndex)
        val block = blocks[index]
        val title = block.field("DisplayName")?.valueText ?: block.field("Name")?.valueText ?: block.wordText
        val record = DukeRecords.recordOf(block)
            ?: return InspectorModel(filePointer, pointers.createSmartPsiElementPointer(block), blocks.map { it.presentableText }, index,
                title, block.wordText, path, emptyList(), emptyList(), "No record is called '${block.wordText}'.")
        val builder = Builder(file)
        val groups = builder.groups(block, record)
        val problems = groups.flatMap { it.rows }.filterIsInstance<FieldRow>().filter { it.problem != null }
        return InspectorModel(filePointer, pointers.createSmartPsiElementPointer(block), blocks.map { it.presentableText }, index,
            title, block.wordText, path, groups, problems, null)
    }

    private fun pathOf(file: DukeFile): String {
        val virtual = file.virtualFile ?: return file.name
        return DukeAssets.rootOf(file)?.let { VfsUtilCore.getRelativePath(virtual, it) } ?: file.name
    }

    private class Builder(file: DukeFile) {
        private val pointers = SmartPointerManager.getInstance(file.project)
        private val document: Document? = PsiDocumentManager.getInstance(file.project).getDocument(file)
        private val files: List<String> by lazy { DukeAssets.rootOf(file)?.let(DukeAssets::filesUnder).orEmpty().sorted() }

        fun groups(block: DukeBlock, record: PsiClass): List<GroupView> {
            val grouped = linkedMapOf<String, MutableList<Row>>()
            var current = record.name.orEmpty()
            for (component in record.recordComponents) {
                groupOf(component)?.let { current = it }
                grouped.getOrPut(current) { mutableListOf() } += rowsOf(block, record, component, "", 0)
            }
            return grouped.map { (title, rows) -> GroupView(title, rows) }
        }

        private fun groupOf(component: PsiRecordComponent): String? =
            DukeRecords.constantString(component.getAnnotation(GROUP)?.findAttributeValue("value"))

        private fun rowsOf(owner: DukeBlock, record: PsiClass, component: PsiRecordComponent, prefix: String, depth: Int): List<Row> {
            val key = DukeRecords.capitalized(component.name)
            val id = if (prefix.isEmpty()) key else "$prefix/$key"
            val place = Place(pointers.createSmartPsiElementPointer(owner), key, record.recordComponents.map { DukeRecords.capitalized(it.name) })
            val type = component.type
            val default = Defaults.of(record)[component.name] ?: plainDefault(type)
            val help = Help(humanize(key), key, type.presentableText, default, Docs.of(record)[component.name].orEmpty())
            val field = owner.field(key)
            if (DukeRecords.isMap(type)) return listOf(entries(owner, component, id, key, depth, place, help, default))
            if (DukeRecords.isCollection(type)) {
                val element = DukeRecords.blockClass(type)
                if (element != null && DukeRecords.isChoosable(element) && !isTuple(element)) {
                    return cards(owner, element, field, id, key, depth, place, help)
                }
                return listOf(values(owner, component, field, id, key, depth, place, help, default))
            }
            val type0 = DukeRecords.classOf(type)
            if (type0 != null && DukeRecords.isChoosable(type0)) {
                if (type0.isRecord && isTuple(type0)) {
                    val labels = type0.recordComponents.map { humanize(DukeRecords.capitalized(it.name)) }
                    return listOf(row(id, key, depth, field, ValueEditor.Tuple(labels), place, type, help, default))
                }
                return nested(owner, type0, type, field, id, key, depth, place, help, default)
            }
            return listOf(scalar(owner, component, field, id, key, depth, place, help, default))
        }

        /** One value: its editor from the type, and what is wrong with what is written. */
        private fun scalar(
            owner: DukeBlock, component: PsiRecordComponent, field: DukeField?, id: String, key: String, depth: Int,
            place: Place, help: Help, default: String,
        ): FieldRow {
            val type = component.type
            val written = field?.valueText ?: field?.let(::writtenText)
            val link = DukeLinks.linkOf(component)
            val editor = when {
                DukeRecords.isBoolean(type) -> ValueEditor.Check
                DukeRecords.constantsOf(type) != null -> ValueEditor.Choice(DukeRecords.constantsOf(type)!!.map { it.name })
                link != null && !DukeLinks.isOneLine(component) -> ValueEditor.Link(link.name.orEmpty(), DukeLinks.blocksOf(link, owner).keys.sorted())
                DukeLinks.isClip(component) -> clip(owner, key)
                isNumeric(type) && isColour(key) -> ValueEditor.Colour
                isNumeric(type) -> ValueEditor.Text(numeric = true)
                isText(type) && (AssetKind.named(key) != null || (written ?: default).let(AssetKind::of) != null) ->
                    ValueEditor.Path(files.filter { AssetKind.of(it) in kindsFor(key, written ?: default) })
                else -> ValueEditor.Text(numeric = false)
            }
            var problem = written?.let { DukeRecords.problemOf(it, type, key) }
            if (problem == null && written != null && link != null && DukeLinks.isOneLine(component)) {
                val name = DukeLinks.linkedName(component, written)
                if (name !in DukeLinks.blocksOf(link, owner)) problem = "No ${link.name} is called '$name'"
            }
            if (problem == null && written != null) {
                problem = when (editor) {
                    is ValueEditor.Link -> if (written !in editor.names) "No ${editor.record} is called '$written'" else null
                    is ValueEditor.Clip -> if (editor.clips.isNotEmpty() && editor.clips.none { it.name == written }) "No clip '$written' in the files it is drawn from" else null
                    else -> null
                }
            }
            return row(id, key, depth, field, editor, place, type, help, default, problem)
        }

        /** A clip, with the one a block it links names for the same key when it names none itself. */
        private fun clip(owner: DukeBlock, key: String): ValueEditor.Clip {
            val clips = DukeLinks.clipFiles(owner).flatMap { (path, names) -> names.map { ClipOption(it, path.substringAfterLast('/')) } }
                .distinctBy { it.name }
            val record = DukeRecords.recordOf(owner)
            for (linking in owner.fields) {
                val link = record?.let { DukeRecords.component(it, linking.key) }?.let(DukeLinks::linkOf) ?: continue
                val name = linking.valueText ?: continue
                val linked = DukeLinks.blocksOf(link, owner)[name] ?: continue
                linked.field(key)?.valueText?.let { return ValueEditor.Clip(clips, it, name) }
            }
            return ValueEditor.Clip(clips, null, null)
        }

        /** `[a, b]`: chosen from the constants or the linked names there are, else typed. */
        private fun values(
            owner: DukeBlock, component: PsiRecordComponent, field: DukeField?, id: String, key: String, depth: Int,
            place: Place, help: Help, default: String,
        ): FieldRow {
            val element = DukeRecords.elementOf(component.type)
            val link = DukeLinks.linkOf(component)
            val names = link?.let { DukeLinks.blocksOf(it, owner).keys.sorted() }
            // `Skeleton 17 16` names a Skeleton, and is typed: its first word is what is checked.
            val oneLine = names != null && DukeLinks.isOneLine(component)
            val options = DukeRecords.constantsOf(element)?.map { it.name } ?: names.takeUnless { oneLine }
            val items = field?.values?.map { it.unquoted }.orEmpty()
            val problem = if (oneLine) {
                items.map { DukeLinks.linkedName(component, it) }.firstOrNull { it !in names }?.let { "No ${link.name} is called '$it'" }
            } else if (options != null) items.firstOrNull { it !in options }?.let { "'$it' is none of ${if (options.size > 8) "the ${options.size} there are" else options.joinToString()}" } else null
            return row(id, key, depth, field, ValueEditor.Values(options), place, component.type, help, default, problem, items)
        }

        /** A map, `Armor … End`: a row of its entries, its keys chosen from an enum's constants or the names it links. */
        private fun entries(
            owner: DukeBlock, component: PsiRecordComponent, id: String, key: String, depth: Int, place: Place, help: Help, default: String,
        ): FieldRow {
            val map = owner.blocks.firstOrNull { it.wordText.equals(key, ignoreCase = true) }
            val keyType = DukeRecords.typeArgument(component.type, 0)
            val keys = DukeRecords.constantsOf(keyType)?.map { it.name }
                ?: DukeLinks.linkOf(component)?.let { DukeLinks.blocksOf(it, owner).keys.sorted() }
            val valueType = DukeRecords.typeArgument(component.type, 1)
            val items = map?.fields?.map { "${it.key} = ${it.valueText ?: writtenText(it)}" }.orEmpty()
            val line = map?.let { lineOf(it) }
            return FieldRow(id, humanize(key), depth, map?.let { "${items.size} entries" }, items, default,
                ValueEditor.Entries(keys, valueType != null && isNumeric(valueType)), place, component.type, null, line, help)
        }

        /** A record inside another: a head row and its own fields under it; a sealed type's word chosen in the head. */
        private fun nested(
            owner: DukeBlock, type: PsiClass, componentType: PsiType, field: DukeField?, id: String, key: String, depth: Int,
            place: Place, help: Help, default: String,
        ): List<Row> {
            val words = DukeRecords.choices(type, owner).map { it.first }
            val nested = field?.nested
            val rows = mutableListOf<Row>()
            if (type.isRecord) {
                rows += RecordRow(id, humanize(key), depth, nested?.wordText, words, place, nested?.let { lineOf(field) }, help)
            } else {
                rows += FieldRow(id, humanize(key), depth, nested?.wordText, emptyList(), default, ValueEditor.Variant(words), place,
                    componentType, null, field?.let(::lineOf), help)
            }
            val record = nested?.let(DukeRecords::recordOf) ?: return rows
            for (component in record.recordComponents) rows += rowsOf(nested, record, component, id, depth + 1)
            return rows
        }

        /** A list of blocks: a card for each, its fields under it, and a row to add one more. */
        private fun cards(
            owner: DukeBlock, element: PsiClass, field: DukeField?, id: String, key: String, depth: Int, place: Place, help: Help,
        ): List<Row> {
            val rows = mutableListOf<Row>()
            val blocks = field?.blocks.orEmpty()
            if (blocks.isEmpty()) rows += NoteRow("$id/none", depth, "None yet.")
            blocks.forEachIndexed { index, block ->
                val cardId = "$id/$index"
                val record = DukeRecords.recordOf(block)
                val groups = moduleGroups(DukeRecords.choices(element, owner).firstOrNull { it.first.equals(block.wordText, ignoreCase = true) }?.second)
                rows += CardRow(cardId, depth, block.wordText, groups, pointers.createSmartPsiElementPointer(block), index, blocks.size,
                    lineOf(block), Help(block.wordText, block.wordText, groups.joinToString(), "", record?.let { Docs.summary(it) }.orEmpty()))
                if (record == null) return@forEachIndexed
                if (record.recordComponents.isEmpty()) rows += NoteRow("$cardId/none", depth + 1, "No settings — adding it is the whole of what it does.")
                for (component in record.recordComponents) rows += rowsOf(block, record, component, cardId, depth + 1)
            }
            val choices = DukeRecords.choices(element, owner).map { BlockChoice(it.first, moduleGroups(it.second)) }
            val noun = if (element.qualifiedName == "uz.duke.core.module.ModuleData") "module" else (element.name ?: key).lowercase()
            rows += AddRow("$id/add", depth, "Add $noun", place, choices)
            return rows
        }

        /** The families a module's class says it is in: `@ModuleGroup({ModuleGroups.EFFECT, ModuleGroups.COMBAT})`. */
        private fun moduleGroups(target: PsiElement?): List<String> {
            val value = (target as? PsiClass)?.getAnnotation(MODULE_GROUP)?.findAttributeValue("value") ?: return emptyList()
            val parts = (value as? PsiArrayInitializerMemberValue)?.initializers?.toList() ?: listOf(value)
            return parts.mapNotNull { DukeRecords.constantString(it) }
        }

        private fun row(
            id: String, key: String, depth: Int, field: DukeField?, editor: ValueEditor, place: Place, type: PsiType, help: Help,
            default: String, problem: String? = null, items: List<String> = field?.values?.map { it.unquoted }.orEmpty(),
        ) = FieldRow(id, humanize(key), depth, field?.let { it.valueText ?: writtenText(it) }, items, default, editor, place, type, problem,
            field?.let(::lineOf), help)

        /** A field's value as its line writes it, when that is not one value: `[a, b]`. */
        private fun writtenText(field: DukeField): String = field.list?.let { list -> "[" + list.items.joinToString(", ") { it.unquoted } + "]" }
            ?: field.nested?.wordText.orEmpty()

        private fun lineOf(element: PsiElement): Int? = document?.getLineNumber(element.textRange.startOffset)

        private fun kindsFor(key: String, value: String): Set<AssetKind> =
            AssetKind.named(key)?.let(::setOf) ?: listOfNotNull(AssetKind.of(value)).toSet()
    }

    /** A small record of numbers — `Band(nearest, furthest)` — written positionally, `[20, 60]`. */
    fun isTuple(record: PsiClass): Boolean {
        val components = record.recordComponents
        return record.isRecord && components.size in 1..4 && components.all { isNumeric(it.type) }
    }

    private fun isNumeric(type: PsiType) = DukeRecords.plainName(type) in setOf("int", "long", "float", "double", "short", "byte")

    fun isText(type: PsiType) = type.canonicalText == "java.lang.String" || type.presentableText == "String"

    /** A packed `0xRRGGBB` by its name: a colour or a tint. */
    private fun isColour(key: String) = listOf("colour", "color", "tint").any { key.contains(it, ignoreCase = true) }
        || key.equals("tint", ignoreCase = true)

    fun plainDefault(type: PsiType): String = when {
        DukeRecords.isBoolean(type) -> "No"
        isNumeric(type) -> "0"
        DukeRecords.isCollection(type) || DukeRecords.isMap(type) -> "[]"
        else -> "none"
    }

    /** `SenseRadius` as a person reads it: "Sense radius". */
    fun humanize(key: String): String =
        key.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase().replaceFirstChar(Char::uppercaseChar)
}

/** What a record leaves out, as its `static final DEFAULTS` says, component by component. */
object Defaults {
    /**
     * The line a record written inside another opens with: a block's body is what is indented under its word, so
     * an empty one is no body at all. Its first component with a default that reads as written.
     */
    fun firstLine(record: PsiClass): String? {
        val defaults = of(record)
        val components = record.recordComponents
        for (component in components) {
            val text = defaults[component.name] ?: InspectorModels.plainDefault(component.type)
            if (text != "none" && text != "…") return "${DukeRecords.capitalized(component.name)} = $text"
        }
        return components.firstOrNull()?.let { "${DukeRecords.capitalized(it.name)} =" }
    }

    fun of(record: PsiClass): Map<String, String?> {
        val call = record.findFieldByName("DEFAULTS", false)?.initializer as? PsiNewExpression ?: return emptyMap()
        val arguments = call.argumentList?.expressions ?: return emptyMap()
        return record.recordComponents.zip(arguments).associate { (component, argument) -> component.name to textOf(argument) }
    }

    /** An argument as a file would write it: `90f` as 90, `null` as none, `List.of()` as `[]`, a constant by its name. */
    fun textOf(expression: PsiExpression): String? = when (expression) {
        is PsiLiteralExpression -> when (val value = expression.value) {
            null -> "none"
            is String -> value.ifEmpty { "none" }
            is Boolean -> if (value) "Yes" else "No"
            is Char -> if (value == '\u0000') "none" else value.toString()
            // The suffix is read off by the literal's type: a hex colour ends in F and is no float.
            else -> when (expression.type) {
                PsiTypes.floatType(), PsiTypes.doubleType() ->
                    expression.text.trimEnd('f', 'F', 'd', 'D').let { if (it.endsWith(".0")) it.dropLast(2) else it }
                PsiTypes.longType() -> expression.text.trimEnd('L', 'l')
                else -> expression.text
            }
        }
        is PsiPrefixExpression -> expression.operand?.let(::textOf)?.let { expression.operationSign.text + it }
        is PsiMethodCallExpression -> {
            val name = expression.methodExpression.referenceName
            val arguments = expression.argumentList.expressions
            if (name == "of" && arguments.isEmpty()) "[]"
            else if (name == "of") "[" + arguments.joinToString(", ") { textOf(it) ?: it.text } + "]"
            else expression.text
        }
        is PsiReferenceExpression -> when (val target = expression.resolve()) {
            is com.intellij.psi.PsiEnumConstant -> target.name
            is PsiField -> (target.initializer as? PsiNewExpression)
                ?.takeIf { DukeRecords.classOf(it.type)?.let(InspectorModels::isTuple) == true }?.argumentList?.expressions
                ?.takeIf { arguments -> arguments.all { it is PsiLiteralExpression || it is PsiPrefixExpression } }
                ?.let { arguments -> "[" + arguments.joinToString(", ") { textOf(it) ?: it.text } + "]" }
                ?: target.name
            else -> expression.referenceName
        }
        is PsiNewExpression -> "…"
        else -> expression.text
    }
}

/** What a record's Javadoc says of each of its components: its `@param` lines, as plain text. */
object Docs {
    fun of(record: PsiClass): Map<String, String> {
        val comment = record.docComment ?: return emptyMap()
        return comment.findTagsByName("param").mapNotNull { tag ->
            val name = tag.valueElement?.text ?: return@mapNotNull null
            name to plain(tag.dataElements.drop(1).joinToString(" ") { it.text })
        }.toMap()
    }

    /** The first sentence of what a record's Javadoc says of it. */
    fun summary(record: PsiClass): String {
        val comment = record.docComment ?: return ""
        val text = plain(comment.descriptionElements.joinToString("") { it.text })
        return text.substringBefore(". ", text).let { if (it.length < text.length) "$it." else it }
    }

    private fun plain(text: String) = text
        .replace(Regex("\\{@\\w+\\s+([^}]*)}"), "$1")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
