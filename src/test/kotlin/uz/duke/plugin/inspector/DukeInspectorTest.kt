package uz.duke.plugin.inspector

import com.intellij.openapi.editor.Document
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import uz.duke.plugin.duke.DukeBlock
import uz.duke.plugin.duke.DukeFile

/** The Inspector against a small engine of its own: the form a record makes, and the lines each change writes. */
class DukeInspectorTest : LightJavaCodeInsightFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addClass("package uz.duke.core.data; public @interface Group { String value(); }")
        myFixture.addClass("package uz.duke.core.data; public @interface Link { Class<? extends Record> value(); }")
        myFixture.addClass("package uz.duke.core.data; public @interface Clip {}")
        myFixture.addClass("package uz.duke.core.module; public @interface ModuleGroup { String[] value(); }")
        myFixture.addClass("package uz.duke.core.module; public interface ModuleData {}")
        myFixture.addClass(
            """
            package uz.duke.core.module;
            @ModuleGroup("Movement")
            public final class MoveUpdate { public record Data(float speed, float turnRate) implements ModuleData {} }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package uz.duke.core.module;
            @ModuleGroup({"Body", "Progression"})
            public final class Growing { public record Data(float maxHealth) implements ModuleData {} }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package uz.duke.core.thing;
            public sealed interface Geometry {
                record Sphere(float radius) implements Geometry {}
                record Cylinder(float radius, float height) implements Geometry {}
            }
            """.trimIndent(),
        )
        myFixture.addClass("package game; public enum Element { FIRE, ICE }")
        myFixture.addClass("package game; public record Band(float nearest, float furthest) {}")
        myFixture.addClass("package game; public record Moves(String name, @uz.duke.core.data.Clip String walk) {}")
        myFixture.addClass(
            """
            package game;
            import java.util.List;
            import java.util.Map;
            import uz.duke.core.data.Clip;
            import uz.duke.core.data.Group;
            import uz.duke.core.data.Link;
            import uz.duke.core.module.ModuleData;
            import uz.duke.core.thing.Geometry;
            /**
             * A thing that walks.
             *
             * @param senseRadius how far it notices the hero
             * @param colour      what it is drawn as, packed {@code 0xRRGGBB}
             */
            public record Monster(@Group("Identity") String name, @Group("Body") float senseRadius, boolean flies, int colour,
                    Element element, Band keep, Geometry geometry, @Group("Modules") List<ModuleData> modules,
                    @Group("Look") @Link(Moves.class) String moves, @Clip String walk, Map<Element, Float> armor,
                    @Group("Body") int weight) {
                static final Monster DEFAULTS = new Monster("", 90f, false, 0xFFFFFF, Element.FIRE, null, null, List.of(), null, null,
                        Map.of(), 0);
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject("humanoid.duke", "Moves\n  Name = Humanoid\n  Walk = Walking_A\nEnd\n")
    }

    fun testTheFormIsTheRecordInItsGroups() {
        myFixture.configureByText(
            "brute.duke",
            """
            |Monster
            |  Name = Brute
            |  SenseRadius = 36
            |  Modules = [
            |    MoveUpdate
            |      Speed = 17
            |    End
            |  ]
            |  Moves = Humanoid
            |End
            |""".trimMargin(),
        )
        val model = build()
        assertNull(model.note)
        assertEquals("Groups in the order the record first names them, one of each", listOf("Identity", "Body", "Modules", "Look"), model.groups.map { it.title })
        assertEquals(listOf("SenseRadius", "Flies", "Colour", "Element", "Keep", "Geometry", "Weight"), fields(model, "Body").map { it.help.key })

        val sense = field(model, "SenseRadius")
        assertEquals("36", sense.written)
        assertEquals("90", sense.default)
        assertEquals("how far it notices the hero", sense.help.doc)
        assertEquals(ValueEditor.Text(numeric = true), sense.editor)

        assertEquals(ValueEditor.Check, field(model, "Flies").editor)
        assertEquals("No", field(model, "Flies").default)
        assertEquals(ValueEditor.Colour, field(model, "Colour").editor)
        assertEquals("0xFFFFFF", field(model, "Colour").default)
        assertEquals(ValueEditor.Choice(listOf("FIRE", "ICE")), field(model, "Element").editor)
        assertEquals(ValueEditor.Tuple(listOf("Nearest", "Furthest")), field(model, "Keep").editor)
        assertEquals(ValueEditor.Variant(listOf("Sphere", "Cylinder")), field(model, "Geometry").editor)
        assertEquals(ValueEditor.Link("Moves", listOf("Humanoid")), field(model, "Moves").editor)
        assertEquals(ValueEditor.Entries(listOf("FIRE", "ICE"), numeric = true), field(model, "Armor").editor)

        val walk = field(model, "Walk").editor as ValueEditor.Clip
        assertEquals("a clip it does not name is the one what it links names", "Walking_A", walk.inherited)
        assertEquals("Humanoid", walk.from)

        val modules = model.groups.single { it.title == "Modules" }.rows
        val card = modules.filterIsInstance<CardRow>().single()
        assertEquals("MoveUpdate", card.word)
        assertEquals(listOf("Movement"), card.groups)
        val speed = modules.filterIsInstance<FieldRow>().single { it.help.key == "Speed" }
        assertEquals("17", speed.written)
        assertEquals(1, speed.depth)
        assertNull(modules.filterIsInstance<FieldRow>().single { it.help.key == "TurnRate" }.written)
        val add = modules.filterIsInstance<AddRow>().single()
        assertEquals(BlockChoice("Growing", listOf("Body", "Progression")), add.choices.single { it.word == "Growing" })
    }

    fun testWhatCannotBeReadIsAProblem() {
        myFixture.configureByText("wrong.duke", "Monster\n  Name = Brute\n  SenseRadius = far\n  Moves = Nobody\nEnd\n")
        val problems = build().problems.associate { it.help.key to it.problem }
        assertEquals("'SenseRadius' is a number, not 'far'", problems["SenseRadius"])
        assertEquals("No Moves is called 'Nobody'", problems["Moves"])
    }

    fun testANewLineGoesWhereTheRecordPutsIt() {
        myFixture.configureByText("brute.duke", "Monster\n  Name = Brute\n  ; how it moves\n  Moves = Humanoid\nEnd\n")
        edit { document, block -> DukeEdits.setValue(document, block, "SenseRadius", ORDER, "40") }
        myFixture.checkResult("Monster\n  Name = Brute\n  SenseRadius = 40\n  ; how it moves\n  Moves = Humanoid\nEnd\n")

        edit { document, block -> DukeEdits.setValue(document, block, "SenseRadius", ORDER, "50") }
        edit { document, block -> DukeEdits.setValue(document, block, "Weight", ORDER, "3") }
        myFixture.checkResult("Monster\n  Name = Brute\n  SenseRadius = 50\n  ; how it moves\n  Moves = Humanoid\n  Weight = 3\nEnd\n")

        edit { document, block -> DukeEdits.removeLines(document, block.field("SenseRadius")!!) }
        edit { document, block -> DukeEdits.removeLines(document, block.field("Weight")!!) }
        myFixture.checkResult("Monster\n  Name = Brute\n  ; how it moves\n  Moves = Humanoid\nEnd\n")
    }

    fun testBlocksAreAddedMovedAndTakenOut() {
        myFixture.configureByText("brute.duke", "Monster\n  Name = Brute\n  Moves = Humanoid\nEnd\n")
        edit { document, block -> DukeEdits.addBlock(document, block, "Modules", ORDER, "MoveUpdate") }
        myFixture.checkResult("Monster\n  Name = Brute\n  Modules = [\n    MoveUpdate\n    End\n  ]\n  Moves = Humanoid\nEnd\n")

        edit { document, block -> DukeEdits.addBlock(document, block, "Modules", ORDER, "Growing") }
        edit { document, block -> DukeEdits.setValue(document, block.field("Modules")!!.blocks[1], "MaxHealth", listOf("MaxHealth"), "60") }
        edit { document, block -> DukeEdits.moveBlock(document, block.field("Modules")!!.blocks[1], -1) }
        myFixture.checkResult(
            "Monster\n  Name = Brute\n  Modules = [\n    Growing\n      MaxHealth = 60\n    End\n    MoveUpdate\n    End\n  ]\n  Moves = Humanoid\nEnd\n",
        )

        edit { document, block -> DukeEdits.removeBlock(document, block.field("Modules")!!.blocks[0]) }
        edit { document, block -> DukeEdits.removeBlock(document, block.field("Modules")!!.blocks[0]) }
        myFixture.checkResult("Monster\n  Name = Brute\n  Moves = Humanoid\nEnd\n")
    }

    fun testMapsListsAndRecordsInside() {
        myFixture.configureByText("brute.duke", "Monster\n  Name = Brute\nEnd\n")
        edit { document, block -> DukeEdits.setEntry(document, block, "Armor", ORDER, "FIRE", "0.5") }
        edit { document, block -> DukeEdits.setEntry(document, block, "Armor", ORDER, "ICE", "2") }
        edit { document, block -> DukeEdits.setValues(document, block, "Keep", ORDER, listOf("20", "60")) }
        edit { document, block -> DukeEdits.addNested(document, block, "Geometry", ORDER, "Cylinder", "Radius = 0") }
        edit { document, block -> DukeEdits.setValue(document, block.field("Geometry")!!.nested!!, "Radius", listOf("Radius", "Height"), "4") }
        edit { document, block -> DukeEdits.setValue(document, block.field("Geometry")!!.nested!!, "Height", listOf("Radius", "Height"), "12") }
        myFixture.checkResult(
            "Monster\n  Name = Brute\n  Keep = [20, 60]\n  Geometry = Cylinder\n    Radius = 4\n    Height = 12\n  End\n" +
                "  Armor\n    FIRE = 0.5\n    ICE = 2\n  End\nEnd\n",
        )

        edit { document, block -> DukeEdits.setWord(document, block.field("Geometry")!!.nested!!, "Sphere", setOf("Radius")) }
        edit { document, block -> DukeEdits.removeEntry(document, block, "Armor", "FIRE") }
        edit { document, block -> DukeEdits.removeEntry(document, block, "Armor", "ICE") }
        myFixture.checkResult("Monster\n  Name = Brute\n  Keep = [20, 60]\n  Geometry = Sphere\n    Radius = 4\n  End\nEnd\n")
    }

    fun testASoundIsForAMomentTheGameNamesSoundsFor() {
        myFixture.addClass("package game; public record Sound(String name, float gain, java.util.List<String> files) {}")
        myFixture.addFileToProject(
            "sfx.duke",
            "Sound\n  Name = died.Brute\n  Gain = 0.7\n  Files = [audio/died.ogg]\nEnd\n\n" +
                "Sound\n  Name = hurt.Ghoul\n  Files = [audio/hurt.ogg]\nEnd\n\n" +
                "Sound\n  Name = skill.Q\n  Files = [audio/q.ogg]\nEnd\n\n" +
                "Sound\n  Name = vo.kill\n  Files = [audio/kill.ogg]\nEnd\n",
        )
        myFixture.configureByText("ghoul.duke", "Monster\n  Name = Ghoul\nEnd\n")
        val moments = NewSound.momentsFor((myFixture.file as DukeFile).blocks.single())
        assertEquals("not a key's, not a word's, and not one it has", listOf("died"), moments.keys.toList())
        assertEquals("Sound\n  Name = died.Ghoul\n  Gain = 0.7\n  Files = [audio/died.ogg]\nEnd", NewSound.textFor(moments.getValue("died"), "died.Ghoul"))
    }

    fun testAValueThatWouldReadAsSomethingElseIsQuoted() {
        assertEquals("Skeleton", DukeEdits.quoted("Skeleton"))
        assertEquals("\"one room; then another\"", DukeEdits.quoted("one room; then another"))
        assertEquals("\"[not a list]\"", DukeEdits.quoted("[not a list]"))
    }

    fun testAFileIsNamedAsTheGameNamesItsFiles() {
        assertEquals("skeleton_healer.duke", NewFromTemplate.fileName("SkeletonHealer"))
        assertEquals("ghoul.duke", NewFromTemplate.fileName("Ghoul"))
    }

    private fun build(): InspectorModel = InspectorModels.build(myFixture.file as DukeFile, 0, null)

    private fun fields(model: InspectorModel, group: String) = model.groups.single { it.title == group }.rows.filterIsInstance<FieldRow>()

    private fun field(model: InspectorModel, key: String) =
        model.groups.flatMap { it.rows }.filterIsInstance<FieldRow>().first { it.help.key == key && it.depth == 0 }

    private fun edit(change: (Document, DukeBlock) -> Unit) {
        val file = myFixture.file as DukeFile
        DukeEdits.write(project, file, "test") { document -> change(document, file.blocks.first()) }
    }

    private companion object {
        val ORDER = listOf("Name", "SenseRadius", "Flies", "Colour", "Element", "Keep", "Geometry", "Modules", "Moves", "Walk", "Armor", "Weight")
    }
}
