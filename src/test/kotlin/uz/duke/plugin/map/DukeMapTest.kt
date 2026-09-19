package uz.duke.plugin.map

import com.intellij.openapi.editor.Document
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import uz.duke.plugin.duke.DukeFile
import uz.duke.plugin.inspector.DukeEdits

/** A map read by the shape of its record, and each thing put down, moved or taken off as the line it is. */
class DukeMapTest : LightJavaCodeInsightFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addClass("package uz.duke.core.data; public @interface Grid { String solid() default \"#\"; }")
        myFixture.addClass("package uz.duke.core.data; public @interface Link { Class<? extends Record> value(); }")
        myFixture.addClass("package game; public record Monster(String name, int colour) {}")
        myFixture.addClass("package game; public record Prop(String name) {}")
        myFixture.addClass(
            """
            package game;
            import java.util.List;
            import uz.duke.core.data.Grid;
            import uz.duke.core.data.Link;
            public record StaticMap(String name, Cell entrance, @Grid List<String> cells, List<Room> rooms,
                    @Link(Monster.class) Placed boss, @Link(Monster.class) List<Placed> monsters, @Link(Prop.class) List<Placed> props) {
                public record Cell(int x, int y) {}
                public record Room(int x, int y, int width, int height, int storey) { public static Room of(String line) { return null; } }
                public record Placed(String kind, int x, int y) { public static Placed of(String line) { return null; } }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject("monsters.duke", "Monster\n  Name = Brute\nEnd\n\nMonster\n  Name = Ghoul\n  Colour = 0x00FF00\nEnd\n")
        myFixture.addFileToProject("props.duke", "Prop\n  Name = Barrel\nEnd\n")
    }

    fun testTheMapIsReadByTheShapeOfItsRecord() {
        myFixture.configureByText("test.duke", MAP)
        val map = MapModels.build(myFixture.file as DukeFile)!!
        assertEquals(5 to 4, map.width to map.height)
        assertTrue(map.isSolid(0, 0))
        assertFalse(map.isSolid(2, 2))
        assertEquals(listOf(listOf(1, 1, 3, 2)), map.areas.map { listOf(it.x, it.y, it.width, it.height) })
        assertEquals(listOf("Entrance", "Boss", "Monsters", "Props"), map.layers.map { it.key })

        val entrance = layer(map, "Entrance")
        assertTrue(entrance.single && !entrance.kinded)
        assertEquals(listOf(1 to 1), entrance.things.map { it.x to it.y })
        assertEquals(listOf("Brute", "Ghoul"), layer(map, "Boss").kinds)
        assertEquals(listOf("Ghoul"), layer(map, "Monsters").things.map { it.kind })
        assertEquals(0x00FF00, layer(map, "Monsters").colours["Ghoul"])
        assertEquals(listOf("Barrel"), layer(map, "Props").kinds)
        assertEquals("the one standing alone comes first", "Boss", map.thingsAt(3, 2).first().first.key)
    }

    fun testThingsArePutDownMovedAndTakenOff() {
        myFixture.configureByText("test.duke", MAP)
        edit { document, map -> MapEdits.place(project, document, map, layer(map, "Monsters"), "Ghoul", 1, 2) }
        edit { document, map -> MapEdits.move(document, map, layer(map, "Monsters"), layer(map, "Monsters").things[1], 3, 1) }
        assertTrue(myFixture.file.text.contains("  Monsters = [\n    Ghoul 2 1,\n    Ghoul 3 1,\n  ]\n"))

        edit { document, map -> MapEdits.place(project, document, map, layer(map, "Props"), "Barrel", 2, 1) }
        edit { document, map -> MapEdits.place(project, document, map, layer(map, "Entrance"), null, 3, 1) }
        edit { document, map -> MapEdits.remove(document, map, layer(map, "Boss"), layer(map, "Boss").things.single()) }
        myFixture.checkResult(
            MAP.replace("Entrance = [1, 1]", "Entrance = [3, 1]")
                .replace("  Boss = Brute 3 2\n  Monsters = [\n    Ghoul 2 1,\n  ]\n", "  Props = [Barrel 2 1]\n"),
        )
    }

    fun testAThingNamesAKindOfItsLink() {
        myFixture.configureByText("wrong.duke", MAP.replace("    Ghoul 2 1,", "    <error descr=\"No Monster is called 'Nobody'\">Nobody 2 1</error>,"))
        myFixture.checkHighlighting()
    }

    private fun edit(change: (Document, MapModel) -> Unit) {
        val file = myFixture.file as DukeFile
        val map = MapModels.build(file)!!
        DukeEdits.write(project, file, "test") { document -> change(document, map) }
    }

    private fun layer(map: MapModel, key: String) = map.layers.single { it.key == key }

    private companion object {
        val MAP = """
            |StaticMap
            |  Name = test
            |  Entrance = [1, 1]
            |  Cells = [
            |    "#####",
            |    "#000#",
            |    "#0/0#",
            |    "#####",
            |  ]
            |  Rooms = [
            |    1 1 3 2 0,
            |  ]
            |  Boss = Brute 3 2
            |  Monsters = [
            |    Ghoul 2 1,
            |  ]
            |End
            |""".trimMargin()
    }
}
