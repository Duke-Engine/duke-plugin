package uz.dukeengine.plugin.duke

import com.intellij.codeInsight.lookup.Lookup
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
            |  Modules = [
            |    Bow
            |    End
            |    <error descr="'Modules' is a list of blocks, each closed by End, and ends with ']' on a line of its own, not 'Speed = 1'">Speed = 1</error>
            |  ]
            |  <error descr="']' with no list of blocks open">]</error>
            |End
            |Unit
            |  Modules = <error descr="'Modules = [' is never closed by ']'">[</error>
            |    Bow
            |    End
            |End
            |<error descr="End with no block open">End</error>
            |<error descr="'Open' has no End">Open</error>
            |  Deep = <error descr="'[' is never closed by ']'">[</error>a,
            """.trimMargin(),
        )
        myFixture.checkHighlighting()
    }

    /** `End,` closes its block as `End` does, and between two blocks of a list the comma is not optional. */
    fun testTheCommaBetweenTheBlocksOfAList() {
        myFixture.configureByText(
            "brute.duke",
            """
            |Monster
            |  Modules = [
            |    Bow
            |    End, ; a comment after it is still the End
            |    Sword
            |    End
            |  ]
            |  Skills = [
            |    Heal
            |    End
            |    <error descr="'Skills' separates its blocks with a comma: write 'End,' before 'Harm', or ']' if the list is done">Harm</error>
            |    End
            |  ]
            |End
            """.trimMargin(),
        )
        myFixture.checkHighlighting()
        val monster = (myFixture.file as DukeFile).blocks.single()
        assertEquals(listOf("Bow", "Sword"), monster.field("Modules")!!.blocks.map { it.wordText })
        assertEquals(listOf(true, false), monster.field("Modules")!!.blocks.map { it.hasComma })
    }

    /** A value with the line under it indented by mistake opens a block; it is flagged at the field, where the engine says it. */
    fun testALineIndentedTooDeepIsFoundWhereItIs() {
        myFixture.configureByText(
            "brute.duke",
            """
            |Monster
            |  Name = Brute
            |  Effect = <error descr="'EmberEyes' has no End; if EmberEyes is a value, the line under 'Effect = EmberEyes' is indented too deep">EmberEyes</error>
            |    ModelScale = 4.2
            |  Walk = Running_A
            |End
            """.trimMargin(),
        )
        myFixture.checkHighlighting()
        assertEquals(listOf("Name", "Effect", "Walk"), (myFixture.file as DukeFile).blocks.single().fields.map { it.key })
    }

    fun testANamedHeaderIsFixedByMovingTheNameInside() {
        myFixture.configureByText("unit.duke", "Monster\n  <caret>Portrait Big\n    Yaw = 4\n  End\nEnd\n")
        myFixture.launchAction(myFixture.findSingleIntention("Move the name inside the block"))
        myFixture.checkResult("Monster\n  Portrait\n    Name = Big\n    Yaw = 4\n  End\nEnd\n")
    }

    /** A word after `=` is a record with a body only when the lines under it are deeper; a lone `[` holds blocks only when they follow. */
    fun testWhatTheLinesUnderAFieldMakeIt() {
        myFixture.configureByText(
            "units.duke",
            """
            |Monster
            |  Geometry = Cylinder
            |    Radius = 3
            |  End
            |  Look = Fire
            |  Modules = [
            |    Bow
            |    End,
            |    MoveUpdate
            |      Speed = 4
            |    End
            |  ]
            |  Kinds = [
            |    TRAIL,
            |    GLOW
            |  ]
            |End
            """.trimMargin(),
        )
        assertEmpty(myFixture.doHighlighting())
        val monster = (myFixture.file as DukeFile).blocks.single()
        assertEquals("Cylinder", monster.field("Geometry")!!.nested!!.wordText)
        assertEquals("Fire", monster.field("Look")!!.valueText)
        assertEquals(listOf("Bow", "MoveUpdate"), monster.field("Modules")!!.blocks.map { it.wordText })
        assertEquals(listOf("TRAIL", "GLOW"), monster.field("Kinds")!!.values.map { it.unquoted })
        assertEquals(listOf("Speed"), monster.field("Modules")!!.blocks[1].fields.map { it.key })
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
            |  Geometry = Cylinder
            |    Radius = 4
            |  End
            |  Modules = [
            |    MoveUpdate
            |    End
            |  ]
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
                |  Geometry = Cylinder
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
            |  Geometry = Cylinder
            |    Radius = <error descr="'Radius' is a number, not 'x'">x</error>
            |  End
            |  Skills = <error descr="'Skills' is a list of blocks: 'Skills = [', a block for each, then ']'">Skill</error>
            |  Modules = [
            |    <error descr="'Modules' is one of [MoveUpdate], not 'Wheel'">Wheel</error>
            |    End
            |  ]
            |  Armor = [<error descr="'Armor' is one of [FIRE, ICE], not 'ARMOR'">ARMOR = 1</error>, <error descr="'Armor' holds entries written 'key = value'; 'Plate' has no '='">Plate</error>]
            |  <error descr="'Cylinder' is the value of its field: Geometry = Cylinder">Cylinder</error>
            |  End
            |  <error descr="'MoveUpdate' goes in its list: 'Modules = [', then MoveUpdate … End, then ']'">MoveUpdate</error>
            |  End
            |End
            |Object
            |  Geometry = <error descr="'Geometry' is one of [Sphere, Cylinder], not 'Cone'">Cone</error>
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
        assertEquals("uz.dukeengine.core.module.MoveUpdate", classAt("Monster\n  Modules = [\n    Move<caret>Update\n    End\n  ]\nEnd\n"))
        assertEquals("uz.dukeengine.core.thing.Geometry.Cylinder", classAt("Monster\n  Geometry = Cyl<caret>inder\n    Radius = 1\n  End\nEnd\n"))
        assertEquals("uz.dukeengine.core.thing.Geometry.Sphere", classAt("Monster\n  Geometry = Sph<caret>ere\nEnd\n"))
        assertEquals("game.Skill", classAt("Monster\n  Skills = [\n    Sk<caret>ill\n    End\n  ]\nEnd\n"))
        // A map is a field now, so its name is a key rather than a word; it opens its component all the same.
        myFixture.configureByText("u.duke", "Monster\n  Ar<caret>mor = [FIRE = 1]\nEnd\n")
        assertEquals("armor", (myFixture.elementAtCaret as PsiRecordComponent).name)
        myFixture.configureByText("u.duke", "Monster\n  Geo<caret>metry = Sphere\nEnd\n")
        assertEquals("geometry", (myFixture.elementAtCaret as PsiRecordComponent).name)
    }

    fun testAKeyOpensItsComponentAndAValueItsConstant() {
        myFixture.configureByText("u.duke", "Monster\n  Modules = [\n    MoveUpdate\n      Sp<caret>eed = 1\n    End\n  ]\nEnd\n")
        val speed = myFixture.elementAtCaret as PsiRecordComponent
        assertEquals("speed", speed.name)
        assertEquals("Data", speed.containingClass?.name)
        myFixture.configureByText("u.duke", "Monster\n  Effect = HE<caret>AL\nEnd\n")
        assertEquals("HEAL", (myFixture.elementAtCaret as PsiEnumConstant).name)
        myFixture.configureByText("u.duke", "Monster\n  Armor = [FI<caret>RE = 1]\nEnd\n")
        assertEquals("FIRE", (myFixture.elementAtCaret as PsiEnumConstant).name)
    }

    fun testALineBeingBegunIsOfferedTheKeysAndMapsLeft() {
        myFixture.configureByText("u.duke", "Monster\n  Name = Brute\n  <caret>\nEnd\n")
        myFixture.completeBasic()
        val offered = myFixture.lookupElementStrings!!
        assertContainsElements(offered, "SenseRadius", "Flies", "Effect", "SkillDistance", "Kinds", "Geometry", "Modules", "Skills", "Armor")
        assertDoesntContain(offered, "Name", "Cylinder", "MoveUpdate", "Skill")
    }

    fun testAListOfBlocksIsOfferedTheRecordsItHolds() {
        myFixture.configureByText("u.duke", "Monster\n  Modules = [\n    <caret>\n  ]\nEnd\n")
        assertEquals(listOf("MoveUpdate"), myFixture.completeBasic().map { it.lookupString })
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        myFixture.checkResult("Monster\n  Modules = [\n    MoveUpdate\n      <caret>\n    End\n  ]\nEnd\n")
    }

    fun testAfterTheEqualsSignTheValuesItsTypeTakes() {
        myFixture.configureByText("u.duke", "Monster\n  Effect = <caret>\nEnd\n")
        myFixture.completeBasic()
        assertSameElements(myFixture.lookupElementStrings!!, "STRIKE", "HEAL")
        myFixture.configureByText("u.duke", "Monster\n  Flies = <caret>\nEnd\n")
        myFixture.completeBasic()
        assertSameElements(myFixture.lookupElementStrings!!, "Yes", "No")
        myFixture.configureByText("u.duke", "Monster\n  Geometry = <caret>\nEnd\n")
        myFixture.completeBasic()
        assertSameElements(myFixture.lookupElementStrings!!, "Sphere", "Cylinder")
    }

    fun testAKeyIsCompletedWithItsEqualsSign() {
        myFixture.configureByText("u.duke", "Monster\n  Sens<caret>\nEnd\n")
        myFixture.completeBasic()
        myFixture.checkResult("Monster\n  SenseRadius = <caret>\nEnd\n")
    }

    fun testRenamingAComponentOrAModuleRenamesTheData() {
        val data = myFixture.addFileToProject("brute.duke", BRUTE)
        myFixture.renameElement(myFixture.findClass("game.Monster").recordComponents.first { it.name == "senseRadius" }, "sightRadius")
        myFixture.renameElement(myFixture.findClass("uz.dukeengine.core.module.MoveUpdate"), "Mover")
        assertTrue(data.text, data.text.contains("\n  SightRadius = 90\n"))
        assertTrue(data.text, data.text.contains("\n    Mover\n      Speed = 10\n"))
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
            |  Geometry = Cylinder
            |    Radius = 4
            |    Height = 12
            |  End
            |  Modules = [
            |    MoveUpdate
            |      Speed = 10
            |      TurnRate = 0
            |    End
            |  ]
            |  Skills = [
            |    Skill
            |      Key = Q
            |      Effect = heal
            |    End,
            |    Skill
            |      Key = W
            |    End
            |  ]
            |  Armor = [FIRE = 0.5]
            |End
            |Object
            |  Name = Tower
            |  BuildCost = 100
            |  Geometry = Sphere
            |End
            |""".trimMargin()
    }
}

private fun JavaCodeInsightTestFixture.addEngineStubs() {
    addClass("package uz.dukeengine.core.data; public final class Binder {}")
    addClass("package uz.dukeengine.core.module; public interface ModuleData {}")
    addClass("package uz.dukeengine.core.module; public final class MoveUpdate { public record Data(float speed, float turnRate) implements ModuleData {} }")
    addClass(
        """
        package uz.dukeengine.core.thing;
        public sealed interface Geometry {
            record Sphere(float radius) implements Geometry {}
            record Cylinder(float radius, float height) implements Geometry {}
        }
        """.trimIndent(),
    )
    addClass("package uz.dukeengine.core.thing; public record ObjectTemplate(String name, Geometry geometry) {}")
    addClass(
        """
        package uz.dukeengine.core.thing;
        public final class ThingTemplateLoader {
            public ThingTemplateLoader() { type("Object", ObjectTemplate.class); }
            public ThingTemplateLoader type(String word, Class<?> record) { return this; }
        }
        """.trimIndent(),
    )
    addClass("package game; public record Unit(String name, uz.dukeengine.core.thing.Geometry geometry, int buildCost) {}")
    addClass("package game; class Setup { void setUp(uz.dukeengine.core.thing.ThingTemplateLoader loader) { loader.type(\"Object\", Unit.class); } }")
    addClass("package game; public enum Effect { STRIKE, HEAL }")
    addClass("package game; public enum Element { FIRE, ICE }")
    addClass("package game; public record Band(float nearest, float furthest) {}")
    addClass("package game; public record Skill(char key, Effect effect) {}")
    addClass(
        """
        package game;
        import java.util.List;
        import java.util.Map;
        import uz.dukeengine.core.module.ModuleData;
        import uz.dukeengine.core.thing.Geometry;
        public record Monster(String name, String name2, float senseRadius, boolean flies, int colour, Effect effect, Band skillDistance,
                List<String> kinds, Geometry geometry, List<ModuleData> modules, List<Skill> skills, Map<Element, Float> armor) {}
        """.trimIndent(),
    )
}
