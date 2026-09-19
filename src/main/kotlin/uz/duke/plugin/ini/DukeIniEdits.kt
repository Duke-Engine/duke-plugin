package uz.duke.plugin.ini

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Changes the Inspector makes to a file, as text: a value replaced, a line added or removed. The
 * text stays what the game reads, so every check the editor runs keeps running, comments stay
 * where they were, and Ctrl+Z undoes each change as one step.
 */
object DukeIniEdits {
    /** Runs [edit] on [file]'s document as one undoable command, and commits it. */
    fun write(project: Project, file: PsiFile, name: String, edit: (Document) -> Unit) {
        WriteCommandAction.runWriteCommandAction(project, name, null, Runnable {
            val documents = PsiDocumentManager.getInstance(project)
            val document = documents.getDocument(file) ?: return@Runnable
            documents.doPostponedOperationsAndUnblockDocument(document)
            documents.commitDocument(document) // the offsets below are the tree's; typing may not have reached it yet
            edit(document)
            documents.commitDocument(document)
        }, file)
    }

    /** Everything after `=` becomes [value]; a comment after it stays. */
    fun setValue(document: Document, field: DukeIniField, value: String) {
        val end = field.textRange.endOffset
        val eq = field.node.findChildByType(DukeIniTypes.EQ)
        if (eq == null) document.replaceString(field.firstChild.textRange.endOffset, end, " = $value")
        else document.replaceString(eq.textRange.endOffset, end, " $value")
    }

    fun addField(document: Document, section: DukeIniSection, key: String, value: String) =
        insertBeforeEnd(document, section, "${innerIndent(document, section)}$key = $value\n")

    /** A line after [after], indented as it is: the next entry of a list. */
    fun addFieldAfter(document: Document, after: DukeIniField, key: String, value: String) {
        val lineEnd = document.getLineEndOffset(document.getLineNumber(after.textRange.endOffset))
        document.insertString(lineEnd, "\n${indentOf(document, after.textRange.startOffset)}$key = $value")
    }

    /** `Generation = Layout`, its fields and its `End`, before [parent]'s own `End`. */
    fun addSection(document: Document, parent: DukeIniSection, key: String, names: String, fields: List<Pair<String, String>>) {
        val indent = innerIndent(document, parent)
        insertBeforeEnd(document, parent, buildString {
            append("$indent$key = $names\n")
            for ((name, value) in fields) append("$indent  $name = $value\n")
            append("${indent}End\n")
        })
    }

    /** A new block at the end of the file, a blank line before it. */
    fun addBlock(document: Document, header: String, fields: List<Pair<String, String>>) {
        val text = document.charsSequence
        val gap = when {
            text.isEmpty() || text.endsWith("\n\n") -> ""
            text.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        document.insertString(document.textLength, gap + blockText(header, fields))
    }

    fun blockText(header: String, fields: List<Pair<String, String>>) = buildString {
        append(header).append('\n')
        for ((name, value) in fields) append("  $name = $value\n")
        append("End\n")
    }

    /** Deletes the whole lines [element] spans: a field, a section or a block. */
    fun remove(document: Document, element: PsiElement) {
        val range = element.textRange
        val last = document.getLineNumber(range.endOffset)
        val end = if (last + 1 < document.lineCount) document.getLineStartOffset(last + 1) else document.textLength
        document.deleteString(document.getLineStartOffset(document.getLineNumber(range.startOffset)), end)
    }

    private fun insertBeforeEnd(document: Document, section: DukeIniSection, text: String) {
        val end = section.node.findChildByType(DukeIniTypes.END)
        if (end != null) {
            document.insertString(document.getLineStartOffset(document.getLineNumber(end.startOffset)), text)
        } else {
            val lineEnd = document.getLineEndOffset(document.getLineNumber(section.textRange.endOffset))
            document.insertString(lineEnd, "\n" + text.removeSuffix("\n"))
        }
    }

    /** As the section's first field or section is indented, else two more than its first line. */
    private fun innerIndent(document: Document, section: DukeIniSection): String {
        val first: PsiElement? = section.fields.firstOrNull() ?: section.parts.firstOrNull()
        return if (first != null) indentOf(document, first.textRange.startOffset) else indentOf(document, section.textRange.startOffset) + "  "
    }

    private fun indentOf(document: Document, offset: Int): String =
        document.charsSequence.subSequence(document.getLineStartOffset(document.getLineNumber(offset)), offset).toString()
}
