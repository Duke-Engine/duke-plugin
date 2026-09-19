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
 * innermost open one, as `DukeText` reads them; `Key = Class` opens a block that is the field's
 * value, and a list of blocks holds its items to the `]` on a line of its own. A block still open at
 * the end of the file has no `End`, which is how the annotator knows.
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

    /** A word, or the class after a field's `=`, then the block's lines to its End. */
    private fun block(b: PsiBuilder) {
        val block = b.mark()
        val base = if (b.tokenType == T.TYPE) indentAt(b) else -1
        val word = b.mark()
        b.advanceLexer()
        word.done(T.BLOCK_WORD)
        rest(b)
        body(b, base)
        block.done(T.BLOCK)
    }

    /**
     * The lines to the End. A block after a field's `=` keeps to the depth that opened it, as `DukeText`
     * reads it: a line back at [base], the field's own, that is not its End leaves it without one.
     */
    private fun body(b: PsiBuilder, base: Int) {
        while (!b.eof()) {
            if (base >= 0 && indentAt(b).let { it < base || it == base && b.tokenType != T.END }) return
            when (b.tokenType) {
                T.END -> {
                    line(b)
                    return
                }
                T.WORD -> block(b)
                T.KEY -> field(b)
                else -> badLine(b)
            }
        }
    }

    private fun field(b: PsiBuilder) {
        val field = b.mark()
        val key = b.mark()
        b.advanceLexer()
        key.done(T.FIELD_KEY)
        if (b.tokenType == T.EQ) b.advanceLexer()
        when (b.tokenType) {
            T.TYPE -> block(b)
            T.LBRACKET -> list(b)
            in T.VALUES -> item(b)
        }
        rest(b)
        field.done(T.FIELD)
    }

    /**
     * `[a, b]`, over as many lines as it runs, or a list of blocks: the lexer starts lines inside the
     * second kind, the first word of them an item, and a `]` alone on its line ends it.
     */
    private fun list(b: PsiBuilder) {
        val list = b.mark()
        b.advanceLexer()
        if (b.tokenType !in T.LINE_STARTS) {
            while (!b.eof() && b.tokenType != T.RBRACKET && b.tokenType !in T.LINE_STARTS) {
                if (b.tokenType in T.VALUES) item(b) else b.advanceLexer()
            }
            if (b.tokenType == T.RBRACKET) b.advanceLexer()
            list.done(T.LIST)
            return
        }
        while (!b.eof()) {
            when (b.tokenType) {
                T.LIST_END -> {
                    line(b)
                    break
                }
                T.WORD -> block(b)
                // An End with the list still open closes what holds it: the list is left without its ].
                T.END -> break
                else -> badLine(b)
            }
        }
        list.done(T.BLOCK_LIST)
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

    /** Spaces and tabs before the code of the line the builder is on. */
    private fun indentAt(b: PsiBuilder): Int {
        val text = b.originalText
        var start = b.currentOffset
        while (start > 0 && text[start - 1] != '\n') start--
        var end = start
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        return end - start
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
        T.LIST, T.BLOCK_LIST -> DukeList(node)
        T.ITEM -> DukeValue(node)
        T.BAD_LINE_ELEMENT -> DukeBadLine(node)
        else -> throw IllegalArgumentException("Unknown element ${node.elementType}")
    }

    private companion object {
        val COMMENTS = TokenSet.create(T.COMMENT)
        val STRINGS = TokenSet.create(T.STRING)
    }
}
