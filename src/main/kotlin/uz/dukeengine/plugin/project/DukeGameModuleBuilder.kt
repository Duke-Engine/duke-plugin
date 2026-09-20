package uz.dukeengine.plugin.project

import com.intellij.icons.AllIcons
import com.intellij.ide.util.projectWizard.ModuleBuilder
import com.intellij.ide.util.projectWizard.ModuleWizardStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JComponent

private val LOG = logger<DukeGameModuleBuilder>()

/**
 * **File → New → Project → Duke Game**: a game that already runs.
 *
 * <p>The point of it is the first five minutes. Somebody who has just found the engine should not have to work
 * out which modules to depend on, which blocks a unit is made of, or what the smallest `Main` looks like — they
 * should press Run, see two things standing on a field, and start changing numbers. Everything the wizard writes
 * is in {@link GameTemplate}, which is plain text and has a test of its own.
 */
class DukeGameModuleBuilder : ModuleBuilder() {

    var gameName: String = "My Duke Game"
    var enginePath: String = ""

    override fun getModuleType(): ModuleType<*> = JavaModuleType.getModuleType()

    override fun getBuilderId() = "duke.game"

    override fun getPresentableName() = "Duke Game"

    override fun getGroupName() = "Duke"

    override fun getDescription() =
        "A game on Duke Engine: two units, a light and a camera, in data files the Duke Engine plugin edits. " +
            "It runs as soon as it is made."

    override fun getNodeIcon(): Icon = AllIcons.Nodes.Deploy

    override fun setupRootModel(model: ModifiableRootModel) {
        val root = contentEntryPath ?: return
        val folder = Path.of(root)
        val name = gameName.ifBlank { File(root).name }
        val engine = enginePath.trim().takeIf { it.isNotEmpty() && Files.isDirectory(Path.of(it)) }
        if (enginePath.isNotBlank() && engine == null) {
            LOG.warn("the engine path '$enginePath' is not a folder: the game is written without includeBuild")
        }
        for ((path, text) in GameTemplate.of(name, GameTemplate.packageOf(name), engine)) {
            val file = folder.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        val here = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(folder) ?: return
        val entry = model.addContentEntry(here)
        here.findFileByRelativePath("src/main/java")?.let { entry.addSourceFolder(it, false) }
        here.findFileByRelativePath("src/main/resources")?.let { entry.addSourceFolder(it, false, "") }
    }

    override fun getCustomOptionsStep(context: WizardContext, parentDisposable: Disposable): ModuleWizardStep =
        Step(this, context)

    /** What the wizard asks: what the game is called, and where the engine it is built against lives. */
    private class Step(private val builder: DukeGameModuleBuilder, context: WizardContext) : ModuleWizardStep() {

        init {
            builder.enginePath = beside(context.projectFileDirectory)
        }

        override fun getComponent(): JComponent = panel {
            row("Game name:") {
                textField().bindText(builder::gameName)
            }
            row("Duke Engine folder:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        .withTitle("Duke Engine Checkout"),
                ).bindText(builder::enginePath)
            }
            row {
                comment(
                    "The engine is not published yet, so the game is built against a checkout of it: the folder " +
                        "holding <code>settings.gradle.kts</code> with <code>core</code>, <code>rts</code> and the " +
                        "rest in it. Leave it empty and the game is written anyway — its dependencies will not " +
                        "resolve until the engine is published or the folder is filled in.",
                )
            }
        }

        override fun updateDataModel() {
            // Bound directly to the builder by the panel above.
        }
    }

    private companion object {
        /**
         * A guess at where the engine is: a folder called duke-engine beside the one the project is being made
         * in. Wrong as often as not, and harmless when it is — the field is there to be corrected.
         */
        fun beside(where: String?): String {
            val parent = where?.let { runCatching { Path.of(it).parent }.getOrNull() } ?: return ""
            val guess = parent.resolve("duke-engine")
            return if (Files.isRegularFile(guess.resolve("settings.gradle.kts"))) guess.toString() else ""
        }
    }
}
