package uz.duke.plugin.map

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
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
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import uz.duke.plugin.duke.DukeFile
import uz.duke.plugin.duke.DukeFileType
import uz.duke.plugin.inspector.DukeEdits
import uz.duke.plugin.play.DukePlay
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.beans.PropertyChangeListener
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel

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

/**
 * The map of a file, drawn from its text and written back into it: each thing put down, moved or taken off is a
 * line of the file changed, one undoable command, and the map is read from the file again after it — the text
 * stays the truth, and the Text tab beside this one shows every change as it is made.
 */
class DukeMapEditor(override val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor, MapHost {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val canvas = MapCanvas(this)
    private val layers = ComboBox<Layer>()
    private val kinds = ComboBox<String>()
    private val status = JBLabel(" ")
    private val panel = JPanel(BorderLayout())

    init {
        layers.renderer = SimpleListCellRenderer.create("") { it.label }
        layers.addActionListener { showKinds(kinds.selectedItem as? String) }
        val buttons = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Play This Map", "Start the game on it, in the Run window", AllIcons.Actions.Execute) {
                override fun actionPerformed(e: AnActionEvent) = DukePlay.play(project, file, true)
            })
            addSeparator()
            add(object : DumbAwareAction("Zoom Out", null, AllIcons.General.ZoomOut) {
                override fun actionPerformed(e: AnActionEvent) = canvas.zoomBy(-1)
            })
            add(object : DumbAwareAction("Zoom In", "Ctrl+wheel zooms too", AllIcons.General.ZoomIn) {
                override fun actionPerformed(e: AnActionEvent) = canvas.zoomBy(1)
            })
        }
        val actions = ActionManager.getInstance().createActionToolbar("DukeMap", buttons, true).apply { targetComponent = panel }
        val tools = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            add(JBLabel("Put down"))
            add(layers)
            add(kinds)
            add(actions.component)
            border = JBUI.Borders.customLineBottom(JBColor.border())
        }
        status.border = JBUI.Borders.empty(3, 8)
        panel.add(tools, BorderLayout.NORTH)
        panel.add(JBScrollPane(canvas).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        panel.add(status, BorderLayout.SOUTH)
        FileDocumentManager.getInstance().getDocument(file)?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                alarm.cancelAllRequests()
                alarm.addRequest(::load, 200)
            }
        }, this)
        load()
    }

    private fun load() {
        ReadAction.nonBlocking<MapModel?> { (PsiManager.getInstance(project).findFile(file) as? DukeFile)?.let(MapModels::build) }
            .inSmartMode(project)
            .withDocumentsCommitted(project)
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.defaultModalityState(), ::show)
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** The map read again; the tool kept, as the same list and the same kind, while the map still has them. */
    private fun show(model: MapModel?) {
        val layer = (layers.selectedItem as? Layer)?.key
        val kind = kinds.selectedItem as? String
        canvas.model = model
        layers.model = DefaultComboBoxModel(model?.layers.orEmpty().toTypedArray())
        (model?.layers?.firstOrNull { it.key == layer } ?: model?.layers?.firstOrNull())?.let { layers.selectedItem = it }
        showKinds(kind)
    }

    private fun showKinds(keep: String?) {
        val layer = layers.selectedItem as? Layer
        val names = layer?.kinds.orEmpty()
        kinds.model = DefaultComboBoxModel(names.toTypedArray())
        // A kind no block is named for is typed.
        kinds.isEditable = layer?.kinds == null
        kinds.isVisible = layer?.kinded == true
        if (keep != null && (keep in names || kinds.isEditable)) kinds.selectedItem = keep
    }

    override val tool: Pair<Layer, String?>?
        get() = (layers.selectedItem as? Layer)?.let { it to (kinds.selectedItem as? String)?.trim() }

    override fun edit(name: String, change: (Document) -> Unit) {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return
        DukeEdits.write(project, psi, name, change)
    }

    override fun picked(layer: Layer, thing: Thing) {
        (0 until layers.itemCount).map { layers.getItemAt(it) }.firstOrNull { it.key == layer.key }?.let { layers.selectedItem = it }
        showKinds(thing.kind)
        say("Now putting down ${thing.kind ?: layer.label}.")
    }

    override fun say(text: String) {
        status.text = text.ifEmpty { " " }
    }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = canvas

    override fun getName() = "Map"

    override fun setState(state: FileEditorState) = Unit

    override fun isModified() = false

    override fun isValid() = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun getFile() = file

    override fun dispose() = Unit
}
