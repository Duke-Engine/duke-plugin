package uz.duke.plugin.inspector

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import uz.duke.plugin.DukeBundle.message
import uz.duke.plugin.ini.DukeAssets
import uz.duke.plugin.ini.DukeIniEdits
import uz.duke.plugin.ini.DukeIniFile
import uz.duke.plugin.ini.DukeIniProject
import javax.swing.JComponent

/** File > New > Duke Unit, and the Inspector's first button. */
class DukeNewUnitAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        NewUnit.start(e.project ?: return)
    }
}

/**
 * A unit is a file: its block (`Monster Goblin`), any blocks named after it, and a line in the list
 * of the game's files. This writes all three, starting either empty or from a copy of a unit that already works.
 */
object NewUnit {
    /** What the dialog settled: a name, a unit to copy or none, and for an empty one its block type and companions. */
    class Plan(val name: String, val base: String?, val type: String, val with: List<String>)

    fun start(project: Project) {
        val index = ProgressManager.getInstance().runProcessWithProgressSynchronously(ThrowableComputable<DukeIniProject, RuntimeException> {
            ReadAction.nonBlocking<DukeIniProject> { DukeIniProject.of(project) }.inSmartMode(project).executeSynchronously()
        }, message("newunit.reading"), true, project)
        val dialog = NewUnitDialog(project, index)
        if (!dialog.showAndGet()) return
        val plan = dialog.plan()
        val folder = folderFor(index, plan)
        if (folder == null) {
            Messages.showErrorDialog(project, message("newunit.no.folder"), message("newunit.title"))
            return
        }
        val text = if (plan.base == null) fresh(index, plan) else {
            val source = index.fileOf(plan.base) ?: return
            copyOf(FileDocumentManager.getInstance().getDocument(source)?.text ?: VfsUtilCore.loadText(source), plan.base, plan.name)
        }
        var created: VirtualFile? = null
        WriteCommandAction.runWriteCommandAction(project, message("newunit.command", plan.name), null, Runnable {
            val file = folder.createChildData(this, fileName(plan.name))
            VfsUtil.saveText(file, text)
            list(project, index, file)
            created = file
        })
        created?.let {
            FileEditorManager.getInstance(project).openFile(it, true)
            ToolWindowManager.getInstance(project).getToolWindow(DukeInspectorFactory.ID)?.show()
        }
    }

    /** Beside the unit it copies, else where the units of its type are. */
    fun folderFor(index: DukeIniProject, plan: Plan): VirtualFile? =
        plan.base?.let { index.fileOf(it)?.parent } ?: index.folderOf(index.unitsOf(plan.type))

    /** `SkeletonArcher` -> `skeleton_archer.ini`, as the game's own files are named. */
    fun fileName(name: String) = name.replace(Regex("(?<=[a-z0-9])([A-Z])"), "_$1").lowercase() + ".ini"

    /**
     * An empty unit: its block, of the type chosen, with the fields units of that type all write,
     * and each chosen companion with the fields every such block writes, at the values most of them
     * have. It has no modules yet: those are the Inspector's to add.
     */
    fun fresh(index: DukeIniProject, plan: Plan): String {
        val alike = index.unitsOf(plan.type)
        val fields = listOf("DisplayName" to plan.name) + index.commonFields(alike.map { it.fields })
            .filterNot { it.first.equals("DisplayName", ignoreCase = true) }
        return buildString {
            append(DukeIniEdits.blockText("${plan.type} ${plan.name}", fields))
            for (type in plan.with) append('\n').append(DukeIniEdits.blockText("$type ${plan.name}", index.defaults(type.lowercase())))
        }
    }

    /**
     * [text] with the unit [from] renamed [to] where the name is the unit's: in headers (`DungeonMonster
     * Skeleton`, `DungeonSkill Skeleton Q`, `DungeonSound died.Skeleton`), in a prefixed module
     * (`Script:SkeletonBrain`) and as its `DisplayName`. Everything else, comments included, is copied
     * as it is: a `SkeletonMage` it mentions is another unit.
     */
    fun copyOf(text: String, from: String, to: String): String {
        val name = Regex.escape(from)
        val inHeader = Regex("(?<=[\\s.])$name(?![\\w])")
        val inModule = Regex("(?<=:)$name(?=[A-Z]|\\s|$)")
        var displayName = false
        return text.lines().joinToString("\n") { line ->
            val code = line.substringBefore(';')
            val comment = line.substring(code.length)
            when {
                HEADER.matches(code) -> inHeader.replace(code, to) + comment
                !displayName && DISPLAY_NAME.matches(code) -> {
                    displayName = true
                    code.substringBefore('=') + "= $to" + comment
                }
                else -> inModule.replace(code, to) + comment
            }
        }
    }

    /** Adds [file] to the game's list of files, after the others in its folder, written the way they are. */
    fun list(project: Project, index: DukeIniProject, file: VirtualFile) {
        val (listing, key) = index.manifest ?: return
        val psi = PsiManager.getInstance(project).findFile(listing.file) as? DukeIniFile ?: return
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(psi) ?: return
        documents.commitDocument(document)
        val block = psi.blocks.firstOrNull { it.textRange.startOffset == listing.offset } ?: return
        val entries = block.fields.filter { it.keyText.equals(key, ignoreCase = true) }
        val root = index.rootOf(listing.file)
        // Whole from the resource root, as the game's own lines are; relative to the list's folder if those are.
        val base = if (root != null && entries.any { DukeAssets.find(root, it.value) != null }) root else listing.file.parent
        val path = VfsUtilCore.getRelativePath(file, base) ?: return
        val folder = path.substringBeforeLast('/', "")
        val after = entries.lastOrNull { it.value.substringBeforeLast('/', "") == folder } ?: entries.lastOrNull() ?: return
        DukeIniEdits.addFieldAfter(document, after, after.keyText, path)
        documents.commitDocument(document)
    }

    private val HEADER = Regex("[A-Za-z_]\\w*(\\s+[^=]*)?")
    private val DISPLAY_NAME = Regex("\\s*DisplayName\\s*=.*")
}

class NewUnitDialog(project: Project, private val index: DukeIniProject) : DialogWrapper(project) {
    private val name = JBTextField(20)
    private val base = ComboBox(arrayOf(message("newunit.empty")) + index.unitNames.sorted())
    private val type = ComboBox(index.unitTypes.toTypedArray())
    private val with = index.companions.associateWith { JBCheckBox(it) }

    init {
        title = message("newunit.title")
        // A copy is its unit's type, with its unit's blocks; only an empty unit is chosen for.
        base.addActionListener { (with.values + type).forEach { it.isEnabled = base.selectedIndex == 0 } }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(message("newunit.name")) { cell(name).focused().align(AlignX.FILL) }
        row(message("newunit.from")) { cell(base).align(AlignX.FILL) }
        row(message("newunit.type")) { cell(type).align(AlignX.FILL) }
        with.values.forEachIndexed { i, box -> row(if (i == 0) message("newunit.with") else "") { cell(box) } }
    }

    override fun doValidate(): ValidationInfo? {
        val text = name.text.trim()
        if (!NAME.matches(text)) return ValidationInfo(message("newunit.bad.name"), name)
        if (text in index.unitNames) return ValidationInfo(message("newunit.taken", text), name)
        val folder = NewUnit.folderFor(index, plan())
        if (folder?.findChild(NewUnit.fileName(text)) != null) return ValidationInfo(message("newunit.exists", NewUnit.fileName(text)), name)
        return null
    }

    fun plan() = NewUnit.Plan(
        name.text.trim(),
        (base.selectedItem as? String)?.takeIf { base.selectedIndex > 0 },
        type.selectedItem as? String ?: DukeIniProject.OBJECT,
        if (base.selectedIndex > 0) emptyList() else with.filterValues { it.isSelected }.keys.toList(),
    )

    private companion object {
        val NAME = Regex("[A-Z][A-Za-z0-9_]*")
    }
}
