package uz.duke.plugin.inspector

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import uz.duke.plugin.duke.DukeBlock
import uz.duke.plugin.duke.DukeField
import uz.duke.plugin.duke.DukeList
import uz.duke.plugin.duke.DukeTypes

/**
 * Every change the Inspector makes, as text in the document: a line written, rewritten or taken out, a block
 * added to a list or moved along it. The file stays the truth — the form is read from it again after each
 * change — and each change is one command, so Ctrl+Z takes it back.
 */
object DukeEdits {

    /** One undoable change to [file], its PSI committed before and after. */
    fun write(project: Project, file: PsiFile, name: String, change: (Document) -> Unit) {
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file) ?: return
        WriteCommandAction.writeCommandAction(project, file).withName(name).run<RuntimeException> {
            documents.commitDocument(document)
            change(document)
            documents.commitDocument(document)
        }
    }

    /** `Key = text` in [owner]: the written line's value replaced, else a new line where the record puts it. */
    fun setValue(document: Document, owner: DukeBlock, key: String, order: List<String>, text: String) {
        val field = owner.field(key) ?: return insertLines(document, owner, key, order, listOf("$key = $text"))
        replaceValue(document, field, text)
    }

    /** `Key = [a, b]`, over several lines when it was written over several. */
    fun setValues(document: Document, owner: DukeBlock, key: String, order: List<String>, items: List<String>) {
        val field = owner.field(key)
        val list = field?.list
        val text = if (list != null && spansLines(document, list) && items.isNotEmpty()) {
            val indent = indentOf(document, field.textRange.startOffset)
            "[\n" + items.joinToString("") { "$indent  ${quoted(it)},\n" } + "$indent]"
        } else {
            "[" + items.joinToString(", ") { quoted(it) } + "]"
        }
        setValue(document, owner, key, order, text)
    }

    /**
     * One more item at the end of the list [key] holds: on a line of its own when the list is written one item a line,
     * the others and their comments left as they are, a comma after it as the one before has one.
     */
    fun addItem(document: Document, owner: DukeBlock, key: String, order: List<String>, item: String) {
        val field = owner.field(key)
        val list = field?.list
        val last = list?.items?.lastOrNull()
        val close = list?.let(::closing)
        if (last == null || close == null || document.getLineNumber(last.textRange.endOffset) == document.getLineNumber(close)) {
            return setValues(document, owner, key, order, field?.values?.map { it.unquoted }.orEmpty() + item)
        }
        val trailing = ',' in document.charsSequence.subSequence(last.textRange.endOffset, close)
        document.insertString(document.getLineStartOffset(document.getLineNumber(close)),
            indentOf(document, last.textRange.startOffset) + quoted(item) + (if (trailing) "," else "") + "\n")
        if (!trailing) document.insertString(last.textRange.endOffset, ",")
    }

    /** The item at [index] of the list [key] holds, rewritten. */
    fun setItem(document: Document, owner: DukeBlock, key: String, index: Int, item: String) {
        val range = owner.field(key)?.list?.items?.getOrNull(index)?.textRange ?: return
        document.replaceString(range.startOffset, range.endOffset, quoted(item))
    }

    /** An item out of its list: its line, when it has one to itself, else the list written again without it; the last one out takes the list. */
    fun removeItem(document: Document, owner: DukeBlock, key: String, order: List<String>, index: Int) {
        val field = owner.field(key) ?: return
        val items = field.list?.items ?: return
        val item = items.getOrNull(index) ?: return
        if (items.size == 1) return removeLines(document, field)
        val line = lineText(document, document.getLineNumber(item.textRange.startOffset)).trim().removeSuffix(",").trim()
        if (line == item.text) return removeLines(document, item)
        setValues(document, owner, key, order, items.filterIndexed { at, _ -> at != index }.map { it.unquoted })
    }

    /** The line [element] is on — all of its lines, for a block — taken out. */
    fun removeLines(document: Document, element: PsiElement) {
        val range = linesOf(document, element)
        document.deleteString(range.startOffset, range.endOffset)
    }

    /**
     * A record written inside [owner], `Key = Word`, [first] under it and its `End`: a body is what is indented
     * under a word, so the record opens with one line in it — see [Defaults.firstLine].
     */
    fun addNested(document: Document, owner: DukeBlock, key: String, order: List<String>, word: String, first: String?) =
        insertLines(document, owner, key, order, listOfNotNull("$key = $word", first?.let { "  $it" }, "End"))

    /**
     * [block] as another word — `Cylinder` as `Sphere` — its lines the new word's record does not have taken out.
     * Later lines first, so the offsets of the ones before them, the word's among them, hold.
     */
    fun setWord(document: Document, block: DukeBlock, word: String, keep: Set<String>) {
        block.fields.filter { field -> keep.none { it.equals(field.key, ignoreCase = true) } }
            .sortedByDescending { it.textRange.startOffset }
            .forEach { removeLines(document, it) }
        val range = block.word.textRange
        document.replaceString(range.startOffset, range.endOffset, word)
    }

    /** One more block at the end of the list [key] holds: `Modules = [`, a block for each, `]`. */
    fun addBlock(document: Document, owner: DukeBlock, key: String, order: List<String>, word: String) {
        val field = owner.field(key)
        val list = field?.list
        if (list != null && list.holdsBlocks) {
            val close = closing(list) ?: return
            val indent = indentOf(document, close) + "  "
            document.insertString(document.getLineStartOffset(document.getLineNumber(close)), "$indent$word\n${indent}End\n")
            return
        }
        if (field != null) {
            val indent = indentOf(document, field.textRange.startOffset)
            replaceValue(document, field, "[\n$indent  $word\n$indent  End\n$indent]")
            return
        }
        insertLines(document, owner, key, order, listOf("$key = [", "  $word", "  End", "]"))
    }

    /** A block out of its list; the last one out takes the list with it, an empty list of blocks being no list. */
    fun removeBlock(document: Document, block: DukeBlock) {
        val list = block.parent as? DukeList
        if (list != null && list.blocks.size == 1) {
            (list.parent as? DukeField)?.let { return removeLines(document, it) }
        }
        removeLines(document, block)
    }

    /** A block swapped with its neighbour [by] places along, and the lines between them left where they are. */
    fun moveBlock(document: Document, block: DukeBlock, by: Int) {
        val items = (block.parent as? DukeList)?.blocks ?: return
        val from = items.indexOf(block)
        val to = from + by
        if (from < 0 || to !in items.indices) return
        val first = linesOf(document, items[minOf(from, to)])
        val second = linesOf(document, items[maxOf(from, to)])
        val firstText = document.getText(first)
        val secondText = document.getText(second)
        document.replaceString(second.startOffset, second.endOffset, firstText)
        document.replaceString(first.startOffset, first.endOffset, secondText)
    }

    /** `entry = value` in the map [key] of [owner], the map written out if it is not. */
    fun setEntry(document: Document, owner: DukeBlock, key: String, order: List<String>, entry: String, value: String) {
        val map = mapOf(owner, key) ?: return insertLines(document, owner, key, order, listOf(key, "  $entry = $value", "End"))
        setValue(document, map, entry, emptyList(), value)
    }

    /** An entry out of its map; the last one out takes the map with it. */
    fun removeEntry(document: Document, owner: DukeBlock, key: String, entry: String) {
        val map = mapOf(owner, key) ?: return
        val field = map.field(entry) ?: return
        removeLines(document, if (map.fields.size == 1) map else field)
    }

    /** An entry's key rewritten, its value kept. */
    fun renameEntry(document: Document, owner: DukeBlock, key: String, from: String, to: String) {
        val range = mapOf(owner, key)?.field(from)?.keyElement?.textRange ?: return
        document.replaceString(range.startOffset, range.endOffset, to)
    }

    fun mapOf(owner: DukeBlock, key: String): DukeBlock? = owner.blocks.firstOrNull { it.wordText.equals(key, ignoreCase = true) }

    /** A value as a file keeps it: quoted where a `;` would start a comment, a quote end it, or a `[` open a list. */
    fun quoted(text: String): String {
        if (text.isNotEmpty() && text.none { it == ';' || it == '"' } && !text.startsWith("[") && text == text.trim()) return text
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    // ---- where things go ----

    /**
     * Lines written into [owner] where its record puts [key]: after the last component before it that is written,
     * else before the first after it — and before the comment over that one, which is about it — else last.
     */
    private fun insertLines(document: Document, owner: DukeBlock, key: String, order: List<String>, lines: List<String>) {
        val indent = childIndent(document, owner)
        val at = insertionOffset(document, owner, key, order)
        document.insertString(at, lines.joinToString("") { "$indent$it\n" })
    }

    private fun insertionOffset(document: Document, owner: DukeBlock, key: String, order: List<String>): Int {
        fun rank(name: String) = order.indexOfFirst { it.equals(name, ignoreCase = true) }
        val mine = rank(key)
        val written = (owner.fields.map { it as PsiElement to it.key } + owner.blocks.map { it as PsiElement to it.wordText })
            .sortedBy { it.first.textRange.startOffset }
        if (mine >= 0) {
            written.lastOrNull { rank(it.second) in 0 until mine }?.let { return linesOf(document, it.first).endOffset }
            written.firstOrNull { rank(it.second) > mine }?.let { return commentsAbove(document, it.first) }
        }
        return endLine(document, owner)
    }

    /** The start of the line holding [owner]'s `End`, or of the line after its last when it has none. */
    private fun endLine(document: Document, owner: DukeBlock): Int {
        val end = owner.node.findChildByType(DukeTypes.END)
            ?: return linesOf(document, owner).endOffset
        return document.getLineStartOffset(document.getLineNumber(end.startOffset))
    }

    /** Where the lines of comment directly over [element] begin, or its own line when there are none. */
    private fun commentsAbove(document: Document, element: PsiElement): Int {
        var line = document.getLineNumber(element.textRange.startOffset)
        while (line > 0) {
            val above = lineText(document, line - 1).trim()
            if (!above.startsWith(";")) break
            line--
        }
        return document.getLineStartOffset(line)
    }

    /** How deep [owner]'s lines stand: as its first one does, else a step in from where it opens. */
    private fun childIndent(document: Document, owner: DukeBlock): String {
        val first = (owner.fields.map { it.textRange.startOffset } + owner.blocks.map { it.textRange.startOffset }).minOrNull()
        return if (first != null) indentOf(document, first) else indentOf(document, owner.textRange.startOffset) + "  "
    }

    /** Whole lines, from the start of [element]'s first to the start of the line after its last. */
    private fun linesOf(document: Document, element: PsiElement): TextRange {
        val range = element.textRange
        val first = document.getLineNumber(range.startOffset)
        val last = document.getLineNumber(maxOf(range.startOffset, range.endOffset - 1))
        val end = if (last + 1 < document.lineCount) document.getLineStartOffset(last + 1) else document.textLength
        return TextRange(document.getLineStartOffset(first), end)
    }

    /** Everything after a field's `=` — its value, its list, or on a line written `Key =`, where one would go. */
    private fun replaceValue(document: Document, field: DukeField, text: String) {
        val value = field.value?.textRange ?: field.list?.textRange
        if (value != null) {
            document.replaceString(value.startOffset, value.endOffset, text)
            return
        }
        val lineEnd = document.getLineEndOffset(document.getLineNumber(field.textRange.startOffset))
        val chars = document.charsSequence
        var at = field.keyElement.textRange.endOffset
        while (at < lineEnd && chars[at] != '=') at++
        if (at >= lineEnd) {
            document.insertString(lineEnd, " = $text")
            return
        }
        val afterEquals = at + 1
        if (afterEquals < lineEnd && chars[afterEquals] == ' ') document.insertString(afterEquals + 1, text)
        else document.insertString(afterEquals, " $text")
    }

    private fun closing(list: DukeList): Int? =
        (list.node.findChildByType(DukeTypes.LIST_END) ?: list.node.findChildByType(DukeTypes.RBRACKET))?.startOffset

    private fun spansLines(document: Document, element: PsiElement) =
        document.getLineNumber(element.textRange.startOffset) != document.getLineNumber(element.textRange.endOffset)

    private fun indentOf(document: Document, offset: Int): String = lineText(document, document.getLineNumber(offset)).takeWhile { it == ' ' || it == '\t' }

    private fun lineText(document: Document, line: Int): String =
        document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
}
