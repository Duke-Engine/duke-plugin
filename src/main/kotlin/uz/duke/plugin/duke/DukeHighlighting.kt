package uz.duke.plugin.duke

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.modcommand.Presentation
import com.intellij.modcommand.PsiUpdateModCommandAction
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import uz.duke.plugin.DukeBundle
import uz.duke.plugin.assets.AssetKind
import uz.duke.plugin.assets.DukeAssets
import uz.duke.plugin.duke.DukeTypes as T

class DukeSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = DukeLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = pack(COLORS[tokenType])

    companion object {
        val WORD = createTextAttributesKey("DUKE_WORD", Default.CLASS_NAME)
        val KEYWORD = createTextAttributesKey("DUKE_KEYWORD", Default.KEYWORD)
        val KEY = createTextAttributesKey("DUKE_KEY", Default.INSTANCE_FIELD)
        val VALUE = createTextAttributesKey("DUKE_VALUE", Default.STRING)
        val NUMBER = createTextAttributesKey("DUKE_NUMBER", Default.NUMBER)
        val STRING = createTextAttributesKey("DUKE_STRING", Default.STRING)
        val EQ = createTextAttributesKey("DUKE_EQ", Default.OPERATION_SIGN)
        val BRACKETS = createTextAttributesKey("DUKE_BRACKETS", Default.BRACKETS)
        val COMMA = createTextAttributesKey("DUKE_COMMA", Default.COMMA)
        val COMMENT = createTextAttributesKey("DUKE_COMMENT", Default.LINE_COMMENT)
        val BAD = createTextAttributesKey("DUKE_BAD", HighlighterColors.BAD_CHARACTER)

        private val COLORS = mapOf(
            T.WORD to WORD, T.TYPE to WORD, T.END to KEYWORD, T.KEY to KEY, T.VALUE to VALUE, T.NUMBER to NUMBER,
            T.STRING to STRING, T.EQ to EQ, T.LBRACKET to BRACKETS, T.RBRACKET to BRACKETS, T.LIST_END to BRACKETS,
            T.COMMA to COMMA, T.COMMENT to COMMENT, T.BAD to BAD, T.BAD_LINE to BAD,
        )
    }
}

class DukeSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = DukeSyntaxHighlighter()
}

/**
 * What `DukeText` refuses, said as it says it: a block with no `End`, an `End` with nothing open, a
 * key written twice, a list that never closes or has a hole in it, a line that fits nothing. Nothing
 * here knows a record.
 */
class DukeAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is DukeBlock -> if (!element.isClosed) noEnd(element, holder)
            is DukeField -> field(element, holder)
            is DukeList -> list(element, holder)
            is DukeBadLine -> badLine(element, holder)
            else -> when (element.node.elementType) {
                T.END -> if (element.parent is DukeFile) holder.error(element, "duke.stray.end")
                T.BAD -> holder.error(element, if (element.text == "[") "duke.list.in.list" else "duke.after.value")
            }
        }
    }

    /** After a field's `=` the block may be a value whose next line was indented too deep by mistake. */
    private fun noEnd(block: DukeBlock, holder: AnnotationHolder) {
        val field = block.parent as? DukeField
        if (field == null) holder.error(block.word, "duke.no.end", block.wordText)
        else holder.error(block.word, "duke.no.end.value", block.wordText, field.key)
    }

    private fun field(field: DukeField, holder: AnnotationHolder) {
        val block = field.block
        if (block == null) {
            holder.error(field.keyElement, "duke.outside", field.key)
            return
        }
        val first = block.fields.first { it.key.equals(field.key, ignoreCase = true) }
        if (first != field) holder.error(field.keyElement, "duke.twice", field.key, block.wordText, lineOf(first) + 1)
    }

    private fun list(list: DukeList, holder: AnnotationHolder) {
        if (list.holdsBlocks) {
            if (!list.isClosed) holder.error(list.firstChild, "duke.block.list.open", (list.parent as? DukeField)?.key.orEmpty())
            return
        }
        if (!list.isClosed) holder.error(list.firstChild, "duke.list.open")
        // Between items a comma, and an item between commas; a trailing comma closes nothing.
        var previous: PsiElement? = null
        for (child in list.node.getChildren(null).map { it.psi }) {
            val comma = child.node.elementType == T.COMMA
            if (!comma && child !is DukeValue) continue
            if (comma && (previous == null || previous.node.elementType == T.COMMA)) holder.error(child, "duke.list.empty.item")
            if (child is DukeValue && previous is DukeValue) holder.error(child, "duke.list.no.comma")
            previous = child
        }
    }

    private fun badLine(line: DukeBadLine, holder: AnnotationHolder) {
        val list = line.parent as? DukeList
        if (list != null) {
            holder.error(line, "duke.block.list.item", (list.parent as? DukeField)?.key.orEmpty(), line.text)
            return
        }
        if (line.text == "]") {
            holder.error(line, "duke.stray.list.end")
            return
        }
        val named = NAMED_HEADER.matchEntire(line.text)
        if (named == null) {
            holder.error(line, "duke.bad.line")
            return
        }
        val (word, name) = named.destructured
        holder.newAnnotation(HighlightSeverity.ERROR, DukeBundle.message("duke.named.header", word, name)).range(line)
            .withFix(NameInsideFix(line)).create()
    }

    private fun lineOf(element: PsiElement): Int =
        element.containingFile.viewProvider.document?.getLineNumber(element.textRange.startOffset) ?: 0

    private companion object {
        /** `Monster Brute`: the INI habit of naming a block in its header. */
        val NAMED_HEADER = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s+(\\S+)")
    }
}

/** `Monster Brute` becomes `Monster` with `Name = Brute` inside it. */
class NameInsideFix(line: DukeBadLine) : PsiUpdateModCommandAction<DukeBadLine>(line) {
    override fun getFamilyName() = DukeBundle.message("duke.named.header.fix")

    override fun invoke(context: ActionContext, element: DukeBadLine, updater: ModPsiUpdater) {
        val text = element.containingFile.text
        val start = element.textRange.startOffset
        val indent = text.substring(text.lastIndexOf('\n', start - 1) + 1, start)
        val (word, name) = element.text.split(Regex("\\s+"), limit = 2)
        val file = PsiFileFactory.getInstance(context.project()).createFileFromText("dummy.duke", DukeFileType, "$word\n$indent  Name = $name")
        element.replace(file.firstChild)
    }
}

/**
 * What `Binder` refuses, said as it says it, found by reading the game's records: a word no block
 * can be where it is, a key its record has no component for, and a value its component's type cannot
 * read. Without the engine on the classpath there is nothing to check against, so nothing is flagged.
 */
class DukeEngineAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is DukeWord -> word(element, holder)
            is DukeField -> field(element, holder)
        }
    }

    private fun word(word: DukeWord, holder: AnnotationHolder) {
        val block = word.block
        val owner = block.owner
        val shape = DukeRecords.shapeOf(block)
        if (owner == null) {
            if (shape == null && engineFound(word)) holder.error(word, "duke.unknown.block", word.text)
            return
        }
        val field = block.owningField
        if (field != null) {
            // The record after `Key =`, or an item of its list: one the field may be.
            if (shape != null) return
            val record = DukeRecords.recordOf(owner) ?: return
            val type = DukeRecords.component(record, field.key)?.type?.let(DukeRecords::blockClass) ?: return
            if (!DukeRecords.isChoosable(type)) return
            holder.plain(word, notOneOf(type, field.key, word.text, word))
            return
        }
        when (shape) {
            is DukeShape.Entries -> {
                val first = owner.blocks.first { it.wordText.equals(block.wordText, ignoreCase = true) }
                if (first != block) holder.error(word, "duke.block.twice", word.text, owner.wordText)
            }
            else -> when {
                DukeRecords.shapeOf(owner) is DukeShape.Entries -> holder.error(word, "duke.entries.not.blocks", owner.wordText)
                else -> DukeRecords.recordOf(owner)?.let { holder.plain(word, DukeRecords.misplaced(it, owner.wordText, word.text, word)) }
            }
        }
    }

    private fun field(field: DukeField, holder: AnnotationHolder) {
        val block = field.block ?: return
        when (val shape = DukeRecords.shapeOf(block)) {
            is DukeShape.Record -> {
                val component = DukeRecords.component(shape.record, field.key)
                if (component == null) {
                    holder.error(field.keyElement, "duke.no.field", block.wordText, field.key)
                    return
                }
                value(field, component.type, holder)
                val link = DukeLinks.linkOf(component) ?: return
                val names = DukeLinks.blocksOf(link, field)
                for (named in field.values) {
                    val name = DukeLinks.linkedName(component, named.unquoted)
                    if (name !in names) holder.error(named, "duke.no.link", link.name.orEmpty(), name)
                }
            }
            is DukeShape.Entries -> {
                DukeLinks.linkOf(shape.component)?.let { link ->
                    if (field.key !in DukeLinks.blocksOf(link, field)) holder.error(field.keyElement, "duke.no.link", link.name.orEmpty(), field.key)
                }
                DukeRecords.typeArgument(shape.component.type, 0)?.let { type ->
                    DukeRecords.problemOf(field.key, type, field.key)?.let { holder.plain(field.keyElement, it) }
                }
                DukeRecords.typeArgument(shape.component.type, 1)?.let { value(field, it, holder) }
            }
            null -> {}
        }
    }

    /** The value [field] writes, as `Binder.value` reads it for [type]. */
    private fun value(field: DukeField, type: PsiType, holder: AnnotationHolder) {
        val key = field.key
        val list = field.list
        val at: PsiElement = field.nested?.word ?: list ?: field.value ?: field.keyElement
        if (DukeRecords.isMap(type)) {
            holder.error(at, "duke.is.map", key)
            return
        }
        if (DukeRecords.isCollection(type)) {
            val blocks = DukeRecords.isChoosable(DukeRecords.blockClass(type))
            when {
                list == null -> holder.error(at, if (blocks) "duke.is.block.list" else "duke.is.list", key)
                list.holdsBlocks && !blocks -> holder.error(list.firstChild, "duke.is.list", key)
                !list.holdsBlocks && blocks -> holder.error(list, "duke.is.block.list", key)
                !list.holdsBlocks -> {
                    val element = DukeRecords.elementOf(type) ?: return
                    for (item in list.items) DukeRecords.problemOf(item.unquoted, element, key)?.let { holder.plain(item, it) }
                }
            }
            return
        }
        val cls = DukeRecords.classOf(type)
        val choosable = DukeRecords.isChoosable(cls)
        if (field.nested != null) {
            if (!choosable) holder.error(at, "duke.is.value", key)
            return
        }
        if (list != null) {
            if (list.holdsBlocks || cls == null || !cls.isRecord) {
                holder.error(list, "duke.not.list", key)
                return
            }
            val components = cls.recordComponents
            if (components.size != list.items.size) {
                holder.error(list, "duke.list.size", key, components.size, list.items.size)
                return
            }
            list.items.zip(components).forEach { (item, component) ->
                DukeRecords.problemOf(item.unquoted, component.type, key)?.let { holder.plain(item, it) }
            }
            return
        }
        val value = field.value
        if (choosable && cls != null && value != null) {
            if (DukeRecords.accepting(cls, value.unquoted, value) == null) holder.plain(value, notOneOf(cls, key, value.unquoted, value))
            return
        }
        val problem = DukeRecords.problemOf(value?.unquoted.orEmpty(), type, key) ?: return
        holder.plain(value ?: field.keyElement, problem)
    }

    private fun notOneOf(type: PsiClass, key: String, word: String, context: PsiElement) =
        "'$key' is one of ${DukeRecords.choices(type, context).map { it.first }}, not '$word'"

    private fun engineFound(context: PsiElement) =
        JavaPsiFacade.getInstance(context.project).findClass(BINDER_CLASS, context.resolveScope) != null

    private companion object {
        const val BINDER_CLASS = "uz.duke.core.data.Binder"
    }
}

/**
 * A path is whole from the resource root the file sits in, as the game loads it. A missing file is an
 * error, letter case included; a file of the wrong kind for its key is a warning.
 */
class DukeAssetAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is DukeValue) return
        val path = element.unquoted
        val kind = AssetKind.of(path) ?: return
        val root = DukeAssets.rootOf(element) ?: return
        if (DukeAssets.resolve(element, path) == null) {
            val suggestion = DukeAssets.suggest(root, path)
            val message = if (suggestion == null) DukeBundle.message("asset.not.found", path)
            else DukeBundle.message("asset.not.found.suggest", path, suggestion)
            val annotation = holder.newAnnotation(HighlightSeverity.ERROR, message).range(element)
            if (suggestion != null) annotation.withFix(UseFileFix(element, suggestion).asIntention())
            annotation.create()
            return
        }
        val key = element.field?.key ?: return
        val expected = AssetKind.named(key) ?: return
        if (kind != expected) {
            holder.newAnnotation(HighlightSeverity.WARNING, DukeBundle.message("asset.wrong.kind", key, expected, path.substringAfterLast('.')))
                .range(element).create()
        }
    }
}

class UseFileFix(value: DukeValue, private val path: String) : PsiUpdateModCommandAction<DukeValue>(value) {
    override fun getFamilyName() = DukeBundle.message("asset.fix.family")

    override fun getPresentation(context: ActionContext, element: DukeValue): Presentation =
        Presentation.of(DukeBundle.message("asset.fix", path))

    override fun invoke(context: ActionContext, element: DukeValue, updater: ModPsiUpdater) {
        val file = PsiFileFactory.getInstance(context.project()).createFileFromText("dummy.duke", DukeFileType, "A\n  Key = $path\nEnd\n")
        element.replace(PsiTreeUtil.findChildOfType(file, DukeValue::class.java)!!)
    }
}

private fun AnnotationHolder.error(at: PsiElement, key: String, vararg params: Any) =
    newAnnotation(HighlightSeverity.ERROR, DukeBundle.message(key, *params)).range(at).create()

/** An error already in the engine's own words. */
private fun AnnotationHolder.plain(at: PsiElement, message: String) =
    newAnnotation(HighlightSeverity.ERROR, message).range(at).create()
