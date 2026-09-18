package uz.duke.plugin.ini

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import uz.duke.plugin.DukeBundle
import uz.duke.plugin.ini.DukeIniTypes as T

class DukeIniSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = DukeIniLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = SyntaxHighlighterBase.pack(COLORS[tokenType])

    companion object {
        val KEYWORD = createTextAttributesKey("DUKE_INI_KEYWORD", Default.KEYWORD)
        val NAME = createTextAttributesKey("DUKE_INI_NAME", Default.CLASS_NAME)
        val MODULE_NAME = createTextAttributesKey("DUKE_INI_MODULE_NAME", Default.CLASS_REFERENCE)
        val KEY = createTextAttributesKey("DUKE_INI_KEY", Default.INSTANCE_FIELD)
        val VALUE = createTextAttributesKey("DUKE_INI_VALUE", Default.STRING)
        val NUMBER = createTextAttributesKey("DUKE_INI_NUMBER", Default.NUMBER)
        val STRING = createTextAttributesKey("DUKE_INI_STRING", Default.STRING)
        val EQ = createTextAttributesKey("DUKE_INI_EQ", Default.OPERATION_SIGN)
        val COMMENT = createTextAttributesKey("DUKE_INI_COMMENT", Default.LINE_COMMENT)

        private val COLORS: Map<IElementType, TextAttributesKey> = mapOf(
            T.BLOCK_TYPE to KEYWORD, T.END to KEYWORD, T.MODULE_KEY to KEYWORD, T.SECTION_KEY to KEYWORD,
            T.NAME to NAME, T.MODULE_NAME to MODULE_NAME, T.KEY to KEY,
            T.VALUE to VALUE, T.NUMBER to NUMBER, T.STRING to STRING,
            T.EQ to EQ, T.COMMENT to COMMENT,
        )
    }
}

class DukeIniSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = DukeIniSyntaxHighlighter()
}

/**
 * What can be told from the text alone: an unclosed block, an End with nothing to
 * close, a line that is neither header nor field. Nothing here knows which block types
 * or fields the engine registers.
 *
 * A key set twice is not a problem: many fields are lists (`Kind`, `Holds`, `File`),
 * and which ones cannot be told from the text.
 */
class DukeIniAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is DukeIniSection -> {
                if (!element.isClosed) {
                    holder.problem(HighlightSeverity.ERROR, element.headerRange, "annotator.unclosed", element.presentableText)
                }
                if (element is DukeIniBlock && element.header.words.size < 2) {
                    holder.problem(HighlightSeverity.WARNING, element.headerRange, "annotator.no.name")
                }
            }
            is DukeIniBadLine -> holder.problem(
                HighlightSeverity.WARNING, element.textRange,
                if (element.parent is DukeIniFile) "annotator.bad.top" else "annotator.bad.inside",
            )
            else -> if (element.node.elementType == T.END && element.parent is DukeIniFile) {
                holder.problem(HighlightSeverity.ERROR, element.textRange, "annotator.stray.end")
            }
        }
    }

    private fun AnnotationHolder.problem(severity: HighlightSeverity, range: TextRange, key: String, vararg params: Any) {
        newAnnotation(severity, DukeBundle.message(key, *params)).range(range).create()
    }
}
