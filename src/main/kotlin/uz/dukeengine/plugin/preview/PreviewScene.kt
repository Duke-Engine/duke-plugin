package uz.dukeengine.plugin.preview

import uz.dukeengine.plugin.assets.AssetKind
import uz.dukeengine.plugin.assets.DukeAssets
import uz.dukeengine.plugin.duke.DukeBlock
import uz.dukeengine.plugin.duke.DukeLinks
import uz.dukeengine.plugin.inspector.FieldRow
import uz.dukeengine.plugin.inspector.InspectorModel
import uz.dukeengine.plugin.inspector.InspectorModels
import uz.dukeengine.plugin.inspector.ValueEditor

/**
 * What the preview draws and plays for one block, every path whole from [root]: the model with what the engine's
 * client dresses a unit in — a `Texture` over it, a `Tint`, a model carried on a `Bone` — the clip each of its clip
 * fields names, the files those clips are in, and the sounds that are its.
 */
class PreviewScene(
    val root: String,
    val model: String?,
    val texture: String?,
    val tint: Int?,
    val held: List<Carried>,
    val libraries: List<String>,
    val actions: List<Action>,
    val sounds: List<Sound>,
) {
    data class Carried(
        val model: String, val bone: String, val scale: Float, val pitch: Float, val yaw: Float, val roll: Float,
        val x: Float, val y: Float, val z: Float,
    )

    /** A clip field and the clip it plays: `Walk` and `Walking_A`, written or taken from what the block links. */
    data class Action(val label: String, val clip: String)

    /** A button's worth of sound: each press the next of its [files]. */
    data class Sound(val label: String, val files: List<String>, val gain: Float)

    fun json(): String = Json.of(mapOf(
        "model" to model,
        "texture" to texture,
        "tint" to tint?.let { "#%06X".format(it and 0xFFFFFF) },
        "held" to held.map {
            mapOf("model" to it.model, "bone" to it.bone, "scale" to it.scale, "pitch" to it.pitch, "yaw" to it.yaw, "roll" to it.roll,
                "x" to it.x, "y" to it.y, "z" to it.z)
        },
        "libraries" to libraries,
        "actions" to actions.map { mapOf("label" to it.label, "clip" to it.clip) },
        "sounds" to sounds.map { mapOf("label" to it.label, "files" to it.files, "gain" to it.gain) },
    ))
}

object PreviewScenes {
    /** The scene of [model]'s block, or null when it has nothing to draw or to play. Call under a read action. */
    fun of(model: InspectorModel): PreviewScene? {
        val block = model.block?.element ?: return null
        val root = DukeAssets.rootOf(block)?.path ?: return null
        val drawn = pathIn(block, "Model", AssetKind.MODEL)
        val sounds = soundsOf(block)
        if (drawn == null && sounds.isEmpty()) return null
        val actions = model.groups.flatMap { it.rows }.filterIsInstance<FieldRow>().filter { it.depth == 0 }.mapNotNull { row ->
            val clip = row.editor as? ValueEditor.Clip ?: return@mapNotNull null
            (row.written ?: clip.inherited)?.let { PreviewScene.Action(row.label, it) }
        }
        return PreviewScene(
            root, drawn, pathIn(block, "Texture", AssetKind.IMAGE), block.field("Tint")?.valueText?.let(::colourOf),
            if (drawn == null) emptyList() else carried(block),
            if (drawn == null) emptyList() else DukeLinks.clipFiles(block).map { it.first }.filter { it != drawn },
            actions, sounds,
        )
    }

    /** The models [block] hangs on its bones: a block inside it, alone or in a list, with a `Model` and a `Bone`. */
    private fun carried(block: DukeBlock): List<PreviewScene.Carried> =
        block.fields.flatMap { it.blocks }.mapNotNull { held ->
            val path = pathIn(held, "Model", AssetKind.MODEL) ?: return@mapNotNull null
            val bone = held.field("Bone")?.valueText ?: return@mapNotNull null
            fun number(key: String, default: Float) = held.field(key)?.valueText?.toFloatOrNull() ?: default
            PreviewScene.Carried(path, bone, number("Scale", 1f), number("Pitch", 0f), number("Yaw", 0f), number("Roll", 0f),
                number("X", 0f), number("Y", 0f), number("Z", 0f))
        }

    /**
     * A block's own sound files; else the sounds named for it, as the client raises them — `died.Skeleton` is played
     * when the Skeleton dies — each under the moment it is for.
     */
    private fun soundsOf(block: DukeBlock): List<PreviewScene.Sound> {
        val name = block.field("Name")?.valueText
        val own = audioIn(block)
        if (own.isNotEmpty()) return listOf(PreviewScene.Sound(name ?: block.wordText, own, gainOf(block)))
        if (name == null) return emptyList()
        return DukeLinks.namedBlocks(block).filter { it.first.endsWith(".$name") }.sortedBy { it.first }.mapNotNull { (sound, other) ->
            audioIn(other).takeIf { it.isNotEmpty() }?.let { PreviewScene.Sound(InspectorModels.humanize(sound.removeSuffix(".$name")), it, gainOf(other)) }
        }
    }

    private fun audioIn(block: DukeBlock) = block.fields.flatMap { it.values }.map { it.unquoted }.filter { AssetKind.of(it) == AssetKind.AUDIO }

    private fun gainOf(block: DukeBlock) = block.field("Gain")?.valueText?.toFloatOrNull() ?: 1f

    private fun pathIn(block: DukeBlock, key: String, kind: AssetKind) = block.field(key)?.valueText?.takeIf { AssetKind.of(it) == kind }

    private fun colourOf(text: String): Int? = runCatching { Integer.decode(text) }.getOrNull()
}

/** Enough JSON for the page: maps, lists, text, numbers, yes/no and nothing. */
internal object Json {
    fun of(value: Any?): String = when (value) {
        null -> "null"
        is String -> string(value)
        is Boolean, is Int -> value.toString()
        is Number -> value.toDouble().takeIf { it.isFinite() }?.toString() ?: "0"
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { string(it.key.toString()) + ":" + of(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { of(it) }
        else -> string(value.toString())
    }

    /** Text as a JSON string, which is also a JavaScript one: the two line separators JSON allows raw are escaped. */
    fun string(text: String): String = buildString {
        append('"')
        for (char in text) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char < ' ' || char == '\u2028' || char == '\u2029' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }
}
