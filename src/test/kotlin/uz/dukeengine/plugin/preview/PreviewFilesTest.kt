package uz.dukeengine.plugin.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/** What the preview's browser is served: the viewer from the plugin, the game's files from its root, and no others. */
class PreviewFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun theViewerComesFromThePluginAndTheGameFromItsRoot() {
        val root = folder.newFolder("game").toPath()
        Files.writeString(root.resolve("a b.txt"), "the game's")
        Files.writeString(folder.newFile("secret.txt").toPath(), "not the game's")

        assertEquals("res/a b.txt", PreviewFiles.pathOf("http://duke.preview/res/a%20b.txt"))
        assertNull(PreviewFiles.pathOf("https://example.com/viewer/index.html"))
        assertNotNull(PreviewFiles.bytes("viewer/index.html", null))
        assertNotNull("three.js is taken out of its webjar when the plugin is built", PreviewFiles.bytes("viewer/three/build/three.module.js", null))
        assertEquals("the game's", String(PreviewFiles.bytes("res/a b.txt", root)!!))
        assertNull("nothing above the root", PreviewFiles.bytes("res/../secret.txt", root))
        assertEquals("a module is only run when it is served as script", "text/javascript", PreviewFiles.mime("viewer/viewer.js"))
    }

    @Test
    fun aSceneIsJsonThePageReads() {
        val scene = PreviewScene("/r", "a.glb", null, 0xFF9A6A, emptyList(), emptyList(), listOf(PreviewScene.Action("Walk", "Walking_\"A\"")), emptyList())
        assertEquals(
            """{"model":"a.glb","texture":null,"tint":"#FF9A6A","held":[],"libraries":[],"actions":[{"label":"Walk","clip":"Walking_\"A\""}],"sounds":[]}""",
            scene.json(),
        )
        assertEquals("\"a\\u2028b\"", Json.string("a\u2028b"))
    }
}
