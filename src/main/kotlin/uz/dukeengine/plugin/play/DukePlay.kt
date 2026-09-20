package uz.dukeengine.plugin.play

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * The game started from the IDE, on a map when one is asked for: the Gradle project a data file belongs to, run as
 * `gradlew :dungeon:run --args=--map=src/main/resources/maps/first/first.map`, in a tab of the Run window that
 * stops it and starts it again. The map is told by its file, which the game reads from disk as it is saved — a map
 * drawn a minute ago plays before it is listed anywhere. That a game is told its map with `--map=` is all that is
 * known of it here.
 */
object DukePlay {
    fun play(project: Project, file: VirtualFile, onThisMap: Boolean) {
        // What the game reads is what is on disk: every edit made so far goes there first.
        FileDocumentManager.getInstance().saveAllDocuments()
        val command = file.fileSystem.getNioPath(file)?.let { commandFor(it, onThisMap, SystemInfo.isWindows) }
            ?: return Messages.showWarningDialog(project, "${file.name} is in no Gradle project with a wrapper to run it by.", "Play")
        val line = GeneralCommandLine(command).withWorkDirectory(Path.of(command.first()).parent.toFile())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        RunContentExecutor(project, KillableColoredProcessHandler(line))
            .withTitle(if (onThisMap) "Play ${file.nameWithoutExtension}" else "Play")
            .withRerun { play(project, file, onThisMap) }
            .withActivateToolWindow(true)
            .run()
    }

    /**
     * The command that runs the project [file] is in: the `run` task of the nearest folder up from it with a build
     * file, by the wrapper of the nearest one from there up with a settings file — the root of its build — and [file]
     * told as the map, by its path from the project, where `run` starts.
     */
    fun commandFor(file: Path, onThisMap: Boolean, windows: Boolean): List<String>? {
        val folders = generateSequence(file.parent) { it.parent }.toList()
        val project = folders.firstOrNull { folder -> BUILD.any { Files.isRegularFile(folder.resolve(it)) } } ?: return null
        val root = folders.dropWhile { it != project }.firstOrNull { folder -> SETTINGS.any { Files.isRegularFile(folder.resolve(it)) } }
            ?: return null
        val wrapper = root.resolve(if (windows) "gradlew.bat" else "gradlew").takeIf(Files::isRegularFile) ?: return null
        val path = if (project == root) "" else root.relativize(project).joinToString("") { ":$it" }
        val map = if (onThisMap) "--args=--map=" + project.relativize(file).joinToString("/") else null
        return listOfNotNull(wrapper.toString(), "$path:run", map)
    }

    private val BUILD = listOf("build.gradle.kts", "build.gradle")
    private val SETTINGS = listOf("settings.gradle.kts", "settings.gradle")
}
