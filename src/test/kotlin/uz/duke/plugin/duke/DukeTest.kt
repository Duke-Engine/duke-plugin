package uz.duke.plugin.duke

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiRecordComponent
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/** What `DukeText` refuses, with no engine on the classpath: the syntax alone. */
class DukeSyntaxTest : BasePlatformTestCase() {
    override fun getTestDataPath() = "src/test/testData"

    fun testWhatTheReaderRefusesIsFlaggedInItsWords() {
        myFixture.configureByText(
            "broken.duke",
            """
            |<error descr="'Speed' stands outside any block">Speed</error> = 5
            |Monster
            |  Name = A
            |  <error descr="'name' is written twice in 'Monster', first on line 3; a list is one value, [a, b]">name</error> = B
            |  Kinds = [a, <error descr="An empty item in a list">,</error> b,]
            |  Other = [a ; a comment inside a list
            |    <error descr="A comma is missing between the items of a list">b</error>]
            |  Quoted = "x; y" <error descr="Nothing may follow a value on its line">z</error>
            |  <error descr="A block opens with one word; its name goes inside it: 'Cylinder', then 'Name = Big'">Cylinder Big</error>
            |  <error descr="Expected a block's word, 'Key = value' or End">42 = x</error>
            |  Empty = []
            |  Spaced = Skeleton Mage
            |End
            |<error descr="End with no block open">End</error>
            |<error descr="'Open' has no End">Open</error>
            |  Deep = <error descr="'[' is never closed by ']'">[</error>a,
            """.trimMargin(),
        )
        myFixture.checkHighlighting()
    }

    fun testANamedHeaderIsFixedByMovingTheNameInside() {
        myFixture.configureByText("unit.duke", "Monster\n  <caret>Cylinder Big\n    Radius = 4\n  End\nEnd\n")
        myFixture.launchAction(myFixture.findSingleIntention("Move the name inside the block"))
        myFixture.checkResult("Monster\n  Cylinder\n    Name = Big\n    Radius = 4\n  End\nEnd\n")
    }

    fun testAListOverSeveralLinesIsOneValue() {
        myFixture.configureByText("content.duke", "Manifest\n  Files = [\n    a.duke,\n    \"b, c.duke\",\n  ]\nEnd\n")
        val field = (myFixture.file as DukeFile).blocks.single().fields.single()
        assertEquals(listOf("a.duke", "b, c.duke"), field.values.map { it.unquoted })
        assertNull(field.value)
    }

    fun testFolding() = myFixture.testFolding("$testDataPath/folding.duke")

    fun testStructureView() {
        myFixture.configureByText(
            "units.duke",
            """
            |Monster
            |  Name = Brute
            |  Cylinder
            |    Radius = 4
            |  End
            |  MoveUpdate
            |  End
            |End
            |Effect
            |  Name = Fire
            |End
            """.trimMargin(),
        )
        myFixture.testStructureView { component ->
            PlatformTestUtil.expandAll(component.tree)
            PlatformTestUtil.assertTreeEqual(
                component.tree,
                """
                |-units.duke
                | -Monster Brute
                |  Cylinder
                |  MoveUpdate
                | Effect Fire
                |""".trimMargin(),
            )
        }
    }
}

/** Against a few records shaped like a game's, read as `Binder` would read the blocks. */
class DukeRecordsTest : LightJavaCodeInsightFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addEngineStubs()
    }

    fun testAWellWrittenBlockIsClean() {
        myFixture.configureByText("brute.duke", BRUTE)
        myFixture.checkHighlighting()
    }

    fun testWhatTheRecordsRefuseIsFlaggedInTheEnginesWords() {
        myFixture.configureByText(
            "broken.duke",
            """
            |Monster
            |  Name = Brute
            |  <error descr="'Monster' has no field 'Sped'">Sped</error> = 1
            |  SenseRadius = <error descr="'SenseRadius' is a number, not 'far'">far</error>
            |  Flies = <error descr="'Flies' is Yes or No, not 'maybe'">maybe</error>
            |  Effect = <error descr="'Effect' is one of [STRIKE, HEAL], not 'BURN'">BURN</error>
            |  SkillDistance = <error descr="'SkillDistance' takes 2 values, not 3">[1, 2, 3]</error>
            |  Kinds = <error descr="'Kinds' is a list: write it [a, b]">a</error>
            |  Name2 = <error descr="'Name2' takes one value, not a list">[a]</error>
            |  Cylinder
            |    Radius = <error descr="'Radius' is a number, not 'x'">x</error>
            |  End
            |  <error descr="'Sphere' is written twice in 'Monster'">Sphere</error>
            |  End
            |  Armor
            |    <error descr="'ARMOR' is one of [FIRE, ICE], not 'ARMOR'">ARMOR</error> = 1
            |    <error descr="'Armor' holds entries, not blocks">Plate</error>
            |    End
            |  End
            |  <error descr="'Monster' holds no block 'Wheel'">Wheel</error>
            |  End
            |End
            |<error descr="No record is called 'Beast'">Beast</error>
            |End
            """.trimMargin(),
        )
        myFixture.checkHighlighting()
    }

    fun testAWordOpensTheClassItIsReadAs() {
        assertEquals("game.Monster", classAt("Mon<caret>ster\nEnd\n"))
        assertEquals("game.Unit", classAt("Obj<caret>ect\nEnd\n")) // the game's registration, not the loader's own
        assertEquals("uz.duke.core.module.MoveUpdate", classAt("Monster\n  Move<caret>Update\n  End\nEnd\n"))
        assertEquals("uz.duke.core.thing.Geometry.Cylinder", classAt("Monster\n  Cyl<caret>inder\n  End\nEnd\n"))
        assertEquals("game.Skill", classAt("Monster\n  Sk<caret>ill\n  End\nEnd\n"))
        myFixture.configureByText("u.duke", "Monster\n  Ar<caret>mor\n  End\nEnd\n")
        assertEquals("armor", (myFixture.elementAtCaret as PsiRecordComponent).name)
    }

    fun testAKeyOpensItsComponentAndAValueItsConstant() {
        myFixture.configureByText("u.duke", "Monster\n  MoveUpdate\n    Sp<caret>eed = 1\n  End\nEnd\n")
        val speed = myFixture.elementAtCaret as PsiRecordComponent
        assertEquals("speed", speed.name)
        assertEquals("Data", speed.containingClass?.name)
        myFixture.configureByText("u.duke", "Monster\n  Effect = HE<caret>AL\nEnd\n")
        assertEquals("HEAL", (myFixture.elementAtCaret as PsiEnumConstant).name)
        myFixture.configureByText("u.duke", "Monster\n  Armor\n    FI<caret>RE = 1\n  End\nEnd\n")
        assertEquals("FIRE", (myFixture.elementAtCaret as PsiEnumConstant).name)
    }

    fun testALineBeingBegunIsOfferedTheKeysAndBlocksLeft() {
        myFixture.configureByText("u.duke", "Monster\n  Name = Brute\n  <caret>\nEnd\n")
        myFixture.completeBasic()
        val offered = myFixture.lookupElementStrings!!
        assertContainsElements(offered, "SenseRadius", "Flies", "Effect", "SkillDistance", "Kinds", "Sphere", "Cylinder", "MoveUpdate", "Skill", "Armor")
        assertDoesntContain(offered, "Name", "Skills", "Modules", "Geometry")
    }

    fun testAfterTheEqualsSignTheValuesItsTypeTakes() {
        myFixture.configureByText("u.duke", "Monster\n  Effect = <caret>\nEnd\n")
        myFixture.completeBasic()
        assertSameElements(myFixture.lookupElementStrings!!, "STRIKE", "HEAL")
        myFixture.configureByText("u.duke", "Monster\n  Flies = <caret>\nEnd\n")
        myFixture.completeBasic()
        assertSameElements(myFixture.lookupElementStrings!!, "Yes", "No")
    }

    fun testAKeyIsCompletedWithItsEqualsSign() {
        myFixture.configureByText("u.duke", "Monster\n  Sens<caret>\nEnd\n")
        myFixture.completeBasic()
        myFixture.checkResult("Monster\n  SenseRadius = <caret>\nEnd\n")
    }

    fun testRenamingAComponentOrAModuleRenamesTheData() {
        val data = myFixture.addFileToProject("brute.duke", BRUTE)
        myFixture.renameElement(myFixture.findClass("game.Monster").recordComponents.first { it.name == "senseRadius" }, "sightRadius")
        myFixture.renameElement(myFixture.findClass("uz.duke.core.module.MoveUpdate"), "Mover")
        assertTrue(data.text, data.text.contains("\n  SightRadius = 90\n"))
        assertTrue(data.text, data.text.contains("\n  Mover\n    Speed = 10\n"))
        // Portrait-style words name a component, not the class they open: renaming the class leaves them.
        assertTrue(data.text, data.text.contains("\n  Cylinder\n"))
    }

    private fun classAt(text: String): String? {
        myFixture.configureByText("u.duke", text)
        return (myFixture.elementAtCaret as PsiClass).qualifiedName
    }

    private companion object {
        val BRUTE = """
            |; A brute, as a game writes one.
            |Monster
            |  Name = Brute
            |  SenseRadius = 90
            |  Flies = No
            |  Colour = 0xFF8800
            |  Effect = STRIKE
            |  SkillDistance = [20, 60]
            |  Kinds = [a, "b, c"]
            |  Cylinder
            |    Radius = 4
            |    Height = 12
            |  End
            |  MoveUpdate
            |    Speed = 10
            |    TurnRate = 0
            |  End
            |  Skill
            |    Key = Q
            |    Effect = heal
            |  End
            |  Skill
            |    Key = W
            |  End
            |  Armor
            |    FIRE = 0.5
            |  End
            |End
            |Object
            |  Name = Tower
            |  BuildCost = 100
            |End
            |""".trimMargin()
    }
}

private fun JavaCodeInsightTestFixture.addEngineStubs() {
    addClass("package uz.duke.core.data; public final class Binder {}")
    addClass("package uz.duke.core.module; public interface ModuleData {}")
    addClass("package uz.duke.core.module; public final class MoveUpdate { public record Data(float speed, float turnRate) implements ModuleData {} }")
    addClass(
        """
        package uz.duke.core.thing;
        public sealed interface Geometry {
            record Sphere(float radius) implements Geometry {}
            record Cylinder(float radius, float height) implements Geometry {}
        }
        """.trimIndent(),
    )
    addClass("package uz.duke.core.thing; public record ObjectTemplate(String name, Geometry geometry) {}")
    addClass(
        """
        package uz.duke.core.thing;
        public final class ThingTemplateLoader {
            public ThingTemplateLoader() { type("Object", ObjectTemplate.class); }
            public ThingTemplateLoader type(String word, Class<?> record) { return this; }
        }
        """.trimIndent(),
    )
    addClass("package game; public record Unit(String name, uz.duke.core.thing.Geometry geometry, int buildCost) {}")
    addClass("package game; class Setup { void setUp(uz.duke.core.thing.ThingTemplateLoader loader) { loader.type(\"Object\", Unit.class); } }")
    addClass("package game; public enum Effect { STRIKE, HEAL }")
    addClass("package game; public enum Element { FIRE, ICE }")
    addClass("package game; public record Band(float nearest, float furthest) {}")
    addClass("package game; public record Skill(char key, Effect effect) {}")
    addClass(
        """
        package game;
        import java.util.List;
        import java.util.Map;
        import uz.duke.core.module.ModuleData;
        import uz.duke.core.thing.Geometry;
        public record Monster(String name, String name2, float senseRadius, boolean flies, int colour, Effect effect, Band skillDistance,
                List<String> kinds, Geometry geometry, List<ModuleData> modules, List<Skill> skills, Map<Element, Float> armor) {}
        """.trimIndent(),
    )
}
