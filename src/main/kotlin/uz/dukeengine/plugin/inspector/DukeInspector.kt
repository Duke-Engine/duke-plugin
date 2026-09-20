package uz.dukeengine.plugin.inspector

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.psi.PsiManager
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import uz.dukeengine.plugin.duke.DukeFile
import uz.dukeengine.plugin.duke.DukeFileType
import uz.dukeengine.plugin.map.MapModels
import uz.dukeengine.plugin.play.DukePlay
import uz.dukeengine.plugin.preview.HudPreview
import uz.dukeengine.plugin.preview.HudScene
import uz.dukeengine.plugin.preview.HudScenes
import uz.dukeengine.plugin.preview.ModelPreview
import uz.dukeengine.plugin.preview.PreviewScene
import uz.dukeengine.plugin.preview.PreviewScenes
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent as SwingDocumentEvent
import javax.swing.event.DocumentListener as SwingDocumentListener
import javax.swing.text.JTextComponent

class DukeInspectorFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DukeInspectorPanel(project, toolWindow)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
    }

    companion object {
        const val ID = "Duke Inspector"
    }
}

/** Opens the Inspector the first time a `.duke` file is selected; after that it is the user's to show or hide. */
class DukeInspectorOpener(private val project: Project) : FileEditorManagerListener {
    override fun selectionChanged(event: FileEditorManagerEvent) {
        if (event.newFile?.fileType != DukeFileType || project.getUserData(OPENED) == true) return
        project.putUserData(OPENED, true)
        ApplicationManager.getApplication().invokeLater({
            ToolWindowManager.getInstance(project).getToolWindow(DukeInspectorFactory.ID)?.show()
        }, project.disposed)
    }

    private companion object {
        val OPENED = Key.create<Boolean>("duke.inspector.opened")
    }
}

/**
 * The block under the caret as a form: its record's fields in their groups, each with the editor its type takes,
 * its default where it is not written and its Javadoc under the form. Every change is written into the file as
 * text and the form is read from the file again after it: the file stays the truth. The caret and the form
 * follow each other — a field picked here is shown in the text, and a line the caret moves to is picked here.
 */
class DukeInspectorPanel(override val project: Project, private val window: ToolWindow) :
    SimpleToolWindowPanel(true, true), Disposable, RowHost {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val column = Column()
    private val header = JPanel(VerticalLayout(JBUI.scale(2)))
    private val search = SearchTextField(false)
    private val help = JBLabel()
    private var model: InspectorModel? = null
    private var wanted = 0
    private var stale = false
    private var showDefaults = true
    private var selected: String? = null
    private val collapsed = HashSet<String>()
    private val shownRows = LinkedHashMap<String, Pair<Row, JComponent>>()
    private var highlighter: RangeHighlighter? = null
    private val splitter = OnePixelSplitter(true, "duke.inspector.preview", 0.4f)
    private val preview: ModelPreview? by lazy { ModelPreview.create(this) }
    private val hudPreview: HudPreview? by lazy { HudPreview.create(this) }
    private var previewShown = false

    /** This panel's own key: the platform refuses a coalescing key it might share with unrelated work. */
    private val reading = Any()

    init {
        Disposer.register(window.disposable, this)
        val actions = DefaultActionGroup().apply {
            add(object : DumbAwareAction("New from Template…", "A new file with a record's own fields", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) =
                    NewFromTemplate.start(project, e.inputEvent?.component as? JComponent ?: this@DukeInspectorPanel, currentFile())
            })
            add(object : DumbAwareAction("Play", "Start the game — on this map, when the block is one — in the Run window", AllIcons.Actions.Execute) {
                override fun actionPerformed(e: AnActionEvent) {
                    val file = currentFile() ?: return
                    val onThisMap = MapModels.gridOf(file)?.first?.let { it == model?.block?.element } == true
                    file.virtualFile?.let { DukePlay.play(project, it, onThisMap) }
                }
            })
            add(object : DumbAwareAction("Add Sound…", "A sound this block makes at a moment: when it dies, is hurt, appears", AllIcons.Actions.AddList) {
                override fun actionPerformed(e: AnActionEvent) =
                    NewSound.start(project, e.inputEvent?.component as? JComponent ?: this@DukeInspectorPanel, model?.block?.element)
            })
            add(object : DumbAwareToggleAction("Show Defaults", "Show the fields a block does not write, at their defaults", AllIcons.Actions.ToggleVisibility) {
                override fun isSelected(e: AnActionEvent) = showDefaults
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    showDefaults = state
                    model?.let(::render)
                }

                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            add(object : DumbAwareAction("Refresh", null, AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refresh()
            })
        }
        toolbar = ActionManager.getInstance().createActionToolbar("DukeInspector", actions, true).apply { targetComponent = this@DukeInspectorPanel }.component

        search.textEditor.emptyText.text = "Filter fields"
        search.addDocumentListener(object : SwingDocumentListener {
            override fun insertUpdate(e: SwingDocumentEvent) = filtered()
            override fun removeUpdate(e: SwingDocumentEvent) = filtered()
            override fun changedUpdate(e: SwingDocumentEvent) = filtered()
        })
        val top = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(search.apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.SOUTH)
            border = JBUI.Borders.customLineBottom(JBColor.border())
        }
        help.verticalAlignment = JBLabel.TOP
        help.border = JBUI.Borders.empty(8, 12)
        val bottom = JPanel(BorderLayout()).apply {
            add(help, BorderLayout.CENTER)
            border = JBUI.Borders.customLineTop(JBColor.border())
            preferredSize = Dimension(0, JBUI.scale(96))
        }
        setContent(JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(splitter.apply { secondComponent = JBScrollPane(column).apply { border = JBUI.Borders.empty() } }, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        })

        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = refresh()
        })
        project.messageBus.connect(this).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(toolWindow: ToolWindow) {
                if (toolWindow === window && stale) refresh()
            }
        })
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (FileDocumentManager.getInstance().getFile(event.document)?.fileType == DukeFileType) refresh()
            }
        }, this)
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (event.editor.project == project) follow(event.editor)
            }
        }, this)
        refresh()
    }

    override fun dispose() = clearHighlight()

    fun refresh() {
        alarm.cancelAllRequests()
        stale = !window.isVisible
        if (!stale) alarm.addRequest(::load, 250)
    }

    private fun currentFile(): DukeFile? {
        val file = FileEditorManager.getInstance(project).selectedEditor?.file?.takeIf { it.isValid } ?: return null
        return PsiManager.getInstance(project).findFile(file) as? DukeFile
    }

    private fun load() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val selectedFile = FileEditorManager.getInstance(project).selectedEditor?.file
        val caret = editor?.takeIf { FileDocumentManager.getInstance().getFile(it.document) == selectedFile }?.caretModel?.offset
        val wanted = wanted
        ReadAction.nonBlocking<Triple<InspectorModel, PreviewScene?, HudScene?>?> {
            val file = selectedFile?.takeIf { it.isValid }?.let { PsiManager.getInstance(project).findFile(it) } as? DukeFile
            file?.let { InspectorModels.build(it, wanted, caret) }?.let { Triple(it, PreviewScenes.of(it), HudScenes.of(it)) }
        }
            .inSmartMode(project)
            .withDocumentsCommitted(project)
            .expireWith(this)
            .coalesceBy(reading)
            .finishOnUiThread(ModalityState.defaultModalityState()) { show(it?.first, it?.second, it?.third) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun show(next: InspectorModel?, scene: PreviewScene?, hud: HudScene?) {
        // Someone typing in the form keeps what they typed; the form is read again once they are done.
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        if (owner is JTextComponent && SwingUtilities.isDescendingFrom(owner, column) && owner.getClientProperty("duke.inspector.committed") != owner.text) {
            alarm.addRequest({ show(next, scene, hud) }, 500)
            return
        }
        if (next?.file?.element != model?.file?.element) selected = null
        model = next
        wanted = next?.index ?: 0
        if (next == null) {
            header.removeAll()
            header.add(JBLabel("Open a .duke file to edit its blocks here.").apply { foreground = Palette.muted; border = JBUI.Borders.empty(8) })
            column.removeAll()
            help.text = ""
            header.revalidate()
            column.revalidate()
            column.repaint()
            showPreview(null, null)
            return
        }
        render(next)
        showPreview(scene, hud)
    }

    /**
     * The block drawn above its form when it has a model or sounds; the hero's bar when it shapes it, its look or
     * one of its skins; the form alone when it is none of those.
     */
    private fun showPreview(scene: PreviewScene?, hud: HudScene?) {
        val model = scene?.let { preview }
        val bar = hud?.takeIf { model == null }?.let { hudPreview }
        val shown = model?.component ?: bar?.component
        if (splitter.firstComponent !== shown) splitter.firstComponent = shown
        previewShown = model != null
        if (scene != null) model?.show(scene)
        if (hud != null) bar?.show(hud)
    }

    private fun filtered() {
        model?.let(::render)
    }

    // ---- drawing the form ----

    private fun render(model: InspectorModel) {
        renderHeader(model)
        column.removeAll()
        shownRows.clear()
        model.note?.let { column.add(JBLabel(it).apply { foreground = Palette.muted; border = JBUI.Borders.empty(12) }) }
        val query = search.text.trim().lowercase()
        for (group in model.groups) {
            val rows = visible(group.rows, query)
            if (rows.isEmpty() && (query.isNotEmpty() || !showDefaults)) continue
            val fields = group.rows.filterIsInstance<FieldRow>()
            val count = if (fields.isEmpty()) "" else "${fields.count { it.isWritten }} of ${fields.size} written"
            column.add(groupHeader(group.title, count))
            if (group.title in collapsed && query.isEmpty()) continue
            for (row in rows) {
                val component = Rows.of(row, this)
                shownRows[row.id] = row to component
                column.add(component)
            }
        }
        column.revalidate()
        column.repaint()
        val row = selected?.let { shownRows[it]?.first }
        if (row != null) showHelp(row) else help.text = ""
    }

    private fun renderHeader(model: InspectorModel) {
        header.removeAll()
        val title = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply { isOpaque = false }
        title.add(JBLabel(model.title).apply { font = JBFont.h3().asBold() })
        if (model.word.isNotEmpty()) title.add(JBLabel(model.word).apply { foreground = Palette.muted })
        if (model.problems.isNotEmpty()) {
            title.add(JBLabel("${model.problems.size} problem" + if (model.problems.size == 1) "" else "s", AllIcons.General.Error, JBLabel.LEFT).apply {
                foreground = Palette.error
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        this@DukeInspectorPanel.model?.problems?.firstOrNull()?.let { select(it) }
                    }
                })
            })
        }
        header.add(title)
        header.add(JBLabel(model.path).apply { foreground = Palette.muted; font = JBFont.small(); border = JBUI.Borders.emptyLeft(8) })
        if (model.blocks.size > 1) {
            val blocks = ComboBox(model.blocks.toTypedArray())
            blocks.selectedIndex = model.index
            blocks.addActionListener { goToBlock(blocks.selectedIndex) }
            header.add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 8)
                add(JBLabel("Block  ").apply { foreground = Palette.muted }, BorderLayout.WEST)
                add(blocks, BorderLayout.CENTER)
            })
        }
        header.border = JBUI.Borders.empty(8, 4, 4, 4)
        header.revalidate()
        header.repaint()
    }

    /** The caret put at the start of another block of the file, which is the block the form then shows. */
    private fun goToBlock(index: Int) {
        if (index == model?.index) return
        wanted = index
        val file = model?.file?.element ?: return
        val block = file.blocks.getOrNull(index) ?: return
        val editor = editorOf(file) ?: return refresh()
        editor.caretModel.moveToOffset(block.textRange.startOffset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        refresh()
    }

    private fun groupHeader(title: String, count: String): JComponent {
        val open = title !in collapsed
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(5, 8))
            background = UIUtil.getPanelBackground()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        panel.add(JBLabel(title, if (open) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight, JBLabel.LEFT).apply { font = JBFont.label().asBold() }, BorderLayout.WEST)
        panel.add(JBLabel(count).apply { foreground = Palette.muted; font = JBFont.small() }, BorderLayout.EAST)
        panel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!collapsed.add(title)) collapsed.remove(title)
                model?.let(::render)
            }
        })
        return panel
    }

    /** The rows the filter keeps, and with each field shown only when it is written, if defaults are hidden. */
    private fun visible(rows: List<Row>, query: String): List<Row> {
        fun keeps(row: Row) = when (row) {
            is FieldRow -> (showDefaults || row.isWritten) && (query.isEmpty() || query in row.match)
            is RecordRow -> (showDefaults || row.word != null) && (query.isEmpty() || query in row.match)
            else -> query.isEmpty()
        }
        val kept = rows.filter(::keeps).map { it.id }.toMutableSet()
        // A card or a record's head stays while anything under it does.
        for (row in rows) {
            if ((row is CardRow || row is RecordRow) && rows.any { it.id.startsWith(row.id + "/") && it.id in kept }) kept += row.id
        }
        return rows.filter { it.id in kept }
    }

    // ---- the form and the text following each other ----

    override fun isSelected(id: String) = selected == id

    override fun select(row: Row, fromEditor: Boolean) {
        val before = selected
        selected = row.id
        if (before != row.id) {
            before?.let { shownRows[it]?.second?.repaint() }
            shownRows[row.id]?.second?.repaint()
        }
        showHelp(row)
        if (previewShown && row is FieldRow) (row.editor as? ValueEditor.Clip)?.let { clip -> (row.written ?: clip.inherited)?.let { preview?.play(it) } }
        val component = shownRows[row.id]?.second
        if (fromEditor) {
            component?.let { it.scrollRectToVisible(Rectangle(0, 0, it.width, it.height)) }
            return
        }
        val line = lineOf(row) ?: return clearHighlight()
        val file = model?.file?.element ?: return
        val editor = editorOf(file) ?: return
        editor.scrollingModel.scrollTo(LogicalPosition(line, 0), ScrollType.MAKE_VISIBLE)
        clearHighlight()
        highlighter = editor.markupModel.addLineHighlighter(line, HighlighterLayer.SELECTION - 1, TextAttributes().apply { backgroundColor = LINE })
    }

    /** The caret on another block's line shows that block; on a line of this one, picks the field it writes. */
    private fun follow(editor: Editor) {
        val model = model ?: return
        val file = model.file.element ?: return
        if (FileDocumentManager.getInstance().getFile(editor.document) != file.virtualFile) return
        val offset = editor.caretModel.offset
        val index = file.blocks.indexOfFirst { it.textRange.containsOffset(offset) }
        if (index >= 0 && index != model.index) {
            wanted = index
            refresh()
            return
        }
        val line = editor.document.getLineNumber(offset)
        val row = shownRows.values.map { it.first }.filter { (lineOf(it) ?: Int.MAX_VALUE) <= line }.maxByOrNull { lineOf(it)!! } ?: return
        if (row.id != selected) select(row, fromEditor = true)
    }

    private fun lineOf(row: Row): Int? = when (row) {
        is FieldRow -> row.line
        is RecordRow -> row.line
        is CardRow -> row.line
        else -> null
    }

    private fun editorOf(file: DukeFile): Editor? {
        val virtual = file.virtualFile ?: return null
        return FileEditorManager.getInstance(project).getEditors(virtual).filterIsInstance<TextEditor>().firstOrNull()?.editor
    }

    private fun clearHighlight() {
        highlighter?.dispose()
        highlighter = null
    }

    private fun showHelp(row: Row) {
        val help = when (row) {
            is FieldRow -> row.help
            is RecordRow -> row.help
            is CardRow -> row.help
            else -> null
        }
        this.help.text = if (help == null) "" else buildString {
            append("<html><b>").append(escape(help.title)).append("</b>&nbsp;&nbsp;<code>").append(escape(help.key)).append("</code>")
            if (help.type.isNotEmpty()) append("&nbsp;&nbsp;<font color='#").append(hex(Palette.muted)).append("'>").append(escape(help.type)).append("</font>")
            if (help.doc.isNotEmpty()) append("<br>").append(escape(help.doc))
            if (help.default.isNotEmpty()) append("<br><font color='#").append(hex(Palette.muted)).append("'>Default</font> ").append(escape(help.default))
            append("</html>")
        }
    }

    // ---- writing ----

    override fun edit(name: String, change: (Document) -> Unit) {
        val file = model?.file?.element ?: return
        DukeEdits.write(project, file, name, change)
        refresh()
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun hex(colour: Color) = "%06X".format(colour.rgb and 0xFFFFFF)

    /** Lays the rows out one under another, as wide as the tool window rather than as their widest row. */
    private class Column : JPanel(VerticalLayout(0)), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) = visible.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    private companion object {
        val LINE = JBColor(Color(0xFCEBDD), Color(0x3A2F27))
    }
}
