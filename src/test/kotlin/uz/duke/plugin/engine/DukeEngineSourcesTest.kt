package uz.duke.plugin.engine

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiRecordComponent
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.jetbrains.jps.model.java.JavaResourceRootType
import uz.duke.plugin.duke.DukeBlock
import java.io.File

/** Against the engine next to the plugin: its real records, registrations and game files are the spec. */
class DukeEngineSourcesTest : LightJavaCodeInsightFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.testDataPath = File("..").canonicalPath
        for (module in ENGINE_MODULES) {
            myFixture.copyDirectoryToProject("$module/src/main/java", "")
        }
    }

    /** Every data file the game ships reads as the engine reads it: not one may raise a problem. */
    fun testGameDataIsCleanAgainstTheEngine() {
        val resources = File("../dungeon/src/main/resources")
        val files = File(resources, "data").walkTopDown().filter { it.extension == "duke" }.toList()
        assertTrue("no data files found next to the plugin", files.size >= 40)
        // Every one before any is checked: a unit links a set another file declares, as the game reads them together.
        val added = files.map { myFixture.addFileToProject(it.relativeTo(resources).invariantSeparatorsPath, it.readText()) }
        assertEmpty(added.flatMap { file ->
            myFixture.configureFromExistingVirtualFile(file.virtualFile)
            myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
                .map { "${file.virtualFile.path.substringAfter("/src/")}: ${it.description} at '${it.text}'" }
        })
        // Clean because it was checked, not because the engine went unseen.
        myFixture.configureByText("check.duke", "Monster\n  Name = Brute\n  Sped = 1\n  MoveUpdat\n  End\nEnd\n")
        assertSameElements(
            myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description },
            "'Monster' has no field 'Sped'", "'Monster' holds no block 'MoveUpdat'",
        )
    }

    /**
     * A unit links what it moves by, and its clip fields are offered the clips those files hold — read
     * out of the files, so any file with clips in it works. The link opens the set; a wrong one is said.
     */
    fun testAClipIsOneOfTheClipsTheFilesItMovesByHold() {
        val resources = myFixture.copyDirectoryToProject("dungeon/src/main/resources/animations", "res/animations").parent
        PsiTestUtil.addSourceRoot(module, resources, JavaResourceRootType.RESOURCE)
        try {
            myFixture.addFileToProject("res/data/animations/humanoid.duke",
                File("../dungeon/src/main/resources/data/animations/humanoid.duke").readText())
            val unit = myFixture.addFileToProject("res/data/units/brute.duke",
                "Monster\n  Name = Brute\n  Animations = Humanoid\n  Walk = Run\nEnd\n")
            myFixture.configureFromExistingVirtualFile(unit.virtualFile)
            val text = unit.text

            val set = myFixture.file.findReferenceAt(text.indexOf("Humanoid"))?.resolve() as? DukeBlock
            assertEquals("AnimationSet", set?.wordText)

            myFixture.editor.caretModel.moveToOffset(text.indexOf("Walk = Run") + "Walk = Run".length)
            val offered = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
            assertContainsElements(offered, "Running_A", "Running_B")

            myFixture.configureByText("wrong.duke",
                "Monster\n  Name = Wrong\n  Animations = <error descr=\"No AnimationSet is called 'Humanoidd'\">Humanoidd</error>\nEnd\n")
            myFixture.checkHighlighting()
        } finally {
            PsiTestUtil.removeSourceRoot(module, resources)
        }
    }

    /** Ctrl+Click on a word opens the class the engine reads it as; on a key, the component it fills. */
    fun testWordsOpenTheClassesTheEngineReadsThemAs() {
        assertEquals("uz.duke.dungeon.content.Monster", classAt("Mon<caret>ster\nEnd\n"))
        assertEquals("uz.duke.rts.RtsTemplate", classAt("Obj<caret>ect\nEnd\n"))
        assertEquals("uz.duke.dungeon.content.Effect", classAt("Eff<caret>ect\nEnd\n"))
        assertEquals("uz.duke.core.module.MoveUpdate", classAt("Monster\n  Modules = [\n    Move<caret>Update\n    End\n  ]\nEnd\n"))
        assertEquals("uz.duke.game.script.ScriptModule", classAt("Monster\n  Modules = [\n    Script<caret>Module\n    End\n  ]\nEnd\n"))
        assertEquals("uz.duke.core.thing.Geometry.Cylinder", classAt("Monster\n  Geometry = Cyl<caret>inder\n    Radius = 1\n  End\nEnd\n"))
        assertEquals("uz.duke.dungeon.skill.Skill", classAt("Monster\n  Skills = [\n    Sk<caret>ill\n    End\n  ]\nEnd\n"))
        assertEquals("uz.duke.dungeon.content.PortraitArt", classAt("Hero\n  Portrait = Portrait<caret>Art\n    Yaw = 1\n  End\nEnd\n"))

        myFixture.configureByText("u.duke", "Hero\n  Port<caret>rait = PortraitArt\n    Yaw = 1\n  End\nEnd\n")
        assertEquals("portrait", (myFixture.elementAtCaret as PsiRecordComponent).name)
        myFixture.configureByText("u.duke", "Object\n  Modules = [\n    ActiveBody\n      Max<caret>Health = 1\n    End\n  ]\nEnd\n")
        assertEquals("maxHealth", (myFixture.elementAtCaret as PsiRecordComponent).name)
    }

    private fun classAt(text: String): String? {
        myFixture.configureByText("u.duke", text)
        return (myFixture.elementAtCaret as PsiClass).qualifiedName
    }

    private companion object {
        // The client too: a block can be read straight into its records (OrderMark), and a game's
        // record can share a name with one of them (Fog).
        val ENGINE_MODULES = listOf("core", "rts", "game", "client3d", "dungeon")
    }
}
