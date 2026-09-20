package uz.duke.plugin.map

import com.intellij.psi.PsiClass
import uz.duke.plugin.assets.AssetKind
import uz.duke.plugin.assets.DukeAssets
import uz.duke.plugin.duke.DukeBlock
import uz.duke.plugin.duke.DukeLinks
import uz.duke.plugin.duke.DukeRecords
import uz.duke.plugin.inspector.Defaults
import uz.duke.plugin.preview.Json

/** A map as the 3D view draws it: the resource root its models are read from, and the rest of it as JSON. */
class MapScene(val root: String, val json: String)

/**
 * Everything the 3D view needs to draw a map as the game's client draws it: its cells and relief, how high a storey
 * stands, the kit its floor is laid from, the sun over it, and every thing standing on it with the model it is drawn
 * as.
 *
 * <p>The kit is read in the words the engine's client lays a floor in — a block with `TileSize` and `Tones`, each
 * tone a `Floor` and a `Wall` — and chosen as the game chooses it for a map fought at its `Difficulty`: the theme the
 * map list names for that depth, a tone drawn from the map's seed.
 */
object MapScenes {
    private const val LAYERED = "uz.duke.core.thing.Layered"

    /** The scene of [map], or null where its file has no resource root to draw models from. Under a read action. */
    fun of(map: MapModel): MapScene? {
        val block = map.block.element ?: return null
        val root = DukeAssets.rootOf(block)?.path ?: return null
        val named = DukeLinks.namedBlocks(block)
        val blocks = DukeLinks.everyBlock(block)
        val kits = blocks.filter { it.field("Tones") != null || recordHas(it, "tones", "tileSize") }
        val theme = kitFor(block, blocks, kits)
        val scene = mapOf(
            "cell" to 10,
            "levelHeight" to levelHeight(blocks),
            "cells" to map.rows,
            "solid" to map.solid,
            // A relief the file does not yet read right is drawn flat until it does: the text says what is wrong with it.
            "relief" to runCatching {
                map.relief?.map { row -> row.trim().split(' ').filter(String::isNotEmpty).map(String::toInt) }
            }.getOrNull(),
            "kit" to theme?.let { kitOf(it, toneFor(block, it.field("Tones")?.blocks.orEmpty())) },
            "sun" to blocks.firstOrNull { recordHas(it, "pitch", "yaw", "ambientTint") }?.let(::sunOf),
            "layers" to map.layers.map { layer ->
                mapOf("key" to layer.key, "single" to layer.single, "kinded" to layer.kinded,
                    "shape" to map.layers.filterNot { it.single }.indexOf(layer))
            },
            "things" to map.layers.flatMap { layer ->
                layer.things.map { thing ->
                    mapOf("layer" to layer.key, "kind" to thing.kind, "x" to thing.x, "y" to thing.y,
                        "colour" to (thing.kind?.let { layer.colours[it] }),
                        "look" to thing.kind?.takeIf { layer.kinded }?.let { lookOf(named, it, theme) })
                }
            },
        )
        return MapScene(root, Json.of(scene))
    }

    private fun name(block: DukeBlock) = block.field("Name")?.valueText ?: block.wordText

    private fun recordHas(block: DukeBlock, vararg components: String): Boolean {
        val record = DukeRecords.recordOf(block) ?: return false
        return components.all { name -> record.recordComponents.any { it.name == name } }
    }

    /** How high a storey stands: the `LevelHeight` of the block whose record is a `Layered` world. */
    private fun levelHeight(blocks: List<DukeBlock>): Float {
        val world = blocks.firstOrNull { block ->
            DukeRecords.recordOf(block)?.let { record -> record.interfaces.any { it.qualifiedName == LAYERED } } == true
        } ?: return 0f
        return number(world, "LevelHeight") ?: 0f
    }

    /** A number the block writes, or its record's default for it. */
    private fun number(block: DukeBlock, key: String): Float? =
        (block.field(key)?.valueText ?: defaultOf(block, key))?.let { text ->
            text.toFloatOrNull() ?: runCatching { java.lang.Long.decode(text).toFloat() }.getOrNull()
        }

    private fun text(block: DukeBlock, key: String): String? = block.field(key)?.valueText ?: defaultOf(block, key)?.takeIf { it != "none" }

    private fun yes(block: DukeBlock, key: String) = (block.field(key)?.valueText ?: defaultOf(block, key)) in setOf("Yes", "yes", "true")

    private fun defaultOf(block: DukeBlock, key: String): String? {
        val record: PsiClass = DukeRecords.recordOf(block) ?: return null
        return Defaults.of(record)[key.replaceFirstChar(Char::lowercaseChar)]
    }

    /**
     * The theme a map fought at its difficulty wears, as the game picks it: the one its map list names for that depth,
     * round again from the first or staying on the last as the list says.
     */
    private fun kitFor(map: DukeBlock, blocks: List<DukeBlock>, kits: List<DukeBlock>): DukeBlock? {
        val list = blocks.firstOrNull { it.field("Themes") != null }
        val order = list?.field("Themes")?.values?.map { it.unquoted }.orEmpty()
        if (order.isEmpty()) return kits.firstOrNull()
        val depth = maxOf(1, number(map, "Difficulty")?.toInt() ?: 1)
        val step = depth - 1
        val name = if (list?.field("WhenExhausted")?.valueText.equals("Last", ignoreCase = true)) order[minOf(step, order.size - 1)]
        else order[step % order.size]
        return kits.firstOrNull { name(it) == name } ?: kits.firstOrNull()
    }

    /** The tone the game draws for this map: a seeded draw over the theme's tones — the dungeon's `Themes.pick`. */
    private fun toneFor(map: DukeBlock, tones: List<DukeBlock>): DukeBlock? {
        if (tones.isEmpty()) return null
        val seed = map.field("Seed")?.valueText?.toLongOrNull() ?: 0L
        val depth = maxOf(1, number(map, "Difficulty")?.toInt() ?: 1)
        return tones[Math.floorMod(firstDraw(mix(seed, depth)), tones.size.toLong()).toInt()]
    }

    private fun mix(seed: Long, depth: Int): Long {
        var z = seed + depth * -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    private fun firstDraw(seed: Long): Long {
        var x = if (seed == 0L) -0x61c8864680b583ebL else seed
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        return x xor (x shl 17)
    }

    /** A theme and one of its tones, as the client's kit takes them. */
    private fun kitOf(theme: DukeBlock, tone: DukeBlock?): Map<String, Any?> {
        val tileSize = number(theme, "TileSize") ?: 4f
        return mapOf(
            "tileSize" to tileSize,
            "wallTileSize" to (number(theme, "WallTileSize")?.takeIf { it > 0f } ?: tileSize),
            "wallHeight" to (number(theme, "WallHeight") ?: 4f),
            "wallLift" to (number(theme, "WallLift") ?: 0f),
            "wallShift" to (number(theme, "WallShift") ?: 0f),
            "fillsRock" to yes(theme, "WallFillsRock"),
            "clump" to maxOf(1, number(theme, "WallClump")?.toInt() ?: 1),
            "spread" to (number(theme, "WallSpread") ?: 0f),
            "variety" to (number(theme, "WallVariety") ?: 0f),
            "stairs" to text(theme, "Stairs"),
            "rockFace" to text(theme, "RockFace"),
            "capTint" to (number(theme, "CapTint")?.toInt() ?: 0xFFFFFF),
            "storeyShade" to (number(theme, "StoreyShadePercent") ?: 100f) / 100f,
            "fog" to (number(theme, "FogTint")?.toInt() ?: 0),
            "floor" to tone?.let { text(it, "Floor") },
            "wall" to tone?.let { text(it, "Wall") },
            "corner" to tone?.let { text(it, "Corner") },
            "tint" to (tone?.let { number(it, "Tint")?.toInt() } ?: 0xFFFFFF),
        )
    }

    private fun sunOf(sun: DukeBlock): Map<String, Any?> = mapOf(
        "pitch" to (number(sun, "Pitch") ?: 40f),
        "yaw" to (number(sun, "Yaw") ?: 219f),
        "strength" to (number(sun, "StrengthPercent") ?: 100f),
        "ambient" to (number(sun, "AmbientPercent") ?: 55f),
        "colour" to (number(sun, "Colour")?.toInt() ?: 0xFFFFFF),
        "ambientTint" to (number(sun, "AmbientTint")?.toInt() ?: 0xFFFFFF),
    )

    /**
     * How a kind is drawn: its own block's model, or — for a kind with none, as a prop is — the one the theme dresses
     * that name in.
     */
    private fun lookOf(named: List<Pair<String, DukeBlock>>, kind: String, theme: DukeBlock?): Map<String, Any?>? {
        val own = named.firstOrNull { (name, block) -> name == kind && block.field("Model") != null }?.second
        val themed = theme?.field("Monsters")?.blocks.orEmpty().firstOrNull { name(it) == kind }
        val look = own ?: themed ?: return null
        val model = text(look, "Model")?.takeIf { AssetKind.of(it) == AssetKind.MODEL } ?: return null
        return mapOf(
            "model" to model,
            "texture" to text(look, "Texture")?.takeIf { AssetKind.of(it) == AssetKind.IMAGE },
            "scale" to (number(look, "ModelScale") ?: 1f),
            "facing" to (number(look, "Facing") ?: 90f),
            "tint" to (number(look, "Tint")?.toInt() ?: 0xFFFFFF),
            "held" to look.fields.flatMap { it.blocks }.mapNotNull { held ->
                val path = text(held, "Model")?.takeIf { AssetKind.of(it) == AssetKind.MODEL } ?: return@mapNotNull null
                val bone = held.field("Bone")?.valueText ?: return@mapNotNull null
                mapOf("model" to path, "bone" to bone, "scale" to (number(held, "Scale") ?: 1f),
                    "pitch" to (number(held, "Pitch") ?: 0f), "yaw" to (number(held, "Yaw") ?: 0f), "roll" to (number(held, "Roll") ?: 0f),
                    "x" to (number(held, "X") ?: 0f), "y" to (number(held, "Y") ?: 0f), "z" to (number(held, "Z") ?: 0f))
            },
        )
    }
}
