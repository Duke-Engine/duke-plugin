package uz.duke.plugin.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/** The command Play runs: the game's own project, by its build's wrapper, told its map. */
class DukePlayTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aMapIsPlayedByTheProjectItIsIn() {
        val root = folder.root.toPath()
        write(root, "settings.gradle.kts", "gradlew.bat", "gradlew", "dungeon/build.gradle.kts")
        val map = write(root, "dungeon/src/main/resources/data/maps/first.duke")

        assertEquals(
            "the map told by its path from the project, where run starts",
            listOf(root.resolve("gradlew.bat").toString(), ":dungeon:run", "--args=--map=src/main/resources/data/maps/first.duke"),
            DukePlay.commandFor(map, true, true),
        )
        assertEquals(listOf(root.resolve("gradlew").toString(), ":dungeon:run"), DukePlay.commandFor(map, false, false))
        assertEquals("a file of the root project runs the root's", listOf(root.resolve("gradlew").toString(), ":run"),
            DukePlay.commandFor(write(root, "build.gradle.kts", "game.duke"), false, false))
    }

    @Test
    fun aFileOfNoBuildIsNotPlayed() {
        assertNull(DukePlay.commandFor(write(folder.root.toPath(), "loose.duke"), false, false))
    }

    private fun write(root: Path, vararg paths: String): Path = paths.map { path ->
        root.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, "") }
    }.last()
}
