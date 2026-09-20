package uz.duke.plugin.inspector

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import uz.duke.plugin.assets.DukeAssets
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Image
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The images a field may name, seen rather than read: a grid of them, a folder at a time — the folder of the one
 * chosen now, to begin with — and a filter by name. A border is picked by how it looks.
 */
internal object ImageGallery {
    private const val SIZE = 72

    /** Small copies of the images, by path and when the file last changed, read once. */
    private val thumbnails = ConcurrentHashMap<String, Icon>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    fun choose(anchor: JComponent, root: VirtualFile, files: List<String>, current: String?, chosen: (String) -> Unit) {
        val folders = listOf(ALL) + files.map(::folderOf).distinct().sorted()
        val folder = ComboBox(folders.toTypedArray()).apply { selectedItem = current?.let(::folderOf)?.takeIf { it in folders } ?: ALL }
        val search = SearchTextField(false).apply { textEditor.emptyText.text = "Filter by name" }
        val model = DefaultListModel<String>()
        val list = JBList(model).apply {
            layoutOrientation = JList.HORIZONTAL_WRAP
            visibleRowCount = -1
            fixedCellWidth = JBUI.scale(SIZE + 24)
            fixedCellHeight = JBUI.scale(SIZE + 30)
            cellRenderer = Tile(root) { repaint() }
        }
        fun refill() {
            val shown = files.filter { (folder.selectedItem == ALL || folderOf(it) == folder.selectedItem) && it.contains(search.text.trim(), ignoreCase = true) }
            model.clear()
            shown.forEach(model::addElement)
            current?.let { list.setSelectedValue(it, true) }
        }
        folder.addActionListener { refill() }
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refill()
            override fun removeUpdate(e: DocumentEvent) = refill()
            override fun changedUpdate(e: DocumentEvent) = refill()
        })
        refill()
        val top = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(folder, BorderLayout.WEST)
            add(search, BorderLayout.CENTER)
            border = JBUI.Borders.empty(6)
        }
        val panel = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(list).apply { border = JBUI.Borders.customLineTop(JBColor.border()) }, BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(560), JBUI.scale(420))
        }
        lateinit var popup: JBPopup
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val picked = list.selectedValue ?: return
                popup.cancel()
                chosen(picked)
            }
        })
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, search.textEditor)
            .setTitle("Choose an Image").setResizable(true).setMovable(true).setRequestFocus(true).createPopup()
        popup.showUnderneathOf(anchor)
    }

    /** The image at [path] as a small icon, or null while it is still being read — [ready] is called once it is. */
    fun thumbnail(root: VirtualFile, path: String, ready: () -> Unit, size: Int = SIZE): Icon? {
        val file = DukeAssets.find(root, path) ?: return null
        val key = "${file.path}@${file.modificationStamp}@$size"
        thumbnails[key]?.let { return it }
        // Asked again at every paint while it loads: read once.
        if (!loading.add(key)) return null
        AppExecutorUtil.getAppExecutorService().execute {
            val icon = runCatching { file.inputStream.use(ImageIO::read) }.getOrNull()?.let { iconOf(it, size) }
            if (icon != null) thumbnails[key] = icon
            loading.remove(key)
            if (icon != null) ApplicationManager.getApplication().invokeLater(ready)
        }
        return null
    }

    private fun iconOf(image: BufferedImage, wanted: Int): Icon {
        val size = JBUI.scale(wanted)
        val scale = minOf(size.toDouble() / image.width, size.toDouble() / image.height, 1.0)
        val width = maxOf(1, (image.width * scale).toInt())
        val height = maxOf(1, (image.height * scale).toInt())
        val small = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        small.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, null)
            dispose()
        }
        return Checkered(small)
    }

    private fun folderOf(path: String) = path.substringBeforeLast('/', "")

    private const val ALL = "All folders"

    /** An image over a checkerboard, so what is transparent in it shows as such. */
    private class Checkered(private val image: BufferedImage) : Icon {
        override fun getIconWidth() = image.width
        override fun getIconHeight() = image.height
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val square = JBUI.scale(6)
            for (row in 0 until (image.height + square - 1) / square) {
                for (column in 0 until (image.width + square - 1) / square) {
                    g.color = if ((row + column) % 2 == 0) LIGHT else DARK
                    g.fillRect(x + column * square, y + row * square, minOf(square, image.width - column * square), minOf(square, image.height - row * square))
                }
            }
            g.drawImage(image, x, y, null)
        }

        private companion object {
            val LIGHT = Color(0x5A5D63)
            val DARK = Color(0x46494F)
        }
    }

    /** One image of the grid: itself, and its name under it. */
    private class Tile(private val root: VirtualFile, private val ready: () -> Unit) : ListCellRenderer<String> {
        private val picture = JBLabel().apply { horizontalAlignment = SwingConstants.CENTER; verticalAlignment = SwingConstants.CENTER }
        private val caption = JBLabel().apply { horizontalAlignment = SwingConstants.CENTER; font = JBUI.Fonts.smallFont() }
        private val panel = JPanel(BorderLayout()).apply {
            add(picture, BorderLayout.CENTER)
            add(caption, BorderLayout.SOUTH)
            border = JBUI.Borders.empty(4)
        }

        override fun getListCellRendererComponent(list: JList<out String>, value: String, index: Int, selected: Boolean, focused: Boolean): Component {
            picture.icon = thumbnail(root, value, ready)
            picture.text = if (picture.icon == null) "…" else null
            caption.text = value.substringAfterLast('/')
            panel.background = if (selected) list.selectionBackground else list.background
            caption.foreground = if (selected) list.selectionForeground else list.foreground
            panel.toolTipText = value
            return panel
        }
    }
}
