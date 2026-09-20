package uz.dukeengine.plugin.assets

import com.intellij.openapi.vfs.VirtualFile
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * The animation clips a glTF model holds, by name: what a field such as `Attack = Melee_Attack`
 * may choose from. Only the JSON part of a `.glb` is read, never its buffers.
 */
object DukeClips {
    private class Read(val stamp: Long, val names: List<String>)

    private val cache = ConcurrentHashMap<String, Read>()

    fun namesIn(file: VirtualFile): List<String> {
        cache[file.path]?.takeIf { it.stamp == file.modificationStamp }?.let { return it.names }
        val names = runCatching {
            val json = when (file.extension?.lowercase()) {
                "gltf" -> String(file.contentsToByteArray(), Charsets.UTF_8)
                "glb" -> file.inputStream.use(::glbJson)
                else -> null
            }
            json?.let(::animationNames).orEmpty()
        }.getOrDefault(emptyList())
        cache[file.path] = Read(file.modificationStamp, names)
        return names
    }

    /** The JSON chunk of a binary glTF: a 12-byte header, then the chunk's length and type. */
    fun glbJson(input: InputStream): String? {
        val header = ByteBuffer.wrap(input.readNBytes(20)).order(ByteOrder.LITTLE_ENDIAN)
        if (header.limit() < 20 || header.getInt(0) != GLTF || header.getInt(16) != JSON) return null
        return String(input.readNBytes(header.getInt(12)), Charsets.UTF_8)
    }

    /** `animations[*].name`, read with a scanner rather than a JSON library the platform may not export. */
    fun animationNames(json: String): List<String> {
        val start = Regex("\"animations\"\\s*:\\s*\\[").find(json) ?: return emptyList()
        val names = mutableListOf<String>()
        var depth = 1 // inside the array; an animation's own keys are at depth 2
        var i = start.range.last + 1
        while (i < json.length && depth > 0) {
            when (json[i]) {
                '"' -> {
                    val end = stringEnd(json, i)
                    var after = end
                    while (after < json.length && json[after].isWhitespace()) after++
                    if (depth == 2 && json.substring(i + 1, end - 1) == "name" && after < json.length && json[after] == ':') {
                        var value = after + 1
                        while (value < json.length && json[value].isWhitespace()) value++
                        if (value < json.length && json[value] == '"') {
                            val valueEnd = stringEnd(json, value)
                            names += json.substring(value + 1, valueEnd - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                            i = valueEnd
                            continue
                        }
                    }
                    i = end
                    continue
                }
                '[', '{' -> depth++
                ']', '}' -> depth--
            }
            i++
        }
        return names
    }

    /** Just past the closing quote of the string opening at [from]. */
    private fun stringEnd(json: String, from: Int): Int {
        var i = from + 1
        while (i < json.length && json[i] != '"') i += if (json[i] == '\\') 2 else 1
        return minOf(i + 1, json.length)
    }

    private const val GLTF = 0x46546C67 // "glTF", little-endian
    private const val JSON = 0x4E4F534A // "JSON"
}
