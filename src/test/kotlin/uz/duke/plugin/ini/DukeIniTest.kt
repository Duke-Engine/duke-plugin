package uz.duke.plugin.ini

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class DukeIniTest : BasePlatformTestCase() {
    override fun getTestDataPath() = "src/test/testData"

    /** The game's own INI file is the spec: not one line of it may raise a problem. */
    fun testGameFilesAreClean() {
        val files = File("../dungeon/src/main/resources/ini").walkTopDown().filter { it.extension == "ini" }.toList()
        assertTrue("no INI files found next to the plugin", files.isNotEmpty())
        assertEmpty(files.flatMap { file ->
            myFixture.configureByText(file.name, file.readText())
            myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).map { "${file.invariantSeparatorsPath.substringAfter("resources/")}: ${it.description} at '${it.text}'" }
        })
    }

    fun testProblems() {
        myFixture.configureByText(
            "broken.ini",
            """
            |<warning descr="Unrecognized line: expected a block header such as 'World Name'">Speed = 5</warning>
            |DungeonHero Rogue
            |  Kind = A
            |  Kind = B
            |  Holds = bow
            |  HeldIn = left
            |  Holds = quiver
            |  HeldIn = back
            |End
            |DungeonHero Knight
            |  Speed = 1
            |  speed = 2
            |  <warning descr="Unrecognized line: expected 'Key = value' or End">42 = x</warning>
            |End
            |World Dungeon
            |  Generation = Layout
            |    MapWidth = 2
            |    MapWidth = 3
            |  End
            |End
            |<error descr="End without an open block">End</error>
            |<warning descr="Block header has no name">DungeonHero</warning>
            |End
            |<error descr="'Object Orc' is not closed: missing End">Object Orc</error>
            |  Speed = 3
            |<error descr="'Object Goblin' is not closed: missing End">Object Goblin</error>
            |  <error descr="'Generation = Layout' is not closed: missing End">Generation = Layout</error>
            |    Speed = 1
            |Object Troll
            |End
            """.trimMargin(),
        )
        myFixture.checkHighlighting()
    }

    /** A block may hold sections, `Generation = Layout` to its own End: told from a field by what is indented under it. */
    fun testSectionsInsideABlock() {
        myFixture.configureByText(
            "world.ini",
            """
            |World Dungeon
            |  LevelHeight = 10
            |  Generation = Layout
            |    MapWidth = 50
            |  End
            |  Stage = Play
            |  End
            |  Tone = Forest Wooded
            |    Floor = a.obj
            |  End
            |  Name = Deep
            |End
            """.trimMargin(),
        )
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING))
        val world = (myFixture.file as DukeIniFile).blocks.single()
        assertEquals(listOf("LevelHeight", "Name"), world.fields.map { it.keyText })
        assertEquals(listOf("Generation = Layout", "Stage = Play", "Tone = Forest Wooded"), world.parts.map { it.presentableText })
        assertEquals(listOf("MapWidth"), world.parts[0].fields.map { it.keyText })
        assertEquals("world/generation", world.parts[0].sectionType)
    }

    fun testFolding() = myFixture.testFolding("$testDataPath/folding.ini")

    fun testStructureView() {
        myFixture.configureByText(
            "world.ini",
            """
            |Object Rogue
            |  DisplayName = Erika
            |  Stage = Play
            |    Speed = 27.2
            |  End
            |End
            |DungeonSkill Rogue Q
            |End
            |World Dungeon
            |  Generation = Layout
            |    MapWidth = 50
            |  End
            |End
            """.trimMargin(),
        )
        myFixture.testStructureView { component ->
            PlatformTestUtil.expandAll(component.tree)
            PlatformTestUtil.assertTreeEqual(
                component.tree,
                """
                |-world.ini
                | -Object Rogue
                |  Stage = Play
                | DungeonSkill Rogue Q
                | -World Dungeon
                |  Generation = Layout
                |""".trimMargin(),
            )
        }
    }

    fun testSkillHeaderPointsAtItsHero() {
        myFixture.addFileToProject("creatures.ini", "Object Rogue\nEnd\n")
        myFixture.configureByText(
            "dungeon.ini",
            """
            |DungeonHero Rogue
            |End
            |DungeonSkill Ro<caret>gue Q
            |  Damage = 45
            |End
            """.trimMargin(),
        )
        val reference = myFixture.getReferenceAtCaretPositionWithAssertion() as PsiPolyVariantReference
        val targets = reference.multiResolve(false).map { (it.element as DukeIniBlock).presentableText }
        assertEquals(listOf("Object Rogue", "DungeonHero Rogue"), targets)
    }

    fun testValuePointsAtBlock() {
        myFixture.configureByText(
            "dungeon.ini",
            """
            |DungeonProjectile HeavyArrow
            |End
            |DungeonSkill Rogue Q
            |  Projectile = Heavy<caret>Arrow
            |End
            """.trimMargin(),
        )
        val target = myFixture.getReferenceAtCaretPositionWithAssertion().resolve() as DukeIniBlock
        assertEquals("DungeonProjectile HeavyArrow", target.presentableText)
    }

    fun testUsagesOfABlock() {
        myFixture.configureByText(
            "dungeon.ini",
            """
            |Object Ro<caret>gue
            |End
            |DungeonSkill Rogue Q
            |End
            |DungeonRun Loop
            |  DefaultHero = Rogue
            |  Speed = 3
            |End
            """.trimMargin(),
        )
        assertEquals(2, myFixture.findUsages(myFixture.elementAtCaret).size)
    }
}
