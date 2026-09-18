package uz.duke.plugin.ini

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import uz.duke.plugin.ini.DukeIniTypes as T

/**
 * One pass over lines: the lexer already said what each line is, so the parser only
 * groups them — a block is its header line, its fields, modules and sections, and its End.
 * A block that meets the next header (or the end of file) first is left without an
 * End, which is how the annotator knows it is unclosed.
 */
class DukeIniParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val file = builder.mark()
        while (!builder.eof()) {
            when (builder.tokenType) {
                T.BLOCK_TYPE -> block(builder)
                T.END -> line(builder) // stray End: stays a bare token for the annotator
                T.EQ -> builder.advanceLexer() // `=` before a line's first token is just a separator
                else -> badLine(builder)
            }
        }
        file.done(root)
        return builder.treeBuilt
    }

    private fun block(b: PsiBuilder) {
        val block = b.mark()
        val header = b.mark()
        line(b) { if (it == T.NAME) T.NAME_ELEMENT else null }
        header.done(T.HEADER_ELEMENT)
        body(b)
        block.done(T.BLOCK_ELEMENT)
    }

    /** Fields and modules up to End; false if the block ran into another header or the end of file. */
    private fun body(b: PsiBuilder): Boolean {
        while (!b.eof()) {
            when (b.tokenType) {
                T.END -> {
                    line(b)
                    return true
                }
                T.BLOCK_TYPE -> return false
                T.MODULE_KEY -> {
                    val module = b.mark()
                    line(b) { if (it == T.MODULE_NAME) T.MODULE_NAME_ELEMENT else null }
                    body(b)
                    module.done(T.MODULE_ELEMENT)
                }
                T.SECTION_KEY -> {
                    val section = b.mark()
                    line(b) { if (it == T.NAME) T.NAME_ELEMENT else null }
                    body(b)
                    section.done(T.SECTION_ELEMENT)
                }
                T.KEY -> {
                    val field = b.mark()
                    line(b) { if (it == T.VALUE) T.VALUE_ELEMENT else null }
                    field.done(T.FIELD_ELEMENT)
                }
                T.EQ -> b.advanceLexer()
                else -> badLine(b)
            }
        }
        return false
    }

    private fun badLine(b: PsiBuilder) {
        val bad = b.mark()
        line(b)
        bad.done(T.BAD_LINE_ELEMENT)
    }

    /** Consumes the current line's first token and the rest of its line, wrapping tokens [wrap] names. */
    private inline fun line(b: PsiBuilder, wrap: (IElementType?) -> IElementType? = { null }) {
        b.advanceLexer()
        while (!b.eof() && b.tokenType !in T.LINE_STARTS) {
            val element = wrap(b.tokenType)
            if (element == null) {
                b.advanceLexer()
            } else {
                val m = b.mark()
                b.advanceLexer()
                m.done(element)
            }
        }
    }
}

class DukeIniParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?) = DukeIniLexer()
    override fun createParser(project: Project?) = DukeIniParser()
    override fun getFileNodeType() = T.FILE
    override fun getCommentTokens(): TokenSet = COMMENTS
    override fun getStringLiteralElements(): TokenSet = STRINGS
    override fun createFile(viewProvider: FileViewProvider) = DukeIniFile(viewProvider)

    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        T.BLOCK_ELEMENT -> DukeIniBlock(node)
        T.MODULE_ELEMENT -> DukeIniModule(node)
        T.SECTION_ELEMENT -> DukeIniSubsection(node)
        T.MODULE_NAME_ELEMENT -> DukeIniModuleName(node)
        T.HEADER_ELEMENT -> DukeIniHeader(node)
        T.FIELD_ELEMENT -> DukeIniField(node)
        T.NAME_ELEMENT, T.VALUE_ELEMENT -> DukeIniWord(node)
        T.BAD_LINE_ELEMENT -> DukeIniBadLine(node)
        else -> throw IllegalArgumentException("Unknown element ${node.elementType}")
    }

    private companion object {
        val COMMENTS = TokenSet.create(T.COMMENT)
        val STRINGS = TokenSet.create(T.STRING)
    }
}
