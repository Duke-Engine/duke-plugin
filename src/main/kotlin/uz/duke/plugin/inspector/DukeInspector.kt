package uz.duke.plugin.inspector

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import uz.duke.plugin.DukeBundle.message
import uz.duke.plugin.engine.FieldKind
import uz.duke.plugin.engine.FieldSpec
import uz.duke.plugin.ini.DukeIniEdits
import uz.duke.plugin.ini.DukeIniField
import uz.duke.plugin.ini.DukeIniFileType
import uz.duke.plugin.ini.DukeIniSection
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingUtilities
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

/** Opens the Inspector the first time a Duke INI file is selected; after that it is the user's to show or hide. */
class DukeInspectorOpener(private val project: Project) : FileEditorManagerListener {
    override fun selectionChanged(event: FileEditorManagerEvent) {
        if (event.newFile?.fileType != DukeIniFileType || project.getUserData(OPENED) == true) return
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
 * The selected INI file as a form: each block a section, each field an editor that suits its value,
 * each section inside a block a section of its own. Every change is written into the file as text,
 * and the form is read again from the file after it; the file stays the truth.
 */
class DukeInspectorPanel(private val project: Project, private val window: ToolWindow) : SimpleToolWindowPanel(true, true), Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val column = Column()
    private var model: InspectorModel? = null
    private var sections: List<Pair<SectionView, Collapsible>> = emptyList()
    private var followed: Collapsible? = null
    private val expanded = HashMap<String, Boolean>()
    private val hideables = HashMap<String, Collapsible>()
    private val focusables = HashMap<String, JComponent>()
    private var stale = false

    init {
        Disposer.register(window.disposable, this)
        val actions = DefaultActionGroup().apply {
            add(object : DumbAwareAction(message("inspector.add.block"), null, AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) = addBlock(e.inputEvent?.component as? JComponent ?: this@DukeInspectorPanel)
            })
            add(object : DumbAwareAction(message("inspector.refresh"), null, AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refresh()
            })
        }
        toolbar = ActionManager.getInstance().createActionToolbar("DukeInspector", actions, true).apply { targetComponent = this@DukeInspectorPanel }.component
        setContent(JBScrollPane(column).apply { border = JBUI.Borders.empty() })

        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = refresh()
        })
        // Hidden, it reads nothing: a big file would otherwise be laid out again on every pause in typing.
        project.messageBus.connect(this).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(toolWindow: ToolWindow) {
                if (toolWindow === window && stale) refresh()
            }
        })
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (FileDocumentManager.getInstance().getFile(event.document)?.fileType == DukeIniFileType) refresh()
            }
        }, this)
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (event.editor.project == project) follow(event.editor)
            }
        }, this)
        refresh()
    }

    override fun dispose() = Unit

    fun refresh() {
        alarm.cancelAllRequests()
        stale = !window.isVisible
        if (!stale) alarm.addRequest(::load, 300)
    }

    private fun load() {
        val selected = FileEditorManager.getInstance(project).selectedEditor?.file
        ReadAction.nonBlocking<InspectorModel?> {
            val file = selected?.takeIf { it.isValid }?.let { PsiManager.getInstance(project).findFile(it) } as? uz.duke.plugin.ini.DukeIniFile
            file?.let(InspectorModels::build)
        }
            .inSmartMode(project)
            .withDocumentsCommitted(project)
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.defaultModalityState(), ::show)
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun show(next: InspectorModel?) {
        // Someone typing in the form keeps what they typed; the form is read again once they are done.
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        if (owner is JTextComponent && SwingUtilities.isDescendingFrom(owner, column) && owner.getClientProperty(COMMITTED) != owner.text) {
            alarm.addRequest({ show(next) }, 500)
            return
        }
        val focused = (owner as? JComponent)?.takeIf { SwingUtilities.isDescendingFrom(it, column) }
            ?.let { generateSequence<java.awt.Component>(it) { c -> c.parent }.firstNotNullOfOrNull { c -> (c as? JComponent)?.getClientProperty(FOCUS) as? String } }
        hideables.forEach { (key, section) -> expanded[key] = section.row.expanded }
        hideables.clear()
        focusables.clear()
        followed = null
        model = next
        column.removeAll()
        if (next == null) {
            column.add(JBLabel(message("inspector.empty")).apply { foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.empty(8) })
            sections = emptyList()
        } else {
            render(next)
        }
        column.revalidate()
        column.repaint()
        focused?.let { focusables[it]?.requestFocusInWindow() }
        FileEditorManager.getInstance(project).selectedTextEditor?.let(::follow)
    }

    private fun render(model: InspectorModel) {
        column.add(JBLabel(model.title).apply { font = JBFont.h4(); border = JBUI.Borders.empty(4, 8) })
        sections = model.sections.mapIndexed { i, view ->
            val key = "$i ${view.title}"
            val section = hideable(key, view.title, model.sections.size <= 6) { sectionContent(key, view, model) }
            column.add(section.component)
            view to section
        }
    }

    /** A section that folds: its own panel, so the caret can bring it into view. */
    private class Collapsible(val component: JComponent, val row: CollapsibleRow)

    /** Its content is built the first time it opens: a file of seventy blocks is seventy titles until then. */
    private fun hideable(key: String, title: String, open: Boolean, content: () -> JComponent): Collapsible {
        val holder = JPanel(BorderLayout()).apply { isOpaque = false }
        lateinit var group: CollapsibleRow
        val component = panel { group = collapsibleGroup(title) { row { cell(holder).align(AlignX.FILL) } } }
        val fill = {
            if (holder.componentCount == 0) {
                holder.add(content())
                holder.revalidate()
            }
        }
        group.expanded = expanded[key] ?: open
        if (group.expanded) fill()
        group.addExpandedListener { if (it) fill() }
        return Collapsible(component, group).also { hideables[key] = it }
    }

    private fun sectionContent(key: String, view: SectionView, model: InspectorModel): JComponent = panel {
        view.fields.forEachIndexed { i, field ->
            row(field.key) {
                cell(editorOf(field, "$key/$i")).align(AlignX.FILL).resizableColumn()
                cell(InplaceButton(message("inspector.remove"), AllIcons.Actions.Close) { remove(field.field, field.key) })
            }
        }
        view.parts.forEachIndexed { i, part ->
            val partKey = "$key/m$i ${part.title}"
            row { cell(hideable(partKey, part.title, false) { sectionContent(partKey, part, model) }.component).align(AlignX.FILL) }
        }
        row {
            link(message("inspector.add.field")) { addField(view, it.source as JComponent) }
            link(message(if ('/' in view.sectionType) "inspector.remove.section" else "inspector.remove.block")) {
                remove(view.section, view.title)
            }
        }
    }

    private fun editorOf(row: FieldRow, key: String): JComponent {
        val component: JComponent = when (val how = row.editor) {
            ValueEditor.Check -> JBCheckBox(null, row.value.equals("Yes", ignoreCase = true)).apply {
                addActionListener { write(row, if (isSelected) "Yes" else "No") }
            }
            is ValueEditor.Choice -> ComboBox(DefaultComboBoxModel((listOf(row.value) + how.options).distinct().toTypedArray())).apply {
                isEditable = how.editable
                selectedItem = row.value
                prototypeDisplayValue = "x".repeat(12)
                (editor?.editorComponent as? JTextComponent)?.putClientProperty(COMMITTED, row.value)
                addActionListener {
                    val value = (selectedItem as? String)?.trim().orEmpty()
                    if (value.isEmpty() || value == row.value) return@addActionListener
                    (editor?.editorComponent as? JTextComponent)?.putClientProperty(COMMITTED, value)
                    write(row, value)
                }
            }
            is ValueEditor.Text -> JBTextField(row.value, 8).apply {
                putClientProperty(COMMITTED, row.value)
                addActionListener { commit(row, this, how.kind) }
                addFocusListener(object : FocusAdapter() {
                    override fun focusLost(e: FocusEvent) = commit(row, this@apply, how.kind)
                })
            }
        }
        component.toolTipText = row.hint.ifEmpty { null }
        component.putClientProperty(FOCUS, key)
        component.minimumSize = Dimension(JBUI.scale(40), component.minimumSize.height)
        focusables[key] = component
        return component
    }

    /** A number field takes only a number; the file would take anything, and the game would refuse it at load. */
    private fun commit(row: FieldRow, field: JBTextField, kind: FieldKind?) {
        val text = field.text.trim()
        if (text == field.getClientProperty(COMMITTED)) return
        val valid = text.isNotEmpty() && when (kind) {
            FieldKind.REAL -> text.removeSuffix("%").toFloatOrNull() != null
            FieldKind.INTEGER -> text.removeSuffix("%").toIntOrNull() != null
            else -> true
        }
        field.putClientProperty("JComponent.outline", if (valid) null else "error")
        if (!valid) return
        field.putClientProperty(COMMITTED, text)
        write(row, text)
    }

    private fun write(row: FieldRow, value: String) =
        edit(message("inspector.command.set", row.key)) { document -> row.field.element?.let { DukeIniEdits.setValue(document, it, value) } }

    private fun remove(pointer: SmartPsiElementPointer<out PsiElement>, what: String) =
        edit(message("inspector.command.remove", what)) { document -> pointer.element?.let { DukeIniEdits.remove(document, it) } }

    private fun addField(view: SectionView, anchor: JComponent) {
        val other = message("inspector.other.key")
        choose(anchor, view.addable.map { it.name } + other) { picked ->
            val key = if (picked != other) picked
            else Messages.showInputDialog(project, message("inspector.key.prompt"), message("inspector.add.field"), null)
                ?.trim()?.takeIf { KEY.matches(it) } ?: return@choose
            val spec = view.addable.firstOrNull { it.name == key }
            if (spec?.section != null) return@choose addSection(view, key, spec.list)
            val value = valueFor(view.sectionType, key, spec) ?: return@choose
            // Another line of a list goes after the lines it already has.
            val last = view.fields.lastOrNull { it.key.equals(key, ignoreCase = true) }?.field
            edit(message("inspector.command.add", key)) { document ->
                val after = last?.element
                if (after != null) DukeIniEdits.addFieldAfter(document, after, key, value)
                else view.section.element?.let { DukeIniEdits.addField(document, it, key, value) }
            }
        }
    }

    /**
     * `Generation = Layout`, the fields such sections usually write, and its End. A section the block has
     * one of is offered the name it usually has; a repeatable one is asked for its own.
     */
    private fun addSection(view: SectionView, key: String, repeatable: Boolean) {
        val type = view.sectionType + "/" + key.lowercase()
        val usual = if (repeatable) "" else model?.index?.sectionName(type).orEmpty()
        val name = Messages.showInputDialog(project, message("inspector.section.prompt", key), message("inspector.add.field"), null, usual, null)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        edit(message("inspector.command.add", "$key = $name")) { document ->
            view.section.element?.let { DukeIniEdits.addSection(document, it, key, name, model?.index?.defaults(type).orEmpty()) }
        }
    }

    /** What a new field starts as: how the project usually writes it, else a value of its kind, else asked. */
    private fun valueFor(sectionType: String, key: String, spec: FieldSpec?): String? {
        model?.index?.mostCommon(sectionType, key)?.let { return it }
        val typed = when (spec?.kind) {
            FieldKind.REAL, FieldKind.INTEGER -> "0"
            FieldKind.BOOL -> "No"
            FieldKind.ENUM -> spec.choices.firstOrNull()
            FieldKind.COLOUR -> "0xFFFFFF"
            else -> null
        }
        return typed ?: Messages.showInputDialog(project, message("inspector.value.prompt", key), message("inspector.add.field"), null)
            ?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun addBlock(anchor: JComponent) {
        val model = model ?: return
        choose(anchor, model.blockTypes.map { it.type }.distinct()) { type ->
            val names = Messages.showInputDialog(
                project, message("inspector.block.prompt", type), message("inspector.add.block"), null, "", null,
            )?.trim() ?: return@choose
            val header = "$type $names".trim()
            edit(message("inspector.command.add", header)) { document ->
                DukeIniEdits.addBlock(document, header, model.index.defaults(type.lowercase()))
            }
        }
    }

    private fun choose(anchor: JComponent, items: List<String>, chosen: (String) -> Unit) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(items)
            .setNamerForFiltering { it }
            .setItemChosenCallback { item -> ApplicationManager.getApplication().invokeLater { chosen(item) } }
            .createPopup()
            .showUnderneathOf(anchor)
    }

    private fun edit(name: String, change: (Document) -> Unit) {
        val file = model?.file?.element ?: return
        DukeIniEdits.write(project, file, name, change)
    }

    /** Opens the section the caret is in and brings it into view. */
    private fun follow(editor: Editor) {
        val model = model ?: return
        if (FileDocumentManager.getInstance().getFile(editor.document) != model.file.virtualFile) return
        val offset = editor.caretModel.offset
        val section = sections.firstOrNull { (view, _) -> view.range.containsOffset(offset) }?.second ?: return
        if (section === followed) return
        followed = section
        section.row.expanded = true
        val component = section.component
        SwingUtilities.invokeLater { component.scrollRectToVisible(Rectangle(0, 0, component.width, component.height.coerceAtMost(JBUI.scale(200)))) }
    }

    /** Lays the sections out one under another, as wide as the tool window rather than as their widest field. */
    private class Column : JPanel(VerticalLayout(JBUI.scale(2))), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) = visible.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    private companion object {
        const val COMMITTED = "duke.inspector.committed"
        const val FOCUS = "duke.inspector.focus"
        val KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
