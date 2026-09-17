package uz.duke.plugin.ini

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class DukeIniTest : BasePlatformTestCase() {
    override fun getTestDataPath() = "src/test/testData"

    /** The game's own files are the spec: not one of them may raise a problem. */
    fun testGameFilesAreClean() {
        val files = File("../dungeon/src/main/resources/ini").listFiles { f -> f.extension == "ini" }.orEmpty()
        assertTrue("no INI files found next to the plugin", files.size >= 5)
        for (file in files) {
            myFixture.configureByText(file.name, file.readText())
            assertEmpty(myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).map { "${file.name}: ${it.description} at '${it.text}'" })
        }
    }

    fun testProblems() {
        myFixture.configureByText(
            "broken.ini",
            """
            |<warning descr="Unrecognized line: expected a block header such as 'Object Name'">Speed = 5</warning>
            |DungeonHero Rogue
            |  Kind = A
            |  Kind = B
            |  Holds = bow
            |  HeldIn = left
            |  HeldRoll = 180
            |  Holds = quiver
            |  HeldIn = back
            |  HeldRoll = 12
            |  Library = a
            |  Library = b
            |  Library = c
            |End
            |DungeonHero Knight
            |  Kind = C
            |  Kind = D
            |  Holds = sword
            |  HeldIn = right
            |  Holds = shield
            |  HeldIn = left
            |  Speed = 1
            |  <warning descr="Key 'speed' is already set in this block on line 22">speed = 2</warning>
            |  <warning descr="Unrecognized line: expected 'Key = value' or End">42 = x</warning>
            |End
            |Object Hero
            |  Update = MoveUpdate Tag
            |    TurnRate = 0
            |    Speed = 2
            |    <warning descr="Key 'Speed' is already set in this block on line 29">Speed = 3</warning>
            |  End
            |End
            |<error descr="End without an open block">End</error>
            |<warning descr="Block header has no name">DungeonHero</warning>
            |End
            |<error descr="'Object Orc' is not closed: missing End">Object Orc</error>
            |  Speed = 3
            |<error descr="'Object Goblin' is not closed: missing End">Object Goblin</error>
            |  <error descr="'Update = MoveUpdate Tag' is not closed: missing End">Update = MoveUpdate Tag</error>
            |    Speed = 1
            |Object Troll
            |End
            """.trimMargin(),
        )
        myFixture.checkHighlighting()
    }

    fun testFolding() = myFixture.testFolding("$testDataPath/folding.ini")

    fun testStructureView() {
        myFixture.configureByText(
            "units.ini",
            """
            |Object Rogue
            |  DisplayName = Erika
            |  Update = MoveUpdate Tag
            |    Speed = 27.2
            |  End
            |  Behavior = ExperienceModule Tag
            |  End
            |End
            |DungeonSkill Rogue Q
            |End
            """.trimMargin(),
        )
        myFixture.testStructureView { component ->
            PlatformTestUtil.expandAll(component.tree)
            PlatformTestUtil.assertTreeEqual(
                component.tree,
                """
                |-units.ini
                | -Object Rogue
                |  Update = MoveUpdate Tag
                |  Behavior = ExperienceModule Tag
                | DungeonSkill Rogue Q
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
