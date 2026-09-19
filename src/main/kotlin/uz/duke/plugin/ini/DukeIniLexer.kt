package uz.duke.plugin.ini

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class DukeIniElementType(debugName: String) : IElementType(debugName, DukeIniLanguage)

object DukeIniTypes {
    @JvmField val FILE = IFileElementType(DukeIniLanguage)

    // Tokens. The first token of a line says what the line is; the lexer decides it.
    @JvmField val BLOCK_TYPE = DukeIniElementType("BLOCK_TYPE") // `Object` in `Object Rogue`
    @JvmField val KEY = DukeIniElementType("KEY")
    @JvmField val SECTION_KEY = DukeIniElementType("SECTION_KEY") // `Generation` in `Generation = Layout`, a section inside a block
    @JvmField val END = DukeIniElementType("END")
    @JvmField val BAD = DukeIniElementType("BAD") // starts a line that fits nothing
    @JvmField val NAME = DukeIniElementType("NAME")
    @JvmField val VALUE = DukeIniElementType("VALUE")
    @JvmField val NUMBER = DukeIniElementType("NUMBER")
    @JvmField val STRING = DukeIniElementType("STRING")
    @JvmField val EQ = DukeIniElementType("EQ")
    @JvmField val COMMENT = DukeIniElementType("COMMENT")

    // Elements.
    @JvmField val BLOCK_ELEMENT = DukeIniElementType("BLOCK")
    @JvmField val HEADER_ELEMENT = DukeIniElementType("HEADER")
    @JvmField val SECTION_ELEMENT = DukeIniElementType("SECTION")
    @JvmField val FIELD_ELEMENT = DukeIniElementType("FIELD")
    @JvmField val NAME_ELEMENT = DukeIniElementType("NAME_REF")
    @JvmField val VALUE_ELEMENT = DukeIniElementType("VALUE_REF")
    @JvmField val BAD_LINE_ELEMENT = DukeIniElementType("BAD_LINE")

    @JvmField val LINE_STARTS = TokenSet.create(BLOCK_TYPE, KEY, SECTION_KEY, END, BAD)
}

/**
 * Tokenises the way `uz.duke.core.ini.Ini` does — separators are space, tab and `=`,
 * `;` comments to the end of the line, control characters count as space — and
 * tracks block depth so each line's first token can say what the line is.
 *
 * The state packs depth and where on the line we are; depth 0 at a line start is
 * state 0, the only point the editor restarts from.
 */
class DukeIniLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    private var tokenState = 0
    private var depth = 0
    private var role = LINE_START

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        tokenEnd = startOffset
        role = initialState and 0x7
        depth = initialState shr 4
        advance()
    }

    override fun getState() = tokenState
    override fun getTokenType() = tokenType
    override fun getTokenStart() = tokenStart
    override fun getTokenEnd() = tokenEnd
    override fun getBufferSequence() = buffer
    override fun getBufferEnd() = bufferEnd

    override fun advance() {
        tokenState = role or (depth shl 4)
        tokenStart = tokenEnd
        if (tokenStart >= bufferEnd) {
            tokenType = null
            return
        }
        val c = buffer[tokenStart]
        when {
            c == ';' -> {
                tokenEnd = skip(tokenStart) { it != '\n' }
                tokenType = DukeIniTypes.COMMENT
            }
            isSpace(c) -> {
                tokenEnd = skip(tokenStart) { isSpace(it) }
                if ((tokenStart until tokenEnd).any { buffer[it] == '\n' }) role = LINE_START
                tokenType = TokenType.WHITE_SPACE
            }
            c == '=' -> {
                tokenEnd = tokenStart + 1
                tokenType = DukeIniTypes.EQ
            }
            else -> {
                tokenEnd = if (c == '"') stringEnd() else skip(tokenStart) { !isSeparator(it) }
                tokenType = if (role == LINE_START) lineStart() else midLine(c == '"')
            }
        }
    }

    private fun lineStart(): IElementType {
        val word = buffer.subSequence(tokenStart, tokenEnd).toString()
        var hasEq = false
        var moreTokens = false
        var i = tokenEnd
        while (i < bufferEnd && buffer[i] != '\n' && buffer[i] != ';') {
            if (buffer[i] == '=') hasEq = true else if (!isSpace(buffer[i])) moreTokens = true
            i++
        }
        role = OTHER
        if (word.equals("End", ignoreCase = true)) {
            if (depth > 0) depth--
            return DukeIniTypes.END
        }
        val identifier = IDENTIFIER.matches(word)
        // Engine files write every field with `=`, so an `=`-less `Word Name` inside a block
        // is the next block's header and the one before it lacks its End.
        // ponytail: misreads a field written as `Key value`; the engine accepts that, none of our files do.
        val header = identifier && !hasEq && (depth == 0 || (moreTokens && word[0].isUpperCase()))
        return when {
            header -> {
                depth = 1
                role = HEADER
                DukeIniTypes.BLOCK_TYPE
            }
            depth == 0 || !identifier -> DukeIniTypes.BAD
            hasEq && opensSection() -> {
                depth++
                role = HEADER
                DukeIniTypes.SECTION_KEY
            }
            else -> DukeIniTypes.KEY
        }
    }

    /**
     * Whether `Generation = Layout` opens a section of its own rather than being a field: named with
     * plain words, and followed by lines indented under it, or by its End at its own indent. Which keys
     * open one is the game's code, which the lexer cannot see, so the layout is what it goes by.
     */
    // ponytail: told by indentation alone; a field whose next line is indented deeper by mistake reads as a section.
    private fun opensSection(): Boolean {
        var i = tokenEnd
        var names = 0
        while (i < bufferEnd && buffer[i] != '\n' && buffer[i] != ';') {
            if (isSeparator(buffer[i])) {
                i++
                continue
            }
            val start = i
            i = skip(i) { !isSeparator(it) }
            if (!IDENTIFIER.matches(buffer.subSequence(start, i))) return false
            names++
        }
        if (names == 0) return false
        var lineStart = tokenStart
        while (lineStart > 0 && buffer[lineStart - 1] != '\n') lineStart--
        val indent = tokenStart - lineStart
        while (i < bufferEnd) {
            val next = if (buffer[i] == '\n') i + 1 else skip(i) { it != '\n' } + 1
            val first = skip(next) { it == ' ' || it == '\t' }
            i = first
            if (first >= bufferEnd || buffer[first] == '\n' || buffer[first] == '\r' || buffer[first] == ';') continue
            val word = buffer.subSequence(first, skip(first) { !isSeparator(it) })
            val nextIndent = first - next
            return nextIndent > indent || (indent > 0 && nextIndent == indent && word.toString().equals("End", ignoreCase = true))
        }
        return false
    }

    private fun midLine(quoted: Boolean): IElementType = when {
        quoted -> DukeIniTypes.STRING
        role == HEADER -> DukeIniTypes.NAME
        NUMBER.matches(buffer.subSequence(tokenStart, tokenEnd)) -> DukeIniTypes.NUMBER
        else -> DukeIniTypes.VALUE
    }

    private fun stringEnd(): Int {
        val end = skip(tokenStart + 1) { it != '"' && it != '\n' && it != ';' }
        return if (end < bufferEnd && buffer[end] == '"') end + 1 else end
    }

    private inline fun skip(from: Int, keep: (Char) -> Boolean): Int {
        var i = from
        while (i < bufferEnd && keep(buffer[i])) i++
        return i
    }

    companion object {
        private const val LINE_START = 0
        private const val HEADER = 1
        private const val OTHER = 2

        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val NUMBER = Regex("[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?%?|0[xX][0-9a-fA-F]+")

        private fun isSpace(c: Char) = c == ' ' || c.code < 32
        private fun isSeparator(c: Char) = isSpace(c) || c == '=' || c == ';'
    }
}
