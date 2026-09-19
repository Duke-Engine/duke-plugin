package uz.duke.plugin.duke

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import uz.duke.plugin.duke.DukeTypes as T

/**
 * Groups the lines the lexer has already told apart. A word opens a block and `End` closes the
 * innermost open one, as `DukeText` reads them; a block still open at the end of the file has no
 * `End`, which is how the annotator knows.
 */
class DukeParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val file = builder.mark()
        while (!builder.eof()) {
            when (builder.tokenType) {
                T.WORD -> block(builder)
                T.KEY -> field(builder) // outside any block: the annotator says so
                T.END -> line(builder) // an End with nothing open stays a bare token for the annotator
                else -> badLine(builder)
            }
        }
        file.done(root)
        return builder.treeBuilt
    }

    private fun block(b: PsiBuilder) {
        val block = b.mark()
        val word = b.mark()
        b.advanceLexer()
        word.done(T.BLOCK_WORD)
        rest(b)
        while (!b.eof()) {
            when (b.tokenType) {
                T.END -> {
                    line(b)
                    block.done(T.BLOCK)
                    return
                }
                T.WORD -> block(b)
                T.KEY -> field(b)
                else -> badLine(b)
            }
        }
        block.done(T.BLOCK)
    }

    private fun field(b: PsiBuilder) {
        val field = b.mark()
        val key = b.mark()
        b.advanceLexer()
        key.done(T.FIELD_KEY)
        if (b.tokenType == T.EQ) b.advanceLexer()
        when (b.tokenType) {
            T.LBRACKET -> list(b)
            in T.VALUES -> item(b)
        }
        rest(b)
        field.done(T.FIELD)
    }

    /** `[a, b]`, over as many lines as it runs: the lexer starts no line inside it. */
    private fun list(b: PsiBuilder) {
        val list = b.mark()
        b.advanceLexer()
        while (!b.eof() && b.tokenType != T.RBRACKET && b.tokenType !in T.LINE_STARTS) {
            if (b.tokenType in T.VALUES) item(b) else b.advanceLexer()
        }
        if (b.tokenType == T.RBRACKET) b.advanceLexer()
        list.done(T.LIST)
    }

    private fun item(b: PsiBuilder) {
        val item = b.mark()
        b.advanceLexer()
        item.done(T.ITEM)
    }

    /** What is left of the line: every token up to the one that starts the next. */
    private fun rest(b: PsiBuilder) {
        while (!b.eof() && b.tokenType !in T.LINE_STARTS) b.advanceLexer()
    }

    private fun line(b: PsiBuilder) {
        b.advanceLexer()
        rest(b)
    }

    private fun badLine(b: PsiBuilder) {
        val bad = b.mark()
        line(b)
        bad.done(T.BAD_LINE_ELEMENT)
    }
}

class DukeParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?) = DukeLexer()
    override fun createParser(project: Project?) = DukeParser()
    override fun getFileNodeType() = T.FILE
    override fun getCommentTokens(): TokenSet = COMMENTS
    override fun getStringLiteralElements(): TokenSet = STRINGS
    override fun createFile(viewProvider: FileViewProvider) = DukeFile(viewProvider)

    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        T.BLOCK -> DukeBlock(node)
        T.BLOCK_WORD -> DukeWord(node)
        T.FIELD -> DukeField(node)
        T.FIELD_KEY -> DukeKey(node)
        T.LIST -> DukeList(node)
        T.ITEM -> DukeValue(node)
        T.BAD_LINE_ELEMENT -> DukeBadLine(node)
        else -> throw IllegalArgumentException("Unknown element ${node.elementType}")
    }

    private companion object {
        val COMMENTS = TokenSet.create(T.COMMENT)
        val STRINGS = TokenSet.create(T.STRING)
    }
}
