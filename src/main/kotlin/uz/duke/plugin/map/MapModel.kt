package uz.duke.plugin.map

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiRecordComponent
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import uz.duke.plugin.duke.DukeBlock
import uz.duke.plugin.duke.DukeFile
import uz.duke.plugin.duke.DukeLinks
import uz.duke.plugin.duke.DukeRecords
import uz.duke.plugin.inspector.DukeEdits
import uz.duke.plugin.inspector.InspectorModels

/** A map as its editor draws it: the rows of its grid, the areas cut out of it, and each thing standing on it. */
class MapModel(
    val block: SmartPsiElementPointer<DukeBlock>,
    val title: String,
    val rows: List<String>,
    val solid: String,
    val areas: List<Area>,
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
    private const val GRID = "uz.duke.core.data.Grid"

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
                written.forEachIndexed { index, words ->
                    fun part(name: String) = words.getOrNull(parts.indexOf(name))?.toIntOrNull() ?: 0
                    areas += Area(index, part("x"), part("y"), part("width"), part("height"))
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
        return MapModel(pointer, title, rows, solid, areas, layers, record.recordComponents.map(::key))
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
