package uz.duke.plugin.duke

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class DukeElementType(debugName: String) : IElementType(debugName, DukeLanguage)

object DukeTypes {
    @JvmField val FILE = IFileElementType(DukeLanguage)

    // Tokens. What starts a line says what the line is; the lexer decides it.
    @JvmField val WORD = DukeElementType("WORD") // `Monster`: a line that is one word opens a block
    @JvmField val END = DukeElementType("END")
    @JvmField val KEY = DukeElementType("KEY")
    @JvmField val BAD_LINE = DukeElementType("BAD_LINE") // starts a line that fits nothing
    @JvmField val EQ = DukeElementType("EQ")
    @JvmField val VALUE = DukeElementType("VALUE")
    @JvmField val NUMBER = DukeElementType("NUMBER")
    @JvmField val STRING = DukeElementType("STRING")
    @JvmField val LBRACKET = DukeElementType("LBRACKET")
    @JvmField val RBRACKET = DukeElementType("RBRACKET")
    @JvmField val COMMA = DukeElementType("COMMA")
    @JvmField val BAD = DukeElementType("BAD") // what follows a value on its line, or a list inside a list
    @JvmField val COMMENT = DukeElementType("COMMENT")

    // Elements.
    @JvmField val BLOCK = DukeElementType("BLOCK")
    @JvmField val BLOCK_WORD = DukeElementType("BLOCK_WORD")
    @JvmField val FIELD = DukeElementType("FIELD")
    @JvmField val FIELD_KEY = DukeElementType("FIELD_KEY")
    @JvmField val LIST = DukeElementType("LIST")
    @JvmField val ITEM = DukeElementType("ITEM") // a value, alone or in a list
    @JvmField val BAD_LINE_ELEMENT = DukeElementType("BAD_LINE_ELEMENT")

    @JvmField val LINE_STARTS = TokenSet.create(WORD, END, KEY, BAD_LINE)
    @JvmField val VALUES = TokenSet.create(VALUE, NUMBER, STRING)
}

/**
 * Tokenises the way `uz.duke.core.data.DukeText` reads: a line that is one word opens a block (or is
 * `End`), `Key = value` is a field, `;` starts a comment outside quotes, and a `[` list runs on over
 * as many lines as it takes to reach its `]`.
 *
 * The state is where on the line the lexer is, a list counting as one place however many lines it
 * spans; a line start outside a list is state 0, the only point the editor restarts from.
 */
class DukeLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    private var tokenState = 0
    private var role = LINE_START

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        tokenEnd = startOffset
        role = initialState
        advance()
    }

    override fun getState() = tokenState
    override fun getTokenType() = tokenType
    override fun getTokenStart() = tokenStart
    override fun getTokenEnd() = tokenEnd
    override fun getBufferSequence() = buffer
    override fun getBufferEnd() = bufferEnd

    override fun advance() {
        tokenState = role
        tokenStart = tokenEnd
        if (tokenStart >= bufferEnd) {
            tokenType = null
            return
        }
        val c = buffer[tokenStart]
        tokenType = when {
            c == ';' -> {
                tokenEnd = skip(tokenStart) { it != '\n' }
                DukeTypes.COMMENT
            }
            isSpace(c) -> {
                tokenEnd = skip(tokenStart, ::isSpace)
                if (role != IN_LIST && (tokenStart until tokenEnd).any { buffer[it] == '\n' }) role = LINE_START
                TokenType.WHITE_SPACE
            }
            role == LINE_START -> lineStart()
            role == AFTER_KEY && c == '=' -> one(DukeTypes.EQ, AFTER_EQ)
            role == AFTER_EQ -> when (c) {
                '[' -> one(DukeTypes.LBRACKET, IN_LIST)
                '"' -> string(AFTER_VALUE)
                else -> scalar(inList = false)
            }
            role == IN_LIST -> when (c) {
                ']' -> one(DukeTypes.RBRACKET, AFTER_VALUE)
                ',' -> one(DukeTypes.COMMA, IN_LIST)
                '"' -> string(IN_LIST)
                '[' -> one(DukeTypes.BAD, IN_LIST)
                else -> scalar(inList = true)
            }
            else -> rest(DukeTypes.BAD)
        }
    }

    /** A line's first token: one word alone, a key before `=`, or a line that fits nothing. */
    private fun lineStart(): IElementType {
        if (isWordStart(buffer[tokenStart])) {
            val wordEnd = skip(tokenStart + 1, ::isWordPart)
            val after = skip(wordEnd) { it != '\n' && isSpace(it) }
            if (after >= bufferEnd || buffer[after] == '\n' || buffer[after] == ';') {
                tokenEnd = wordEnd
                role = AFTER_VALUE
                val word = buffer.subSequence(tokenStart, wordEnd).toString()
                return if (word.equals("End", ignoreCase = true)) DukeTypes.END else DukeTypes.WORD
            }
            if (buffer[after] == '=') {
                tokenEnd = wordEnd
                role = AFTER_KEY
                return DukeTypes.KEY
            }
        }
        return rest(DukeTypes.BAD_LINE)
    }

    /** The rest of the line's text, up to its comment, as one token. */
    private fun rest(type: IElementType): IElementType {
        tokenEnd = trimmed(codeEnd(tokenStart))
        role = AFTER_VALUE
        return type
    }

    private fun one(type: IElementType, next: Int): IElementType {
        tokenEnd = tokenStart + 1
        role = next
        return type
    }

    private fun string(next: Int): IElementType {
        tokenEnd = quoteEnd(tokenStart)
        role = next
        return DukeTypes.STRING
    }

    /** A value as written, spaces inside it included; in a list, one item, up to its comma. */
    private fun scalar(inList: Boolean): IElementType {
        val end = skip(tokenStart) { it != '\n' && it != ';' && !(inList && (it == ',' || it == ']')) }
        tokenEnd = trimmed(end)
        role = if (inList) IN_LIST else AFTER_VALUE
        return if (NUMBER.matches(buffer.subSequence(tokenStart, tokenEnd))) DukeTypes.NUMBER else DukeTypes.VALUE
    }

    /** Where the line's code ends: at its `;` comment, a `;` inside quotes being text, or at its end. */
    private fun codeEnd(from: Int): Int {
        var i = from
        while (i < bufferEnd && buffer[i] != '\n' && buffer[i] != ';') {
            i = if (buffer[i] == '"') quoteEnd(i) else i + 1
        }
        return i
    }

    /** Just past the quote that closes the one at [start], or the end of its line if none does. */
    private fun quoteEnd(start: Int): Int {
        var i = start + 1
        while (i < bufferEnd && buffer[i] != '\n') {
            when (buffer[i]) {
                '\\' -> i++
                '"' -> return i + 1
            }
            i++
        }
        return minOf(i, bufferEnd)
    }

    /** [end], moved back over trailing space, but never behind the token's start. */
    private fun trimmed(end: Int): Int {
        var i = end
        while (i > tokenStart + 1 && isSpace(buffer[i - 1])) i--
        return i
    }

    private inline fun skip(from: Int, keep: (Char) -> Boolean): Int {
        var i = from
        while (i < bufferEnd && keep(buffer[i])) i++
        return i
    }

    companion object {
        private const val LINE_START = 0
        private const val AFTER_KEY = 1
        private const val AFTER_EQ = 2
        private const val IN_LIST = 3
        private const val AFTER_VALUE = 4

        private val NUMBER = Regex("[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?[fFdD]?|0[xX][0-9a-fA-F]+")

        private fun isSpace(c: Char) = c == ' ' || c.code < 32
        private fun isWordStart(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c == '_'
        private fun isWordPart(c: Char) = isWordStart(c) || c in '0'..'9'
    }
}
