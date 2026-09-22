package uz.dukeengine.plugin.inspector

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.pom.Navigatable
import com.intellij.ui.ColorPanel
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import uz.dukeengine.plugin.assets.AssetKind
import uz.dukeengine.plugin.assets.DukeAssets
import uz.dukeengine.plugin.duke.DukeBlock
import uz.dukeengine.plugin.duke.DukeRecords
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel

/** What a row asks of the panel it is in. */
internal interface RowHost {
    val project: Project

    /** One undoable change to the file shown; the form is read from the file again after it. */
    fun edit(name: String, change: (Document) -> Unit)

    fun select(row: Row, fromEditor: Boolean = false)

    fun isSelected(id: String): Boolean
}

internal object Palette {
    val accent = JBColor(Color(0xD9773A), Color(0xE8894F))
    val guide = JBColor(Color(0xC9CCD2), Color(0x4A4D56))
    val selected = JBColor(Color(0xFCEBDD), Color(0x3A2F27))
    val card = JBColor(Color(0xF3F4F6), Color(0x2A2C31))
    val cardEdge = JBColor(Color(0xD8DBE0), Color(0x3A3C44))
    val error = JBColor(Color(0xC7402E), Color(0xF07A6E))
    val muted: Color get() = UIUtil.getContextHelpForeground()
}

/** A step in, for each record a field is inside. */
internal val STEP get() = JBUI.scale(22)
private val LABEL get() = JBUI.scale(150)
private val LEFT get() = JBUI.scale(12)

/** The same steps unscaled, for borders, which scale themselves. */
private const val INDENT = 12
private const val STEP_UNSCALED = 22

private const val COMMITTED = "duke.inspector.committed"

/**
 * One row of the form, and what is painted round it: the mark of a field that is written, the line from a
 * record's head down past its fields, and the selection.
 */
internal open class RowPanel(val row: Row, private val host: RowHost, private val written: Boolean) : JPanel(BorderLayout(JBUI.scale(8), 0)) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(3, INDENT + row.depth * STEP_UNSCALED + 8, 3, 8)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = host.select(row)
        })
    }

    override fun paintComponent(g: Graphics) {
        if (host.isSelected(row.id)) {
            g.color = Palette.selected
            g.fillRect(0, 0, width, height)
        }
        g.color = Palette.guide
        for (level in 1..row.depth) g.fillRect(LEFT + (level - 1) * STEP + JBUI.scale(6), 0, JBUI.scale(1), height)
        if (written) {
            g.color = Palette.accent
            val x = if (row.depth > 0) LEFT + (row.depth - 1) * STEP + JBUI.scale(11) else JBUI.scale(4)
            g.fillRect(x, JBUI.scale(7), JBUI.scale(2), height - JBUI.scale(14))
        }
        super.paintComponent(g)
    }
}

internal object Rows {

    fun of(row: Row, host: RowHost): JComponent = when (row) {
        is FieldRow -> field(row, host)
        is RecordRow -> record(row, host)
        is CardRow -> card(row, host)
        is NoteRow -> note(row)
        is AddRow -> add(row, host)
    }

    // ---- a field ----

    private fun field(row: FieldRow, host: RowHost): JComponent {
        val panel = RowPanel(row, host, row.isWritten)
        // A map's entries take the whole width: its name goes above them rather than in the label
        // column, which leaves a long key — SUBDUAL_BUILDING — nothing to be written beside.
        val above = row.editor is ValueEditor.Entries
        panel.add(label(row.label, row.isWritten, row.help.key), if (above) BorderLayout.NORTH else BorderLayout.WEST)
        val centre = JPanel(VerticalLayout(JBUI.scale(2))).apply { isOpaque = false }
        centre.add(editor(row, host))
        row.problem?.let { centre.add(JBLabel(it, AllIcons.General.Error, JBLabel.LEFT).apply { foreground = Palette.error; font = JBFont.small() }) }
        panel.add(centre, BorderLayout.CENTER)
        if (row.isWritten) panel.add(button("Reset to default — takes the line out of the file", AllIcons.General.Reset) {
            host.edit("Reset ${row.help.key}") { document -> reset(document, row) }
        }, BorderLayout.EAST)
        return panel
    }

    private fun reset(document: Document, row: FieldRow) {
        val owner = row.place.owner.element ?: return
        owner.field(row.place.key)?.let { DukeEdits.removeLines(document, it) }
    }

    private fun label(text: String, written: Boolean, key: String) = JBLabel(text).apply {
        foreground = if (written) UIUtil.getLabelForeground() else Palette.muted
        preferredSize = Dimension(LABEL, preferredSize.height)
        minimumSize = preferredSize
        toolTipText = key
    }

    private fun editor(row: FieldRow, host: RowHost): JComponent = when (val how = row.editor) {
        is ValueEditor.Text -> text(row, host)
        ValueEditor.Check -> check(row, host)
        is ValueEditor.Choice -> choice(row, host, how.options)
        is ValueEditor.Link -> link(row, host, how)
        is ValueEditor.Clip -> clip(row, host, how)
        is ValueEditor.Path -> path(row, host, how)
        ValueEditor.Colour -> colour(row, host)
        is ValueEditor.Values -> if (how.options != null) items(row, host, how.options) else typedItems(row, host)
        is ValueEditor.Tuple -> tuple(row, host, how.labels)
        is ValueEditor.Variant -> variant(row, host, how.options)
        is ValueEditor.Entries -> entries(row, host, how)
    }

    private fun set(host: RowHost, row: FieldRow, text: String) = host.edit("Set ${row.help.key}") { document ->
        row.place.owner.element?.let { DukeEdits.setValue(document, it, row.place.key, row.place.order, text) }
    }

    private fun setItems(host: RowHost, row: FieldRow, items: List<String>) = host.edit("Set ${row.help.key}") { document ->
        row.place.owner.element?.let { DukeEdits.setValues(document, it, row.place.key, row.place.order, items) }
    }

    /** A text box that writes when it is left or on Enter, and refuses what the field cannot read. */
    private fun text(row: FieldRow, host: RowHost): JComponent {
        val box = JBTextField(row.shown, 14)
        if (!row.isWritten) box.foreground = Palette.muted
        box.putClientProperty(COMMITTED, row.shown)
        val commit = commit@{
            val text = box.text.trim()
            if (text == box.getClientProperty(COMMITTED)) return@commit
            if (text.isEmpty()) {
                box.putClientProperty(COMMITTED, text)
                if (row.isWritten) host.edit("Reset ${row.help.key}") { document -> reset(document, row) }
                return@commit
            }
            val problem = DukeRecords.problemOf(text, row.type, row.help.key)
            if (problem != null) {
                box.putClientProperty("JComponent.outline", "error")
                box.toolTipText = problem
                return@commit
            }
            box.putClientProperty(COMMITTED, text)
            set(host, row, DukeEdits.quoted(text))
        }
        box.addActionListener { commit() }
        box.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = host.select(row)
            override fun focusLost(e: FocusEvent) = commit()
        })
        return fill(box)
    }

    private fun check(row: FieldRow, host: RowHost): JComponent {
        val on = row.shown.equals("Yes", ignoreCase = true) || row.shown.equals("true", ignoreCase = true)
        val box = JBCheckBox(if (row.isWritten) null else "default", on)
        box.addActionListener { set(host, row, if (box.isSelected) "Yes" else "No") }
        return fill(box)
    }

    private fun choice(row: FieldRow, host: RowHost, options: List<String>): JComponent {
        val shown = options.firstOrNull { it.equals(row.shown, ignoreCase = true) }
        val box = ComboBox((if (shown == null) listOf("none") + options else options).toTypedArray())
        box.selectedItem = shown ?: "none"
        box.addActionListener {
            val picked = box.selectedItem as? String ?: return@addActionListener
            if (picked != "none" && !picked.equals(row.written, ignoreCase = true)) set(host, row, picked)
        }
        return fill(box)
    }

    /** A block's name, picked from the blocks of the record there are; and a way to open the one named. */
    private fun link(row: FieldRow, host: RowHost, link: ValueEditor.Link): JComponent {
        val panel = flow()
        lateinit var pick: ActionLink
        pick = ActionLink(row.written ?: "none") { choose(pick, link.names, link.record, row.written) { set(host, row, it) } }
        panel.add(pick)
        panel.add(JBLabel(link.record).apply { foreground = Palette.muted; font = JBFont.small() })
        if (row.isWritten) panel.add(button("Open ${row.written}", AllIcons.Actions.EditSource) {
            val value = row.place.owner.element?.field(row.place.key)?.value
            (value?.reference?.resolve() as? Navigatable)?.navigate(true)
        })
        return panel
    }

    /** A clip, picked from the files the block is drawn from; the one it takes from what it links when it names none. */
    private fun clip(row: FieldRow, host: RowHost, clip: ValueEditor.Clip): JComponent {
        val panel = flow()
        val shown = row.written ?: clip.inherited ?: "none"
        lateinit var pick: ActionLink
        pick = ActionLink(shown) {
            JBPopupFactory.getInstance().createPopupChooserBuilder(clip.clips)
                .setTitle("${row.label} clip")
                .setRenderer(SimpleListCellRenderer.create { label, value, _ -> label.text = "${value.name}    ${value.file}" })
                .setNamerForFiltering { it.name }
                .setItemChosenCallback { chosen -> later { set(host, row, chosen.name) } }
                .createPopup().showUnderneathOf(pick)
        }
        if (!row.isWritten) pick.foreground = Palette.muted
        panel.add(pick)
        if (!row.isWritten && clip.from != null) panel.add(JBLabel("from ${clip.from}").apply { foreground = Palette.muted; font = JBFont.small() })
        if (clip.clips.isEmpty()) panel.add(JBLabel("no clips found in its files").apply { foreground = Palette.muted; font = JBFont.small() })
        return panel
    }

    /** A file of the kinds the key takes; an image picked from a gallery of them, and shown beside its name. */
    private fun path(row: FieldRow, host: RowHost, path: ValueEditor.Path): JComponent {
        val panel = flow()
        val root = row.place.owner.element?.let(DukeAssets::rootOf)
        val images = path.files.isNotEmpty() && path.files.all { AssetKind.of(it) == AssetKind.IMAGE }
        lateinit var pick: ActionLink
        pick = ActionLink(row.written ?: "none") {
            if (images && root != null) ImageGallery.choose(pick, root, path.files, row.written) { later { set(host, row, it) } }
            else choose(pick, path.files, row.label, row.written) { set(host, row, it) }
        }
        val written = row.written
        if (images && root != null && written != null) {
            val thumbnail = JBLabel()
            fun show() {
                thumbnail.icon = ImageGallery.thumbnail(root, written, ::show, size = 28)
            }
            show()
            panel.add(thumbnail)
        }
        panel.add(pick)
        return panel
    }

    private fun colour(row: FieldRow, host: RowHost): JComponent {
        val panel = flow()
        val colour = ColorPanel()
        colour.selectedColor = parseColour(row.shown)
        colour.addActionListener {
            val picked = colour.selectedColor ?: return@addActionListener
            set(host, row, "0x%06X".format(picked.rgb and 0xFFFFFF))
        }
        panel.add(colour)
        panel.add(JBLabel(row.shown).apply { if (!row.isWritten) foreground = Palette.muted })
        return panel
    }

    private fun parseColour(text: String): Color? = runCatching {
        Color(Integer.parseUnsignedInt(text.removePrefix("0x").removePrefix("0X"), 16))
    }.getOrNull()

    /** A list whose items are chosen: each on a line of its own, moved up and down, taken out; one more added. */
    private fun items(row: FieldRow, host: RowHost, options: List<String>): JComponent {
        val panel = JPanel(VerticalLayout(JBUI.scale(2))).apply { isOpaque = false }
        val items = if (row.isWritten) row.items else emptyList()
        items.forEachIndexed { index, item ->
            val line = flow()
            lateinit var pick: ActionLink
            pick = ActionLink(item) { choose(pick, options, row.label, item) { chosen -> setItems(host, row, items.toMutableList().also { it[index] = chosen }) } }
            line.add(JBLabel("${index + 1}").apply { foreground = Palette.muted; preferredSize = Dimension(JBUI.scale(18), preferredSize.height) })
            line.add(pick)
            if (index > 0) line.add(button("Move up", AllIcons.Actions.MoveUp) { setItems(host, row, swapped(items, index, index - 1)) })
            if (index < items.lastIndex) line.add(button("Move down", AllIcons.Actions.MoveDown) { setItems(host, row, swapped(items, index, index + 1)) })
            line.add(button("Remove $item", AllIcons.Actions.Close) { setItems(host, row, items.filterIndexed { at, _ -> at != index }) })
            panel.add(line)
        }
        if (items.isEmpty()) panel.add(JBLabel(if (row.isWritten) "none" else row.default).apply { foreground = Palette.muted })
        lateinit var add: ActionLink
        add = ActionLink("+ Add") { choose(add, options, row.label, null) { setItems(host, row, items + it) } }
        panel.add(flow().apply { this.add(add) })
        return panel
    }

    /**
     * A list typed rather than chosen: its items in one box, comma between them. A long one — a map's cells — is
     * said by its length, and edited in the file where it can be seen.
     */
    private fun typedItems(row: FieldRow, host: RowHost): JComponent {
        if (row.items.size > 8) {
            val panel = flow()
            panel.add(JBLabel("${row.items.size} items").apply { foreground = Palette.muted })
            panel.add(ActionLink("Edit in the file") { host.select(row) })
            return panel
        }
        val box = JBTextField(if (row.isWritten) row.items.joinToString(", ") else row.default.removePrefix("[").removeSuffix("]"), 14)
        if (!row.isWritten) box.foreground = Palette.muted
        box.putClientProperty(COMMITTED, box.text)
        val commit = commit@{
            if (box.text == box.getClientProperty(COMMITTED)) return@commit
            box.putClientProperty(COMMITTED, box.text)
            setItems(host, row, box.text.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        }
        box.addActionListener { commit() }
        box.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = host.select(row)
            override fun focusLost(e: FocusEvent) = commit()
        })
        return fill(box)
    }

    /** A small record of numbers, `[20, 60]`: a box for each, labelled by its component. */
    private fun tuple(row: FieldRow, host: RowHost, labels: List<String>): JComponent {
        val panel = flow()
        val values = (if (row.isWritten) row.items else row.default.removePrefix("[").removeSuffix("]").split(',').map { it.trim() })
        val boxes = labels.mapIndexed { index, label ->
            panel.add(JBLabel(label.lowercase()).apply { foreground = Palette.muted; font = JBFont.small() })
            JBTextField(values.getOrElse(index) { "0" }, 5).also { box ->
                if (!row.isWritten) box.foreground = Palette.muted
                panel.add(box)
            }
        }
        val committed = boxes.joinToString(", ") { it.text }
        val commit = commit@{
            val texts = boxes.map { it.text.trim() }
            if (texts.joinToString(", ") == committed) return@commit
            val bad = boxes.filter { box -> box.text.trim().toDoubleOrNull() == null }
            boxes.forEach { it.putClientProperty("JComponent.outline", if (it in bad) "error" else null) }
            if (bad.isNotEmpty()) return@commit
            set(host, row, "[" + texts.joinToString(", ") + "]")
        }
        for (box in boxes) {
            box.addActionListener { commit() }
            box.addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) = host.select(row)
                override fun focusLost(e: FocusEvent) = commit()
            })
        }
        return panel
    }

    /** A sealed type's word: choosing one writes the record, `Geometry = Cylinder`, keeping the fields both have. */
    private fun variant(row: FieldRow, host: RowHost, options: List<String>): JComponent {
        val shown = options.firstOrNull { it.equals(row.written, ignoreCase = true) }
        val box = ComboBox((if (shown == null) listOf("none") + options else options).toTypedArray())
        box.selectedItem = shown ?: "none"
        box.addActionListener {
            val word = box.selectedItem as? String ?: return@addActionListener
            if (word == "none" || word.equals(row.written, ignoreCase = true)) return@addActionListener
            host.edit("Set ${row.help.key}") { document ->
                val owner = row.place.owner.element ?: return@edit
                val nested = owner.field(row.place.key)?.nested
                if (nested == null) {
                    DukeEdits.addNested(document, owner, row.place.key, row.place.order, word, firstLineOf(owner, row.place.key, word))
                } else {
                    val type = DukeRecords.blockClass(row.type) ?: return@edit
                    val record = DukeRecords.accepting(type, word, owner)
                    val keep = record?.recordComponents?.map { DukeRecords.capitalized(it.name) }?.toSet().orEmpty()
                    DukeEdits.setWord(document, nested, word, keep)
                }
            }
        }
        return fill(box)
    }

    /** A map: an entry a line, its key chosen where the keys are known, its value typed; one more added. */
    private fun entries(row: FieldRow, host: RowHost, entries: ValueEditor.Entries): JComponent {
        val panel = JPanel(VerticalLayout(JBUI.scale(2))).apply { isOpaque = false }
        val pairs = row.items.map { it.substringBefore(" = ") to it.substringAfter(" = ") }
        fun edit(name: String, change: (Document, DukeBlock) -> Unit) = host.edit("$name ${row.help.key}") { document ->
            row.place.owner.element?.let { change(document, it) }
        }
        for ((key, value) in pairs) {
            // The key takes what it needs and the box the rest: a flow would wrap the box onto a second
            // line and the row, sized for one, would cut it in half.
            val line = JPanel(BorderLayout(JBUI.scale(6), 0)).apply { isOpaque = false }
            val name = flow()
            if (entries.keys != null) {
                lateinit var pick: ActionLink
                pick = ActionLink(key) {
                    choose(pick, entries.keys.filter { candidate -> pairs.none { it.first == candidate } }, row.label, key) { chosen ->
                        edit("Rename") { document, owner -> DukeEdits.renameEntry(document, owner, row.place.key, key, chosen) }
                    }
                }
                name.add(pick)
            } else {
                name.add(JBLabel(key))
            }
            name.add(JBLabel("=").apply { foreground = Palette.muted })
            line.add(name, BorderLayout.WEST)
            val box = JBTextField(value, 5)
            box.putClientProperty(COMMITTED, value)
            val commit = commit@{
                val text = box.text.trim()
                if (text == box.getClientProperty(COMMITTED) || text.isEmpty()) return@commit
                if (entries.numeric && text.toDoubleOrNull() == null) {
                    box.putClientProperty("JComponent.outline", "error")
                    return@commit
                }
                box.putClientProperty(COMMITTED, text)
                edit("Set") { document, owner -> DukeEdits.setEntry(document, owner, row.place.key, row.place.order, key, text) }
            }
            box.addActionListener { commit() }
            box.addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) = host.select(row)
                override fun focusLost(e: FocusEvent) = commit()
            })
            line.add(box, BorderLayout.CENTER)
            line.add(button("Remove $key", AllIcons.Actions.Close) {
                edit("Remove") { document, owner -> DukeEdits.removeEntry(document, owner, row.place.key, row.place.order, key) }
            }, BorderLayout.EAST)
            panel.add(line)
        }
        if (pairs.isEmpty()) panel.add(JBLabel("none").apply { foreground = Palette.muted })
        lateinit var add: ActionLink
        add = ActionLink("+ Add") {
            val first = if (entries.numeric) "1" else "0"
            if (entries.keys != null) {
                choose(add, entries.keys.filter { candidate -> pairs.none { it.first == candidate } }, row.label, null) { chosen ->
                    edit("Add") { document, owner -> DukeEdits.setEntry(document, owner, row.place.key, row.place.order, chosen, first) }
                }
            } else {
                val key = Messages.showInputDialog(host.project, "Key:", "Add to ${row.label}", null)?.trim()
                if (!key.isNullOrEmpty()) edit("Add") { document, owner -> DukeEdits.setEntry(document, owner, row.place.key, row.place.order, key, first) }
            }
        }
        panel.add(flow().apply { this.add(add) })
        return panel
    }

    // ---- a record inside another ----

    private fun record(row: RecordRow, host: RowHost): JComponent {
        val panel = RowPanel(row, host, row.word != null)
        panel.add(label(row.label, row.word != null, row.help.key).apply { font = JBFont.label().asBold() }, BorderLayout.WEST)
        val centre = flow()
        if (row.word != null) {
            centre.add(chip(row.word))
            panel.add(button("Remove ${row.label}", AllIcons.Actions.GC) {
                host.edit("Remove ${row.help.key}") { document ->
                    row.place.owner.element?.field(row.place.key)?.let { DukeEdits.removeLines(document, it) }
                }
            }, BorderLayout.EAST)
        } else {
            centre.add(JBLabel("none").apply { foreground = Palette.muted })
            centre.add(ActionLink("+ Add ${row.label.lowercase()}") {
                val word = row.words.firstOrNull() ?: return@ActionLink
                host.edit("Add ${row.help.key}") { document ->
                    val owner = row.place.owner.element ?: return@edit
                    DukeEdits.addNested(document, owner, row.place.key, row.place.order, word, firstLineOf(owner, row.place.key, word))
                }
            })
        }
        panel.add(centre, BorderLayout.CENTER)
        return panel
    }

    // ---- a list of blocks ----

    /** A module, a skill, a layer: its word and the families it is in, moved up and down the list, or taken out. */
    private fun card(row: CardRow, host: RowHost): JComponent {
        val panel = object : RowPanel(row, host, false) {
            override fun paintComponent(g: Graphics) {
                g.color = if (host.isSelected(row.id)) Palette.selected else Palette.card
                val x = LEFT + row.depth * STEP
                g.fillRect(x, JBUI.scale(4), width - x - JBUI.scale(8), height - JBUI.scale(4))
                g.color = Palette.cardEdge
                g.drawRect(x, JBUI.scale(4), width - x - JBUI.scale(9), height - JBUI.scale(5))
                g.color = Palette.guide
                for (level in 1..row.depth) g.fillRect(LEFT + (level - 1) * STEP + JBUI.scale(6), 0, JBUI.scale(1), height)
            }
        }
        panel.border = JBUI.Borders.empty(8, INDENT + row.depth * STEP_UNSCALED + 8, 4, 12)
        val title = flow()
        title.add(JBLabel(row.word, AllIcons.Nodes.Module, JBLabel.LEFT).apply { font = JBFont.label().asBold() })
        row.groups.forEach { title.add(chip(it)) }
        panel.add(title, BorderLayout.CENTER)
        val tools = flow()
        fun move(by: Int) = host.edit("Move ${row.word}") { document -> row.block.element?.let { DukeEdits.moveBlock(document, it, by) } }
        if (row.index > 0) tools.add(button("Move up", AllIcons.Actions.MoveUp) { move(-1) })
        if (row.index < row.count - 1) tools.add(button("Move down", AllIcons.Actions.MoveDown) { move(1) })
        tools.add(button("Remove ${row.word}", AllIcons.Actions.GC) {
            host.edit("Remove ${row.word}") { document -> row.block.element?.let { DukeEdits.removeBlock(document, it) } }
        })
        panel.add(tools, BorderLayout.EAST)
        return panel
    }

    private fun note(row: NoteRow): JComponent = JBLabel(row.text).apply {
        foreground = Palette.muted
        font = JBFont.small()
        border = JBUI.Borders.empty(4, INDENT + row.depth * STEP_UNSCALED + 8, 4, 0)
    }

    /** One more block in the list: its word picked from what the list may hold, each with the families it is in. */
    private fun add(row: AddRow, host: RowHost): JComponent {
        val panel = flow()
        panel.border = JBUI.Borders.empty(6, INDENT + row.depth * STEP_UNSCALED + 8, 8, 0)
        lateinit var link: ActionLink
        link = ActionLink("+ ${row.label}") {
            JBPopupFactory.getInstance().createPopupChooserBuilder(row.choices)
                .setTitle(row.label)
                .setRenderer(SimpleListCellRenderer.create { label, value, _ ->
                    label.text = if (value.groups.isEmpty()) value.word else "${value.word}    ${value.groups.joinToString()}"
                })
                .setNamerForFiltering { it.word }
                .setItemChosenCallback { chosen ->
                    later {
                        host.edit("Add ${chosen.word}") { document ->
                            row.place.owner.element?.let { DukeEdits.addBlock(document, it, row.place.key, row.place.order, chosen.word) }
                        }
                    }
                }
                .createPopup().showUnderneathOf(link)
        }
        panel.add(link)
        return panel
    }

    // ---- small pieces ----

    /** The line the record [word] opens with where [owner]'s [key] holds it. */
    private fun firstLineOf(owner: DukeBlock, key: String, word: String): String? {
        val record = DukeRecords.recordOf(owner) ?: return null
        val type = DukeRecords.component(record, key)?.type?.let(DukeRecords::blockClass) ?: return null
        return DukeRecords.accepting(type, word, owner)?.let(Defaults::firstLine)
    }

    private fun choose(anchor: JComponent, items: List<String>, title: String, current: String?, chosen: (String) -> Unit) {
        if (items.isEmpty()) return
        val builder = JBPopupFactory.getInstance().createPopupChooserBuilder(items)
            .setTitle(title)
            .setNamerForFiltering { it }
            .setItemChosenCallback { item -> later { chosen(item) } }
        if (current != null && current in items) builder.setSelectedValue(current, true)
        builder.createPopup().showUnderneathOf(anchor)
    }

    private fun later(run: () -> Unit) = ApplicationManager.getApplication().invokeLater(run)

    private fun swapped(items: List<String>, a: Int, b: Int) = items.toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }

    private fun button(tip: String, icon: javax.swing.Icon, run: () -> Unit) = InplaceButton(tip, icon) { run() }

    private fun chip(text: String) = JBLabel(text).apply {
        font = JBFont.small()
        isOpaque = true
        background = Palette.card
        border = JBUI.Borders.compound(JBUI.Borders.customLine(Palette.cardEdge), JBUI.Borders.empty(1, 6))
    }

    private fun flow() = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }

    /** An editor that takes the width it is given, up to a sensible one. */
    private fun fill(component: JComponent): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(component, BorderLayout.WEST)
        component.maximumSize = Dimension(JBUI.scale(320), component.preferredSize.height)
    }
}
