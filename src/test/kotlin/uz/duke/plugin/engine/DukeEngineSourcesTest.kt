package uz.duke.plugin.engine

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiRecordComponent
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
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
        val files = File("../dungeon/src/main/resources/data").walkTopDown().filter { it.extension == "duke" }.toList()
        assertTrue("no data files found next to the plugin", files.size >= 40)
        assertEmpty(files.flatMap { file ->
            myFixture.configureByText(file.name, file.readText())
            myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
                .map { "${file.invariantSeparatorsPath.substringAfter("resources/")}: ${it.description} at '${it.text}'" }
        })
        // Clean because it was checked, not because the engine went unseen.
        myFixture.configureByText("check.duke", "Monster\n  Name = Brute\n  Sped = 1\n  MoveUpdat\n  End\nEnd\n")
        assertSameElements(
            myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description },
            "'Monster' has no field 'Sped'", "'Monster' holds no block 'MoveUpdat'",
        )
    }

    /** The world's INI file is clean too, and its sections are read off the settings' code. */
    fun testWorldSettingsAreCleanAndTheirSchemaIsTheGames() {
        val world = File("../dungeon/src/main/resources/ini/dungeon.ini")
        myFixture.configureByText(world.name, world.readText())
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).map { "${it.description} at '${it.text}'" })

        val schema = DukeSchemas.of(myFixture.file)!!
        val block = schema.block("World")!!
        assertTrue("too few sections to be a scan", block.fields.count { it.section != null } >= 30)
        assertEquals(FieldKind.REAL, block.field("LevelHeight")!!.kind)
        assertNull(block.field("MapWidth")) // Generation's, not the world's
        assertEquals(FieldKind.INTEGER, block.field("Generation")!!.section!!.field("MapWidth")!!.kind)
        assertEquals(FieldKind.REAL, block.field("StatBlock")!!.section!!.field("FigureIcon")!!.kind)
        val item = block.field("LootItem")!!
        assertTrue("a section written once per item", item.list)
        assertEquals(FieldKind.ENUM, item.section!!.field("Kind")!!.kind)
        assertEquals(2, block.field("Tone")!!.section!!.names) // Tone = Forest Wooded
        assertNull("a section is not a block of its own", schema.block("LootItem"))
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
        val ENGINE_MODULES = listOf("core", "rts", "game", "dungeon")
    }
}
