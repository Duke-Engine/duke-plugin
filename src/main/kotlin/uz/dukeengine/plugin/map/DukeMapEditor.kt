package uz.dukeengine.plugin.map

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import uz.dukeengine.plugin.duke.DukeFile
import uz.dukeengine.plugin.duke.DukeFileType
import uz.dukeengine.plugin.inspector.DukeEdits
import uz.dukeengine.plugin.play.DukePlay
import uz.dukeengine.plugin.preview.DukeBrowser
import uz.dukeengine.plugin.preview.Json
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.beans.PropertyChangeListener
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

private val LOG = logger<DukeMapEditor>()

/** A **Map** tab beside the text of a `.duke` file whose block has a `@Grid`. */
class DukeMapEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (file.fileType != DukeFileType || DumbService.isDumb(project)) return false
        val psi = PsiManager.getInstance(project).findFile(file) as? DukeFile ?: return false
        return MapModels.gridOf(psi) != null
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = DukeMapEditor(project, file)

    override fun getEditorTypeId() = "duke-map"

    override fun getPolicy() = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}

/** What the left button does in the 3D view, and the word the page knows it by. */
private enum class Hand(val label: String, val mode: String) {
    PUT("Put down", "place"),
    TAKE("Take off", "erase"),
    RAISE("Raise ground", "raise"),
    LOWER("Lower ground", "lower"),
    SMOOTH("Smooth ground", "smooth"),
    FLATTEN("Flatten ground", "flatten"),
    FLOOR("Paint floor", "floor"),
    STONE("Paint rock", "stone"),
    STAIR("Paint stair", "stair");

    val brush get() = this in setOf(RAISE, LOWER, SMOOTH, FLATTEN)
}

/**
 * The map of a file, drawn from its text and written back into it: each thing put down, moved or taken off, each
 * cell painted and each stroke over the ground is lines of the file changed, one undoable command, and the map is read
 * from the file again after it — the text stays the truth, and the Text tab beside this one shows every change as it
 * is made.
 *
 * <p>Drawn in 3D as the game draws it where the IDE has its browser; from above, a square a cell, where it has not.
 */
class DukeMapEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor, MapHost {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val page = if (JBCefApp.isSupported()) DukeBrowser(this, "viewer/map.html", ::act) else null
    private val canvas = if (page == null) MapCanvas(this) else null
    private val hands = ComboBox(Hand.entries.toTypedArray())
    private val layers = ComboBox<Layer>()
    private val kinds = ComboBox<String>()
    private val size = JSpinner(SpinnerNumberModel(2, 1, 12, 1))
    private val strength = JSpinner(SpinnerNumberModel(2, 1, 8, 1))
    private val storey = JSpinner(SpinnerNumberModel(0, 0, 9, 1))
    private val brush = labelled("Size" to size, "Strength" to strength)
    private val floor = labelled("Storey" to storey)
    private val status = JBLabel(" ")
    private val panel = JPanel(BorderLayout())
    private var model: MapModel? = null

    /**
     * This tab's own key, so its reads of the file coalesce with each other and with nothing else. The platform
     * refuses a key it might share — an editor, a file, a project — for exactly that reason.
     */
    private val reading = Any()

    init {
        hands.renderer = SimpleListCellRenderer.create("") { it.label }
        hands.addActionListener { showHand() }
        layers.renderer = SimpleListCellRenderer.create("") { it.label }
        layers.addActionListener { showKinds(kinds.selectedItem as? String) }
        for (spinner in listOf(size, strength, storey)) spinner.addChangeListener { sendHand() }
        val buttons = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Play This Map", "Start the game on it, in the Run window", AllIcons.Actions.Execute) {
                override fun actionPerformed(e: AnActionEvent) = DukePlay.play(project, file, true)
            })
            add(object : DumbAwareAction("Resize Map…", "Grow or shrink the map; everything on it moves with its floor",
                AllIcons.General.FitContent) {
                override fun actionPerformed(e: AnActionEvent) = resize()
            })
            add(object : DumbAwareAction("Save Preview", "Write ${MapPreview.NAME} beside the map, for the screen a map is chosen on",
                AllIcons.FileTypes.Image) {
                override fun actionPerformed(e: AnActionEvent) = savePreview()
            })
            if (canvas != null) {
                addSeparator()
                add(object : DumbAwareAction("Zoom Out", null, AllIcons.General.ZoomOut) {
                    override fun actionPerformed(e: AnActionEvent) = canvas.zoomBy(-1)
                })
                add(object : DumbAwareAction("Zoom In", "Ctrl+wheel zooms too", AllIcons.General.ZoomIn) {
                    override fun actionPerformed(e: AnActionEvent) = canvas.zoomBy(1)
                })
            }
        }
        val actions = ActionManager.getInstance().createActionToolbar("DukeMap", buttons, true).apply { targetComponent = panel }
        val tools = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            add(if (page != null) hands else JBLabel("Put down"))
            add(layers)
            add(kinds)
            if (page != null) {
                add(brush)
                add(floor)
            }
            add(actions.component)
            border = JBUI.Borders.customLineBottom(JBColor.border())
        }
        panel.add(tools, BorderLayout.NORTH)
        if (canvas != null) {
            status.border = JBUI.Borders.empty(3, 8)
            panel.add(JBScrollPane(canvas).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            panel.add(status, BorderLayout.SOUTH)
        } else {
            page?.let { panel.add(it.component, BorderLayout.CENTER) }
        }
        FileDocumentManager.getInstance().getDocument(file)?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                alarm.cancelAllRequests()
                alarm.addRequest(::load, 200)
            }
        }, this)
        showHand()
        load()
    }

    private fun load() {
        ReadAction.nonBlocking<Pair<MapModel, MapScene?>?> {
            val map = (PsiManager.getInstance(project).findFile(file) as? DukeFile)?.let(MapModels::build) ?: return@nonBlocking null
            map to page?.let { MapScenes.of(map) }
        }
            .inSmartMode(project)
            .withDocumentsCommitted(project)
            .expireWith(this)
            .coalesceBy(reading)
            .finishOnUiThread(ModalityState.defaultModalityState()) { show(it?.first, it?.second) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** The map read again; the tool kept, as the same list and the same kind, while the map still has them. */
    private fun show(map: MapModel?, scene: MapScene?) {
        val layer = (layers.selectedItem as? Layer)?.key
        val kind = kinds.selectedItem as? String
        model = map
        canvas?.model = map
        layers.model = DefaultComboBoxModel(map?.layers.orEmpty().toTypedArray())
        (map?.layers?.firstOrNull { it.key == layer } ?: map?.layers?.firstOrNull())?.let { layers.selectedItem = it }
        showKinds(kind)
        val page = page ?: return
        if (map != null && scene == null) return say("This file is in no resource root: there is nowhere to read its models from.")
        scene ?: return
        page.root = Path.of(scene.root).normalize()
        page.call("showMap", "duke.showMap(${scene.json}, ${DukeBrowser.colours()})")
    }

    private fun showKinds(keep: String?) {
        val layer = layers.selectedItem as? Layer
        val names = layer?.kinds.orEmpty()
        kinds.model = DefaultComboBoxModel(names.toTypedArray())
        // A kind no block is named for is typed.
        kinds.isEditable = layer?.kinds == null
        kinds.isVisible = layer?.kinded == true && layers.isVisible
        if (keep != null && (keep in names || kinds.isEditable)) kinds.selectedItem = keep
    }

    /** The controls the chosen hand works with, and no others; and the page told what its left button now does. */
    private fun showHand() {
        val hand = hands.item ?: Hand.PUT
        layers.isVisible = page == null || hand == Hand.PUT
        brush.isVisible = hand.brush
        floor.isVisible = hand == Hand.FLOOR
        showKinds(kinds.selectedItem as? String)
        sendHand()
    }

    private fun sendHand() {
        val hand = hands.item ?: Hand.PUT
        page?.call("tool", "duke.tool(${Json.of(mapOf("mode" to hand.mode, "radius" to size.value, "strength" to strength.value,
            "storey" to storey.value))})")
    }

    /**
     * What the page asks, in the words it posts: a cell, `x y`; a cell and the list of the thing on it; a thing's cell,
     * where it goes and its list; or, a line each, the rows of the relief a stroke left or the cells it painted.
     */
    private fun act(what: String, body: String) {
        val map = model ?: return
        val words = body.trim().split(' ')
        fun at(index: Int) = words.getOrNull(index)?.toIntOrNull()
        fun thing(layer: String?): Pair<Layer, Thing>? =
            map.thingsAt(at(0) ?: return null, at(1) ?: return null).firstOrNull { layer == null || it.first.key == layer }
        when (what) {
            "place" -> put(map, at(0) ?: return, at(1) ?: return)
            "remove" -> thing(null)?.let { (layer, thing) -> takeOff(map, layer, thing) }
            "pick" -> thing(words.getOrNull(2))?.let { (layer, thing) -> picked(layer, thing) }
            "move" -> thing(words.getOrNull(4))?.let { (layer, thing) -> move(map, layer, thing, at(2) ?: return, at(3) ?: return) }
            "relief" -> shape(map, body.lines())
            "cells" -> paint(map, body.lines())
            else -> LOG.warn("The map page asked for '$what', which the editor does not do")
        }
    }

    override fun put(map: MapModel, x: Int, y: Int) {
        val layer = layers.selectedItem as? Layer ?: return
        val kind = (kinds.selectedItem as? String)?.trim()
        if (map.isSolid(x, y)) return say("Rock: nothing is put down on it.")
        if (layer.kinded && kind.isNullOrBlank()) return say("Choose which ${layer.label.lowercase()} to put down.")
        edit("Put Down ${kind ?: layer.label}") { MapEdits.place(project, it, map, layer, kind, x, y) }
    }

    override fun move(map: MapModel, layer: Layer, thing: Thing, x: Int, y: Int) = when {
        map.isSolid(x, y) -> say("Rock: nothing is put down on it.")
        map.thingsAt(x, y).isNotEmpty() -> say("Something stands there already.")
        else -> edit("Move ${thing.kind ?: layer.label}") { MapEdits.move(it, map, layer, thing, x, y) }
    }

    override fun takeOff(map: MapModel, layer: Layer, thing: Thing) =
        edit("Take Off ${thing.kind ?: layer.label}") { MapEdits.remove(it, map, layer, thing) }

    /** A stroke over the ground: the relief it left, a row of corners a line, written if it is the map's size. */
    private fun shape(map: MapModel, rows: List<String>) {
        if (map.reliefKey == null) return refused("This map's record has no @Relief component: its ground stays flat.")
        val fits = rows.size == map.height + 1 && rows.all { row ->
            row.split(' ').let { corners -> corners.size == map.width + 1 && corners.all { it.toIntOrNull() != null } }
        }
        if (!fits) {
            LOG.warn("The map page sent a relief that is not ${map.width + 1} by ${map.height + 1} corners")
            return refused("")
        }
        edit("Shape Ground") { MapEdits.shape(it, map, rows) }
    }

    /** Cells a stroke painted, `x y c` a line: floor of a storey, a stair or rock, and no rock under what stands on one. */
    private fun paint(map: MapModel, lines: List<String>) {
        val cells = lines.mapNotNull { line ->
            val (x, y, char) = line.split(' ').takeIf { it.size == 3 } ?: return@mapNotNull null
            Triple(x.toIntOrNull() ?: return@mapNotNull null, y.toIntOrNull() ?: return@mapNotNull null,
                char.singleOrNull()?.takeIf { it.isDigit() || it == '/' || it in map.solid } ?: return@mapNotNull null)
        }
        val (laid, underThings) = cells.partition { (x, y, char) -> char !in map.solid || map.thingsAt(x, y).isEmpty() }
        if (laid.isNotEmpty()) edit("Paint Cells") { MapEdits.paint(it, map, laid) }
        if (underThings.isNotEmpty()) refused("Rock is not laid under what stands on a cell: take it off first.")
    }

    /**
     * The map at another size: the rows rewritten, and everything standing on them moved with the floor it stands
     * on. Refused while a thing would fall outside, because an editor that drops a monster to fit a new edge is an
     * editor nobody trusts with a morning's work.
     */
    private fun resize() {
        val map = model ?: return
        val dialog = ResizeMap(project, map.width, map.height)
        if (!dialog.showAndGet()) return
        val width = dialog.columns
        val height = dialog.rows
        val dx = dialog.anchor.acrossOf(map.width, width)
        val dy = dialog.anchor.downOf(map.height, height)
        val outside = map.outside(width, height, dx, dy)
        if (outside.isNotEmpty()) {
            return say("$width by $height leaves " + outside.take(3).joinToString(", ")
                + (if (outside.size > 3) " and ${outside.size - 3} more" else "") + " outside the map: take them off first.")
        }
        if (width == map.width && height == map.height) return
        edit("Resize Map") { MapEdits.resize(project, it, map, width, height, dx, dy) }
        say("The map is now $width by $height.")
    }

    /** The map's picture written beside it, which is what a screen listing the maps shows of this one. */
    private fun savePreview() {
        val map = model ?: return
        val written = runCatching { MapPreview.save(file, map) }.getOrElse {
            LOG.warn("The preview of ${file.name} could not be written", it)
            return say("${MapPreview.NAME} could not be written: ${it.message}")
        }
        say(if (written == null) "This map is in no folder of its own, so there is nowhere to put its picture."
            else "${MapPreview.NAME} written beside the map, ${map.width} by ${map.height} cells.")
    }

    /** Nothing written, and the page drawn from the file again over what it showed for the stroke. */
    private fun refused(why: String) {
        load()
        say(why)
    }

    private fun edit(name: String, change: (Document) -> Unit) {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return
        DukeEdits.write(project, psi, name, change)
    }

    override fun picked(layer: Layer, thing: Thing) {
        (0 until layers.itemCount).map { layers.getItemAt(it) }.firstOrNull { it.key == layer.key }?.let { layers.selectedItem = it }
        showKinds(thing.kind)
        say("Now putting down ${thing.kind ?: layer.label}.")
    }

    override fun say(text: String) {
        if (page != null) page.call("say", "duke.say(${Json.string(text)})") else status.text = text.ifEmpty { " " }
    }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = canvas ?: page?.component ?: panel

    override fun getName() = "Map"

    override fun setState(state: FileEditorState) = Unit

    override fun isModified() = false

    override fun isValid() = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun getFile() = file

    override fun dispose() = Unit

    /** Where the map it already has sits in the map it is becoming. */
    private enum class Corner(val label: String, private val across: Float, private val down: Float) {
        TOP_LEFT("Top left", 0f, 0f),
        TOP("Top", 0.5f, 0f),
        TOP_RIGHT("Top right", 1f, 0f),
        LEFT("Left", 0f, 0.5f),
        MIDDLE("Middle", 0.5f, 0.5f),
        RIGHT("Right", 1f, 0.5f),
        BOTTOM_LEFT("Bottom left", 0f, 1f),
        BOTTOM("Bottom", 0.5f, 1f),
        BOTTOM_RIGHT("Bottom right", 1f, 1f);

        /** Where the old map's left edge lands: none of it moves when it is kept at the left. */
        fun acrossOf(was: Int, now: Int) = Math.round((now - was) * across)

        fun downOf(was: Int, now: Int) = Math.round((now - was) * down)
    }

    /** How big the map is to be, and which of its corners stays where it is while it grows or shrinks. */
    private class ResizeMap(project: Project, was: Int, wasDown: Int) : DialogWrapper(project) {
        private val across = JSpinner(SpinnerNumberModel(was, 2, 1024, 1))
        private val down = JSpinner(SpinnerNumberModel(wasDown, 2, 1024, 1))
        private val corner = ComboBox(Corner.entries.toTypedArray())

        val columns get() = across.value as Int
        val rows get() = down.value as Int
        val anchor get() = corner.item ?: Corner.TOP_LEFT

        init {
            title = "Resize Map"
            corner.renderer = SimpleListCellRenderer.create("") { it.label }
            init()
        }

        override fun createCenterPanel(): JComponent = panel {
            row("Cells across:") { cell(across).focused() }
            row("Cells down:") { cell(down) }
            row("Keep the map at the:") { cell(corner) }
            row {
                comment("New cells are rock. Nothing standing on the map is cut off — a thing that would fall "
                    + "outside refuses the change, so take it off first.")
            }
        }
    }

    private companion object {
        /** Each field after the word it is for, in a row of their own, shown and hidden together. */
        fun labelled(vararg fields: Pair<String, JComponent>): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            for ((word, field) in fields) {
                add(JBLabel(word))
                add(field)
            }
        }
    }
}
