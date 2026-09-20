package uz.duke.plugin.map

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/** What a view of the map asks of the editor around it: each change to the file, made by the editor's rules. */
internal interface MapHost {
    /** What the tool puts down, on an empty cell — or, where nothing can go there, why. */
    fun put(map: MapModel, x: Int, y: Int)

    fun move(map: MapModel, layer: Layer, thing: Thing, x: Int, y: Int)

    fun takeOff(map: MapModel, layer: Layer, thing: Thing)

    /** A thing clicked rather than dragged: what the next click puts down becomes one of it. */
    fun picked(layer: Layer, thing: Thing)

    fun say(text: String)
}

/**
 * The map from above, a square a cell: rock dark, floor lighter a storey up, any other cell a colour of its own; the
 * rooms outlined and numbered; and each thing on it, drawn by its list — the first list's things round, the next's
 * square — in its kind's colour, the ones a map has one of larger. A click puts down what the tool says, a drag
 * moves a thing, a right click takes one off, and a click on a thing makes it the tool.
 */
internal class MapCanvas(private val host: MapHost) : JComponent() {
    var model: MapModel? = null
        set(value) {
            field = value
            revalidate()
            repaint()
        }

    private var zoom = JBUI.scale(14)
    private var hover: Point? = null
    private var dragging: Pair<Layer, Thing>? = null
    private var pressedAt: Point? = null

    init {
        isOpaque = true
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = pressed(e)
            override fun mouseReleased(e: MouseEvent) = released(e)
            override fun mouseDragged(e: MouseEvent) = moved(e)
            override fun mouseMoved(e: MouseEvent) = moved(e)
            override fun mouseExited(e: MouseEvent) {
                hover = null
                repaint()
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                if (e.isControlDown) return zoomBy(-e.wheelRotation)
                // Only a zoom is the canvas's: a wheel otherwise scrolls the map, as it would with no listener here.
                SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this@MapCanvas)
                    ?.let { it.dispatchEvent(SwingUtilities.convertMouseEvent(this@MapCanvas, e, it)) }
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
    }

    fun zoomBy(steps: Int) {
        zoom = (zoom + steps * JBUI.scale(2)).coerceIn(JBUI.scale(4), JBUI.scale(40))
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val model = model ?: return Dimension(0, 0)
        return Dimension(model.width * zoom, model.height * zoom)
    }

    // ---- the hand ----

    private fun pressed(e: MouseEvent) {
        val model = model ?: return
        val cell = cellAt(e.point) ?: return
        val there = model.thingsAt(cell.x, cell.y).firstOrNull()
        if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger) {
            val (layer, thing) = there ?: return
            return host.takeOff(model, layer, thing)
        }
        if (!SwingUtilities.isLeftMouseButton(e)) return
        if (there != null) {
            dragging = there
            pressedAt = cell
            return
        }
        host.put(model, cell.x, cell.y)
    }

    private fun released(e: MouseEvent) {
        val (layer, thing) = dragging ?: return
        dragging = null
        val model = model ?: return
        val cell = cellAt(e.point)
        when (cell) {
            null -> Unit
            pressedAt -> host.picked(layer, thing)
            else -> host.move(model, layer, thing, cell.x, cell.y)
        }
        repaint()
    }

    private fun moved(e: MouseEvent) {
        val cell = cellAt(e.point)
        if (cell != hover) {
            hover = cell
            repaint()
        }
        host.say(cell?.let(::describe).orEmpty())
    }

    private fun cellAt(point: Point): Point? {
        val model = model ?: return null
        val x = point.x / zoom
        val y = point.y / zoom
        return if (point.x < 0 || point.y < 0 || x >= model.width || y >= model.height) null else Point(x, y)
    }

    /** A cell in words: where it is, what it is, what stands on it, and the room it is in. */
    private fun describe(cell: Point): String {
        val model = model ?: return ""
        val char = model.charAt(cell.x, cell.y)
        val ground = when {
            char == null || char in model.solid -> "rock"
            char.isDigit() -> "floor, storey $char"
            else -> "'$char'"
        }
        val things = model.thingsAt(cell.x, cell.y).joinToString { (layer, thing) -> thing.kind?.let { "$it (${layer.label})" } ?: layer.label }
        val room = model.areaAt(cell.x, cell.y)?.let { "room ${it.index}" }
        return listOfNotNull("${cell.x}, ${cell.y}", ground, things.ifEmpty { null }, room).joinToString("  ·  ")
    }

    // ---- drawing ----

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = BACKGROUND
            g2.fillRect(0, 0, width, height)
            val model = model ?: return
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            paintGround(g2, model)
            paintAreas(g2, model)
            paintThings(g2, model)
            hover?.let { cell ->
                val (layer, thing) = dragging ?: (null to null)
                if (layer != null && thing != null && cell != pressedAt) mark(g2, model, layer, thing.kind, cell.x, cell.y, ghost = true)
                g2.color = HOVER
                g2.fillRect(cell.x * zoom, cell.y * zoom, zoom, zoom)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun paintGround(g2: Graphics2D, model: MapModel) {
        val clip = g2.clipBounds ?: return
        val left = (clip.x / zoom).coerceAtLeast(0)
        val top = (clip.y / zoom).coerceAtLeast(0)
        val right = ((clip.x + clip.width) / zoom + 1).coerceAtMost(model.width)
        val bottom = ((clip.y + clip.height) / zoom + 1).coerceAtMost(model.height)
        for (y in top until bottom) {
            for (x in left until right) {
                g2.color = groundOf(model.charAt(x, y), model.solid)
                g2.fillRect(x * zoom, y * zoom, zoom, zoom)
            }
        }
        if (zoom < JBUI.scale(8)) return
        g2.color = LINES
        for (x in left..right) g2.drawLine(x * zoom, top * zoom, x * zoom, bottom * zoom)
        for (y in top..bottom) g2.drawLine(left * zoom, y * zoom, right * zoom, y * zoom)
    }

    private fun paintAreas(g2: Graphics2D, model: MapModel) {
        g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
        g2.font = JBUI.Fonts.smallFont()
        for (area in model.areas) {
            g2.color = AREA
            g2.drawRect(area.x * zoom, area.y * zoom, area.width * zoom, area.height * zoom)
            if (zoom >= JBUI.scale(11)) g2.drawString(area.index.toString(), area.x * zoom + JBUI.scale(3), area.y * zoom + JBUI.scale(12))
        }
    }

    private fun paintThings(g2: Graphics2D, model: MapModel) {
        // The lists first and the ones a map has one of over them: a boss is not hidden under the monster it stands on.
        for (layer in model.layers.sortedBy { it.single }) {
            for (thing in layer.things) {
                if (dragging?.second === thing) continue
                mark(g2, model, layer, thing.kind, thing.x, thing.y, ghost = false)
            }
        }
    }

    private fun mark(g2: Graphics2D, model: MapModel, layer: Layer, kind: String?, x: Int, y: Int, ghost: Boolean) {
        val colour = colourOf(layer, kind).let { if (ghost) Color(it.red, it.green, it.blue, 150) else it }
        val centreX = x * zoom + zoom / 2
        val centreY = y * zoom + zoom / 2
        if (layer.single) {
            val size = (zoom * 1.1f).toInt().coerceAtLeast(JBUI.scale(4))
            g2.color = colour
            g2.fillOval(centreX - size / 2, centreY - size / 2, size, size)
            g2.color = if (layer.kinded) RING else Color.WHITE
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
            val ring = (zoom * 1.4f).toInt()
            g2.drawOval(centreX - ring / 2, centreY - ring / 2, ring, ring)
            return
        }
        val size = (zoom * 0.72f).toInt().coerceAtLeast(JBUI.scale(3))
        g2.color = colour
        when (model.layers.filterNot { it.single }.indexOf(layer) % 3) {
            0 -> g2.fillOval(centreX - size / 2, centreY - size / 2, size, size)
            1 -> g2.fillRect(centreX - size / 2, centreY - size / 2, size, size)
            else -> g2.fillPolygon(intArrayOf(centreX, centreX + size / 2, centreX, centreX - size / 2),
                intArrayOf(centreY - size / 2, centreY, centreY + size / 2, centreY), 4)
        }
    }

    companion object {
        val BACKGROUND = Color(18, 18, 21)
        val ROCK = Color(28, 28, 32)
        val LINES = Color(44, 44, 50)
        val FLOOR = Color(72, 72, 82)
        val OTHER = Color(150, 130, 80)
        val AREA = Color(96, 104, 120)
        val RING = Color(255, 210, 90)
        val ENTRANCE = Color(90, 170, 255)
        val HOVER = Color(255, 255, 255, 70)

        /** Rock, or floor a shade lighter for every storey up, or — for anything else a cell may be — a colour of its own. */
        fun groundOf(char: Char?, solid: String): Color {
            if (char == null || char in solid) return ROCK
            if (!char.isDigit()) return OTHER
            val lift = minOf(char - '0', 4) * 22
            return Color(minOf(255, FLOOR.red + lift), minOf(255, FLOOR.green + lift), minOf(255, FLOOR.blue + lift))
        }

        /** A kind in the colour its block gives it, else one of its own from its name; a thing with no kind in blue. */
        fun colourOf(layer: Layer, kind: String?): Color {
            if (kind == null) return ENTRANCE
            layer.colours[kind]?.let { return Color(it) }
            return Color.getHSBColor((kind.hashCode() and 0xFFFF) / 65535f, 0.55f, 0.95f)
        }
    }
}
