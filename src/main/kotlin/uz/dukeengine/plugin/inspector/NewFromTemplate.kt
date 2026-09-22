package uz.dukeengine.plugin.inspector

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import uz.dukeengine.plugin.assets.DukeAssets
import uz.dukeengine.plugin.duke.DukeBlock
import uz.dukeengine.plugin.duke.DukeField
import uz.dukeengine.plugin.duke.DukeFile
import uz.dukeengine.plugin.duke.DukeFileType
import uz.dukeengine.plugin.duke.DukeRecords
import javax.swing.JComponent

/**
 * A new file from a record: the block's word, its `Name` and — for a record with one — its `DisplayName`; or a
 * copy of a block there already is under a new name. Written where the file being edited is, unless told
 * otherwise, and listed in the game's own list of files when it has one, so the game reads it without a word
 * of Java. The list is the block with a `Files` field of `.duke` paths; nothing here names a game.
 */
internal object NewFromTemplate {

    fun start(project: Project, anchor: JComponent, context: DukeFile?) {
        val words = wordsIn(project)
        if (words.isEmpty()) return
        JBPopupFactory.getInstance().createPopupChooserBuilder(words)
            .setTitle("New from Template")
            .setNamerForFiltering { it }
            .setItemChosenCallback { word -> ApplicationManager.getApplication().invokeLater { ask(project, word, context) } }
            .createPopup().showUnderneathOf(anchor)
    }

    /** Every word a block opens a project file with, and every one a template loader is told, each once. */
    private fun wordsIn(project: Project): List<String> =
        (files(project).flatMap { it.blocks }.map { it.wordText } +
            (files(project).firstOrNull()?.let { DukeRecords.registered(it).keys.map(DukeRecords::capitalized) } ?: emptyList()))
            .distinct().sorted()

    private fun files(project: Project): List<DukeFile> {
        val psi = PsiManager.getInstance(project)
        return FileTypeIndex.getFiles(DukeFileType, GlobalSearchScope.projectScope(project)).mapNotNull { psi.findFile(it) as? DukeFile }
    }

    private fun ask(project: Project, word: String, context: DukeFile?) {
        val sources = files(project).flatMap { it.blocks }.filter { it.wordText == word && it.field("Name") != null }
        // A map is a place rather than a rule: it goes in a folder of its own under maps/, where the game finds
        // it, and is not listed among the game's files. See uz.dukeengine.core.map.MapPackage.
        val map = gridOf(project, word)
        val list = if (map == null) gameList(project) else null
        val where = if (map == null) context?.virtualFile?.parent else mapsFolder(project, context)
        val dialog = NewBlockDialog(project, word, sources.map { it.field("Name")!!.valueText.orEmpty() }, where, list)
        if (!dialog.showAndGet()) return
        val source = sources.getOrNull(dialog.sourceIndex - 1)
        create(project, word, dialog.name, source, dialog.folder, list?.takeIf { dialog.listIt }, map)
    }

    /** The component that holds a block's cells, for a word whose record has one: what makes it a map. */
    private fun gridOf(project: Project, word: String): com.intellij.psi.PsiRecordComponent? {
        val shape = files(project).firstOrNull()?.let { DukeRecords.topLevel(it, word) } as? uz.dukeengine.plugin.duke.DukeShape
        return shape?.record?.recordComponents?.firstOrNull { it.hasAnnotation("uz.dukeengine.core.data.Grid") }
    }

    /** Where a game keeps its maps: {@code maps/} of the resource root the file being edited is in. */
    private fun mapsFolder(project: Project, context: DukeFile?): VirtualFile? {
        val root = context?.let { DukeAssets.rootOf(it) } ?: files(project).firstNotNullOfOrNull { DukeAssets.rootOf(it) }
        return root?.findChild("maps") ?: root ?: context?.virtualFile?.parent
    }

    /** The field that lists the game's files: `Files = [ … ]` of `.duke` paths, in the first block that has one. */
    private fun gameList(project: Project): DukeField? = files(project).flatMap { it.blocks }.mapNotNull { it.field("Files") }
        .firstOrNull { field -> field.values.any { it.unquoted.endsWith(".duke") } }

    private fun create(project: Project, word: String, name: String, source: DukeBlock?, folder: String, list: DukeField?,
                       grid: com.intellij.psi.PsiRecordComponent?) {
        val plain = plainName(name)
        val text = when {
            source != null -> copyOf(source, if (grid == null) name else plain)
            grid == null -> blank(project, word, name)
            else -> blankMap(project, word, plain, name, grid)
        }
        var created: VirtualFile? = null
        WriteCommandAction.writeCommandAction(project).withName("New $word $name").run<RuntimeException> {
            val directory = VfsUtil.createDirectoryIfMissing(if (grid == null) folder else "$folder/$plain") ?: return@run
            val file = directory.createChildData(this, if (grid == null) fileName(name) else "$plain.map")
            VfsUtil.saveText(file, text)
            created = file
            if (list != null && list.isValid) listIn(project, list, file)
        }
        created?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }

    /** The new file's path from the resource root, after the last one in the same folder, or last. */
    private fun listIn(project: Project, list: DukeField, file: VirtualFile) {
        val root = DukeAssets.rootOf(list) ?: return
        val path = VfsUtilCore.getRelativePath(file, root) ?: return
        val items = list.values.map { it.unquoted }.toMutableList()
        val folder = path.substringBeforeLast('/') + "/"
        val after = items.indexOfLast { it.startsWith(folder) }
        items.add(if (after >= 0) after + 1 else items.size, path)
        val document = PsiDocumentManager.getInstance(project).getDocument(list.containingFile) ?: return
        val owner = list.block ?: return
        DukeEdits.setValues(document, owner, list.key, emptyList(), items)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    private fun blank(project: Project, word: String, name: String): String {
        val record = files(project).firstOrNull()?.let { DukeRecords.topLevel(it, word) } as? uz.dukeengine.plugin.duke.DukeShape
        val titled = record?.record?.let { DukeRecords.component(it, "displayName") } != null
        return buildString {
            append(word).append('\n')
            append("  Name = ").append(name).append('\n')
            if (titled) append("  DisplayName = ").append(name).append('\n')
            append("End\n")
        }
    }

    /** A block's text under a new name: its `Name` and `DisplayName` lines rewritten, every other line as it was. */
    private fun copyOf(block: DukeBlock, name: String): String {
        var text = block.text
        for (key in listOf("Name", "DisplayName")) {
            text = text.replaceFirst(Regex("(?m)^(\\s*$key\\s*=\\s*).*$"), "$1" + Regex.escapeReplacement(name))
        }
        return text + "\n"
    }

    /**
     * A new map: its name, and a floor to start drawing on — a room of cells inside a border of rock, so the
     * Map tab opens on something to paint rather than on nothing.
     */
    private fun blankMap(project: Project, word: String, name: String, displayName: String,
                         grid: com.intellij.psi.PsiRecordComponent): String {
        val solid = DukeRecords.constantString(grid.getAnnotation("uz.dukeengine.core.data.Grid")?.findAttributeValue("solid"))
            ?.firstOrNull() ?: '#'
        val record = files(project).firstOrNull()?.let { DukeRecords.topLevel(it, word) } as? uz.dukeengine.plugin.duke.DukeShape
        val titled = record?.record?.let { DukeRecords.component(it, "displayName") } != null
        val rows = (0 until STARTER_ROWS).map { row ->
            if (row == 0 || row == STARTER_ROWS - 1) solid.toString().repeat(STARTER_COLUMNS)
            else solid + "0".repeat(STARTER_COLUMNS - 2) + solid
        }
        return buildString {
            append(word).append('\n')
            append("  Name = ").append(name).append('\n')
            if (titled) append("  DisplayName = ").append(displayName).append('\n')
            append("  ").append(DukeRecords.capitalized(grid.name)).append(" = [\n")
            rows.forEach { append("    \"").append(it).append("\",\n") }
            append("  ]\n")
            append("End\n")
        }
    }

    /** How big a map starts: a room somebody can walk across, and small enough to see whole. */
    private const val STARTER_COLUMNS = 32
    private const val STARTER_ROWS = 20

    /** `SkeletonHealer` as a file is named: `skeleton_healer.duke`. */
    fun fileName(name: String) = plainName(name) + ".duke"

    /** The same, as a name for a file or a folder: `SkeletonHealer` is `skeleton_healer`. */
    fun plainName(name: String) = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
}

private class NewBlockDialog(
    project: Project, private val word: String, sources: List<String>, folder: VirtualFile?, list: DukeField?,
) : DialogWrapper(project) {
    private val nameField = JBTextField(20)
    private val source = ComboBox((listOf("The template — every field at its default") + sources.map { "A copy of $it" }).toTypedArray())
    private val folderField = TextFieldWithBrowseButton()
    private val listBox = JBCheckBox("List it in ${list?.containingFile?.name ?: ""}, so the game reads it", list != null)

    val name get() = nameField.text.trim()
    val sourceIndex get() = source.selectedIndex
    val folder: String get() = folderField.text.trim()
    val listIt get() = listBox.isSelected

    init {
        title = "New $word"
        folderField.text = folder?.path.orEmpty()
        folderField.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Folder"))
        listBox.isVisible = list != null
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(nameField).align(AlignX.FILL).focused() }
        row("Start from:") { cell(source).align(AlignX.FILL) }
        row("Folder:") { cell(folderField).align(AlignX.FILL) }
        row { cell(listBox) }
    }

    override fun doValidate(): ValidationInfo? {
        if (!Regex("[A-Za-z][A-Za-z0-9_]*").matches(name)) return ValidationInfo("A name is one word: letters, digits and _", nameField)
        if (folder.isEmpty()) return ValidationInfo("Choose where it goes", folderField)
        val existing = LocalFileSystem.getInstance().findFileByPath(folder)?.findChild(NewFromTemplate.fileName(name))
        if (existing != null) return ValidationInfo("${existing.name} is there already", nameField)
        return null
    }

    override fun getPreferredFocusedComponent() = nameField
}
