package uz.dukeengine.plugin.map

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiRecordComponent
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import uz.dukeengine.plugin.duke.DukeBlock
import uz.dukeengine.plugin.duke.DukeFile
import uz.dukeengine.plugin.duke.DukeLinks
import uz.dukeengine.plugin.duke.DukeRecords
import uz.dukeengine.plugin.inspector.DukeEdits
import uz.dukeengine.plugin.inspector.InspectorModels

/**
 * A map as its editor draws it: the rows of its grid, the relief over them, the areas cut out of it, and each thing
 * standing on it.
 */
class MapModel(
    val block: SmartPsiElementPointer<DukeBlock>,
    val title: String,
    /** The component its `@Grid` marks, and the one its `@Relief` does, as the file writes them. */
    val gridKey: String,
    val reliefKey: String?,
    val rows: List<String>,
    /** The relief's rows as the file writes them, a whole number a corner; null where it has none. */
    val relief: List<String>?,
    val solid: String,
    val areas: List<Area>,
    /** The areas as a layer of their own, for the one thing that moves them: the map being made bigger or smaller. */
    val areaLayer: Layer?,
    val layers: List<Layer>,
    /** The record's components in order, for where a new line goes. */
    val order: List<String>,
) {
    val width = rows.maxOfOrNull { it.length } ?: 0
    val height = rows.size

    fun charAt(x: Int, y: Int): Char? = rows.getOrNull(y)?.getOrNull(x)

    fun isSolid(x: Int, y: Int) = charAt(x, y)?.let { it in solid } ?: true

    /** What stands on a cell, the ones there alone first: a boss before the monster it was put on. */
    fun thingsAt(x: Int, y: Int): List<Pair<Layer, Thing>> =
        layers.sortedBy { !it.single }.flatMap { layer -> layer.things.filter { it.x == x && it.y == y }.map { layer to it } }

    fun areaAt(x: Int, y: Int): Area? = areas.firstOrNull { x >= it.x && x < it.x + it.width && y >= it.y && y < it.y + it.height }

    /**
     * What a map of this size, with its floor moved by this much, would leave outside itself — said rather than
     * cut, because a thing quietly dropped is a monster somebody placed this morning and will look for tonight.
     */
    fun outside(width: Int, height: Int, dx: Int, dy: Int): List<String> {
        val things = layers.flatMap { layer ->
            layer.things.filter { it.x + dx !in 0 until width || it.y + dy !in 0 until height }
                .map { "${it.kind ?: layer.label} at ${it.x}, ${it.y}" }
        }
        val rooms = areas.filter {
            it.x + dx < 0 || it.y + dy < 0 || it.x + dx + it.width > width || it.y + dy + it.height > height
        }.map { "room ${it.index}" }
        return things + rooms
    }
}

/** A rectangle of the map — a room — drawn with its place in its list, and not edited here. */
class Area(val index: Int, val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * One component of things on the map — `Monsters`, `Boss`, `Entrance` — each thing a record with an `x` and a `y`,
 * written on one line, `Skeleton 17 16`, or as numbers, `[9, 28]`. One that names what it is names it from [kinds],
 * the blocks its `@Link` names, each drawn in its own [colours] where its block gives one.
 */
class Layer(
    val key: String,
    val label: String,
    val single: Boolean,
    val kinds: List<String>?,
    val colours: Map<String, Int>,
    private val parts: List<String>,
    private val kindAt: Int,
    private val tuple: Boolean,
    val things: List<Thing>,
) {
    val kinded get() = kindAt >= 0

    /** A thing as its line writes it: [kind] and the cell in their places, every other part as it was, or 0. */
    fun write(kind: String?, x: Int, y: Int, was: List<String>?): String {
        val words = parts.mapIndexed { at, part ->
            when {
                at == kindAt -> kind ?: was?.getOrNull(at) ?: "?"
                part == "x" -> x.toString()
                part == "y" -> y.toString()
                else -> was?.getOrNull(at) ?: "0"
            }
        }
        return if (tuple) words.joinToString(", ", "[", "]") else words.joinToString(" ")
    }
}

/** One thing on the map: what it is, where, and its place in its list — -1 for a component that holds one. */
class Thing(val kind: String?, val x: Int, val y: Int, val index: Int, val words: List<String>)

object MapModels {
    private const val GRID = "uz.dukeengine.core.data.Grid"
    private const val RELIEF = "uz.dukeengine.core.data.Relief"

    /** The first block of [file] whose record has a component marked `@Grid`, with that component. */
    fun gridOf(file: DukeFile): Pair<DukeBlock, PsiRecordComponent>? {
        for (block in file.blocks) {
            val record = DukeRecords.recordOf(block) ?: continue
            record.recordComponents.firstOrNull { it.hasAnnotation(GRID) }?.let { return block to it }
        }
        return null
    }

    /** [file]'s map, or null when no block of it is one. Call under a read action. */
    fun build(file: DukeFile): MapModel? {
        val (block, grid) = gridOf(file) ?: return null
        val record = DukeRecords.recordOf(block) ?: return null
        val solid = DukeRecords.constantString(grid.getAnnotation(GRID)?.findAttributeValue("solid")) ?: "#"
        val rows = block.field(key(grid))?.values?.map { it.unquoted }.orEmpty()
        val areas = mutableListOf<Area>()
        val layers = mutableListOf<Layer>()
        var areaLayer: Layer? = null
        for (component in record.recordComponents) {
            if (component == grid) continue
            val shape = DukeRecords.blockClass(component.type)?.takeIf { it.isRecord } ?: continue
            val parts = shape.recordComponents.map { it.name }
            if ("x" !in parts || "y" !in parts) continue
            val field = block.field(key(component))
            val many = DukeRecords.isCollection(component.type)
            val tuple = !DukeRecords.hasFactory(shape) && InspectorModels.isTuple(shape)
            if (!tuple && !DukeRecords.hasFactory(shape)) continue
            // A line is its words; `[9, 28]` is the numbers of its list.
            val written: List<List<String>> = when {
                field == null -> emptyList()
                many -> field.values.map { words(it.unquoted) }
                tuple -> listOf(field.values.map { it.unquoted })
                else -> listOfNotNull(field.valueText?.let(::words))
            }
            if ("width" in parts && "height" in parts) {
                val corners = written.mapIndexed { index, words ->
                    fun part(name: String) = words.getOrNull(parts.indexOf(name))?.toIntOrNull() ?: 0
                    areas += Area(index, part("x"), part("y"), part("width"), part("height"))
                    Thing(null, part("x"), part("y"), if (many) index else -1, words)
                }
                // Not a layer anything is put down in — a room is the generator's — but one that can be moved,
                // which is what a map growing or shrinking does to every one of them.
                if (areaLayer == null) {
                    areaLayer = Layer(key(component), InspectorModels.humanize(key(component)), !many, null, emptyMap(),
                        parts, -1, tuple, corners)
                }
                continue
            }
            val kindAt = shape.recordComponents.indexOfFirst { InspectorModels.isText(it.type) }
            val link = DukeLinks.linkOf(component)
            val linked = link?.let { DukeLinks.blocksOf(it, block) }.orEmpty()
            val colours = linked.mapNotNull { (name, it) -> it.field("Colour")?.valueText?.let(::colourOf)?.let { colour -> name to colour } }.toMap()
            val things = written.mapIndexedNotNull { index, words ->
                val x = words.getOrNull(parts.indexOf("x"))?.toIntOrNull() ?: return@mapIndexedNotNull null
                val y = words.getOrNull(parts.indexOf("y"))?.toIntOrNull() ?: return@mapIndexedNotNull null
                Thing(if (kindAt >= 0) words.getOrNull(kindAt) else null, x, y, if (many) index else -1, words)
            }
            layers += Layer(key(component), InspectorModels.humanize(key(component)), !many, link?.let { linked.keys.sorted() }, colours,
                parts, kindAt, tuple, things)
        }
        val pointer = SmartPointerManager.getInstance(file.project).createSmartPsiElementPointer(block)
        val title = block.field("DisplayName")?.valueText ?: block.field("Name")?.valueText ?: block.wordText
        val relief = record.recordComponents.firstOrNull { it.hasAnnotation(RELIEF) }?.let(::key)
        val corners = relief?.let { block.field(it)?.values?.map { value -> value.unquoted } }
        return MapModel(pointer, title, key(grid), relief, rows, corners, solid, areas, areaLayer, layers,
            record.recordComponents.map(::key))
    }

    private fun key(component: PsiRecordComponent) = DukeRecords.capitalized(component.name)

    private fun words(text: String) = text.trim().split(Regex("\\s+"))

    private fun colourOf(text: String): Int? = runCatching { Integer.decode(text) }.getOrNull()
}

/**
 * What a hand on the map writes: a thing put down, moved or taken off, each as the lines of the map's file. A cell
 * holds one thing of the lists: whatever of theirs stood where something is put down is taken off it first.
 */
object MapEdits {
    fun place(project: Project, document: Document, map: MapModel, layer: Layer, kind: String?, x: Int, y: Int) {
        clear(project, document, map, x, y)
        val block = map.block.element ?: return
        val text = layer.write(kind, x, y, null)
        if (layer.single) DukeEdits.setValue(document, block, layer.key, map.order, text)
        else DukeEdits.addItem(document, block, layer.key, map.order, text)
    }

    fun move(document: Document, map: MapModel, layer: Layer, thing: Thing, x: Int, y: Int) {
        val block = map.block.element ?: return
        val text = layer.write(thing.kind, x, y, thing.words)
        if (layer.single) DukeEdits.setValue(document, block, layer.key, map.order, text)
        else DukeEdits.setItem(document, block, layer.key, thing.index, text)
    }

    fun remove(document: Document, map: MapModel, layer: Layer, thing: Thing) {
        val block = map.block.element ?: return
        if (layer.single) block.field(layer.key)?.let { DukeEdits.removeLines(document, it) }
        else DukeEdits.removeItem(document, block, layer.key, map.order, thing.index)
    }

    /** Cells of the grid, each at its `x` and `y`, made what it says: only the rows they fall in change in the file. */
    fun paint(document: Document, map: MapModel, cells: List<Triple<Int, Int, Char>>) {
        val block = map.block.element ?: return
        val rows = map.rows.toMutableList()
        for ((x, y, char) in cells) {
            val row = rows.getOrNull(y)?.takeIf { x in it.indices } ?: continue
            rows[y] = row.substring(0, x) + char + row.substring(x + 1)
        }
        DukeEdits.setRows(document, block, map.gridKey, map.order, rows)
    }

    /**
     * The map at another size: its cells and its relief rewritten, and everything standing on it moved with the
     * floor under it — [dx] and [dy] being where the old map's top-left corner lands in the new one.
     *
     * <p>What is grown is rock, because an editor that filled new ground with floor would be drawing the map for
     * its author. Nothing is cut: a thing that would fall outside is refused before this is called — see
     * [MapModel.outside] — so a monster placed this morning is never quietly dropped.
     */
    fun resize(project: Project, document: Document, map: MapModel, width: Int, height: Int, dx: Int, dy: Int) {
        val documents = PsiDocumentManager.getInstance(project)
        val rock = map.solid.firstOrNull() ?: '#'
        val rows = (0 until height).map { y ->
            val was = map.rows.getOrNull(y - dy)
            (0 until width).map { x -> was?.getOrNull(x - dx) ?: rock }.joinToString("")
        }
        map.block.element?.let { DukeEdits.setRows(document, it, map.gridKey, map.order, rows) } ?: return
        documents.commitDocument(document)
        val relief = map.relief
        if (relief != null && map.reliefKey != null) {
            // One more row and one more number a row than there are cells: the corners, not the cells.
            val corners = relief.map { it.trim().split(' ').filter(String::isNotEmpty) }
            val moved = (0..height).map { y ->
                val was = corners.getOrNull(y - dy)
                (0..width).joinToString(" ") { x -> was?.getOrNull(x - dx) ?: "0" }
            }
            map.block.element?.let { DukeEdits.setRows(document, it, map.reliefKey, map.order, moved) }
            documents.commitDocument(document)
        }
        if (dx == 0 && dy == 0) {
            return // the floor did not move, so nothing standing on it did either
        }
        for (layer in map.layers + listOfNotNull(map.areaLayer)) {
            for (thing in layer.things) {
                val block = map.block.element ?: return
                val text = layer.write(thing.kind, thing.x + dx, thing.y + dy, thing.words)
                if (layer.single) DukeEdits.setValue(document, block, layer.key, map.order, text)
                else DukeEdits.setItem(document, block, layer.key, thing.index, text)
            }
            documents.commitDocument(document)
        }
    }

    /** The relief as [rows], each a line of whole numbers, a corner each: written where the file keeps it, or added. */
    fun shape(document: Document, map: MapModel, rows: List<String>) {
        val block = map.block.element ?: return
        DukeEdits.setRows(document, block, map.reliefKey ?: return, map.order, rows)
    }

    /** The things of the lists on a cell taken off it, the last of each list first so the ones before keep their place. */
    private fun clear(project: Project, document: Document, map: MapModel, x: Int, y: Int) {
        val documents = PsiDocumentManager.getInstance(project)
        for (layer in map.layers.filterNot { it.single }) {
            for (thing in layer.things.filter { it.x == x && it.y == y }.sortedByDescending { it.index }) {
                val block = map.block.element ?: return
                DukeEdits.removeItem(document, block, layer.key, map.order, thing.index)
                documents.commitDocument(document)
            }
        }
    }
}
