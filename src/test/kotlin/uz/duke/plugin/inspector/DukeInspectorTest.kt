package uz.duke.plugin.inspector

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import uz.duke.plugin.ini.DukeClips
import uz.duke.plugin.ini.DukeIniEdits
import uz.duke.plugin.ini.DukeIniFile
import uz.duke.plugin.ini.DukeIniProject
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the Inspector reads off a project's files and writes back into them. The window itself is checked by eye. */
class DukeInspectorTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "settings/monsters.ini",
            """
            |DungeonMonster Skeleton
            |  Weight = 10
            |  Look = Fire
            |End
            |DungeonSound died.Skeleton
            |  Channel = Effects
            |  File = bone.ogg
            |End
            |""".trimMargin(),
        )
        myFixture.addFileToProject(
            "settings/heroes.ini",
            """
            |DungeonHero Rogue
            |  Title = Archer
            |End
            |DungeonSkill Rogue Q
            |  Look = Ice
            |  Damage = 5
            |End
            |DungeonSound hurt.Hero
            |  Channel = Effects
            |  File = ouch.ogg
            |End
            |""".trimMargin(),
        )
        myFixture.addFileToProject("settings/effects.ini", "DungeonEffect Fire\nEnd\nDungeonEffect Ice\nEnd\n")
    }

    fun testWhatAFieldNamesIsReadFromTheFiles() {
        val index = DukeIniProject.of(project)
        assertEquals("DungeonEffect", index.referenceOf("dungeonskill", "look"))
        assertEquals("DungeonEffect", index.referenceOf("dungeonmonster", "look"))
        assertNull(index.referenceOf("dungeonskill", "damage"))
        assertEquals(listOf("Channel" to "Effects", "File" to "ouch.ogg"), index.defaults("dungeonsound"))
    }

    fun testAFieldThatNamesABlockIsPickedFromTheBlocks() {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("settings/heroes.ini")) as DukeIniFile
        val look = InspectorModels.build(file).sections.first { it.title == "DungeonSkill Rogue Q" }.fields.first { it.key == "Look" }
        assertEquals(ValueEditor.Choice(listOf("Fire", "Ice"), true), look.editor)
    }

    fun testEditsLeaveTheRestOfTheFileAlone() {
        val file = myFixture.configureByText(
            "world.ini", "World Dungeon\n  Speed = 1 ; fast\n  Generation = Layout\n    MapWidth = 10\n  End\nEnd\n",
        ) as DukeIniFile
        write { DukeIniEdits.setValue(it, file.blocks.single().fields.single(), "2") }
        assertEquals("World Dungeon\n  Speed = 2 ; fast\n  Generation = Layout\n    MapWidth = 10\n  End\nEnd\n", file.text)

        write { DukeIniEdits.addField(it, file.blocks.single(), "Scale", "1.5") }
        write { DukeIniEdits.addField(it, file.blocks.single().parts.single(), "MapHeight", "8") }
        write { DukeIniEdits.addBlock(it, "DungeonMonster Goblin", listOf("Weight" to "5")) }
        write { DukeIniEdits.remove(it, file.blocks.first().fields.first()) }
        assertEquals(
            """
            |World Dungeon
            |  Generation = Layout
            |    MapWidth = 10
            |    MapHeight = 8
            |  End
            |  Scale = 1.5
            |End
            |
            |DungeonMonster Goblin
            |  Weight = 5
            |End
            |""".trimMargin(),
            file.text,
        )
    }

    /** A block's sections are shown under it, each with its own fields, and a new one gets its own End. */
    fun testSectionsAreShownUnderTheirBlockAndAdded() {
        val file = myFixture.configureByText("world.ini", "World Dungeon\n  LevelHeight = 10\n  Generation = Layout\n    MapWidth = 50\n  End\nEnd\n") as DukeIniFile
        val world = InspectorModels.build(file).sections.single()
        assertEquals(listOf("LevelHeight"), world.fields.map { it.key })
        assertEquals(listOf("Generation = Layout"), world.parts.map { it.title })
        assertEquals(listOf("MapWidth"), world.parts.single().fields.map { it.key })
        assertEquals("Layout", DukeIniProject.of(project).sectionName("world/generation"))

        write { DukeIniEdits.addSection(it, file.blocks.single(), "Stage", "Play", listOf("Name" to "Crypt")) }
        assertEquals(
            "World Dungeon\n  LevelHeight = 10\n  Generation = Layout\n    MapWidth = 50\n  End\n  Stage = Play\n    Name = Crypt\n  End\nEnd\n",
            file.text,
        )
        assertEquals(listOf("Generation = Layout", "Stage = Play"), file.blocks.single().parts.map { it.presentableText })
    }

    fun testClipNamesAreReadFromTheModelsHeader() {
        val json = """{"asset":{"version":"2.0"},"animations":[{"name":"Idle","channels":[{"sampler":0,"target":{"node":1}}],""" +
            """"samplers":[{"input":0}]},{"channels":[],"name":"Attack_1"}],"nodes":[{"name":"Hips"}]}"""
        val bytes = json.toByteArray()
        val glb = ByteBuffer.allocate(20 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46546C67).putInt(2).putInt(20 + bytes.size).putInt(bytes.size).putInt(0x4E4F534A).put(bytes).array()
        assertEquals(listOf("Idle", "Attack_1"), DukeClips.animationNames(DukeClips.glbJson(ByteArrayInputStream(glb))!!))
    }

    private fun write(edit: (Document) -> Unit) = WriteCommandAction.runWriteCommandAction(project) {
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(myFixture.file)!!
        documents.commitDocument(document)
        edit(document)
        documents.commitDocument(document)
    }
}
