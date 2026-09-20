package uz.duke.plugin.map

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import uz.duke.plugin.DukeBundle
import uz.duke.plugin.duke.DukeBlock
import uz.duke.plugin.duke.DukeField
import uz.duke.plugin.duke.DukeFile

/**
 * What a map is refused for, said on the line that says it rather than when the game is started.
 *
 * <p>An engine reading a map checks it before it lays it: a monster inside a wall or a way in off the edge stops
 * the game with a list rather than starting. The list is the right thing for a game to do and the wrong place to
 * read it — by then the editor is closed, and the cell the message names has to be counted along a row by hand.
 * So the same reading is done here, where the map is drawn.
 *
 * <p>Only what any map means is checked. A cell the `@Grid` calls solid is stone in every game; a cell outside the
 * rows is nowhere in every game. Whether two things may share a cell is a rule a game makes, so it is a warning
 * rather than an error — the editor puts down one thing to a cell, and a file that holds two was written by hand
 * or by a generator that meant it.
 */
class DukeMapAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is DukeBlock) return
        val file = element.containingFile as? DukeFile ?: return
        if (MapModels.gridOf(file)?.first != element) return
        val map = MapModels.build(file) ?: return
        rows(element, map, holder)
        relief(element, map, holder)
        things(element, map, holder)
    }

    /** Rows of different widths: a short one reads as stone to its right, which is a map that plays almost right. */
    private fun rows(block: DukeBlock, map: MapModel, holder: AnnotationHolder) {
        val field = block.field(map.gridKey) ?: return
        val first = map.rows.firstOrNull()?.length ?: return
        map.rows.forEachIndexed { y, row ->
            if (row.length == first) return@forEachIndexed
            val at = field.values.getOrNull(y) ?: field.keyElement
            holder.error(at, "duke.map.ragged", map.gridKey, y, row.length, first)
        }
    }

    /** The ground is every corner of every cell, so it has one more row, and one more number in a row, than the cells. */
    private fun relief(block: DukeBlock, map: MapModel, holder: AnnotationHolder) {
        val key = map.reliefKey ?: return
        val corners = map.relief?.takeIf { it.isNotEmpty() } ?: return
        val across = corners.first().trim().split(Regex("\\s+")).size
        if (corners.size == map.height + 1 && across == map.width + 1) return
        val field = block.field(key) ?: return
        holder.error(field.keyElement, "duke.map.relief.size", key, map.width, map.height,
            map.width + 1, map.height + 1, across, corners.size)
    }

    private fun things(block: DukeBlock, map: MapModel, holder: AnnotationHolder) {
        val taken = mutableMapOf<Pair<Int, Int>, String>()
        for (layer in map.layers) {
            val field = block.field(layer.key) ?: continue
            for (thing in layer.things) {
                val at = at(field, thing.index)
                val what = thing.kind ?: layer.label
                if (thing.x !in 0 until map.width || thing.y !in 0 until map.height) {
                    holder.error(at, "duke.map.off", what, thing.x, thing.y, map.width, map.height)
                    continue
                }
                if (map.isSolid(thing.x, thing.y)) {
                    holder.error(at, "duke.map.stone", what, thing.x, thing.y)
                }
                val already = taken.putIfAbsent(thing.x to thing.y, what)
                if (already != null) {
                    holder.newAnnotation(HighlightSeverity.WARNING,
                        DukeBundle.message("duke.map.taken", what, thing.x, thing.y, already)).range(at).create()
                }
            }
        }
    }

    /** Where a thing is written: its item of the list, or the whole of a field that holds one. */
    private fun at(field: DukeField, index: Int): PsiElement =
        if (index >= 0) field.values.getOrNull(index) ?: field.keyElement
        else field.list ?: field.value ?: field.keyElement

    private fun AnnotationHolder.error(at: PsiElement, key: String, vararg params: Any) =
        newAnnotation(HighlightSeverity.ERROR, DukeBundle.message(key, *params)).range(at).create()
}
