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
        myFixture.addClass("package uz.duke.core.data; public @interface Relief {}")
        myFixture.addClass("package uz.duke.core.data; public @interface Link { Class<? extends Record> value(); }")
        myFixture.addClass("package game; public record Monster(String name, int colour) {}")
        myFixture.addClass("package game; public record Prop(String name) {}")
        myFixture.addClass(
            """
            package game;
            import java.util.List;
            import uz.duke.core.data.Grid;
            import uz.duke.core.data.Link;
            import uz.duke.core.data.Relief;
            public record StaticMap(String name, Cell entrance, @Grid List<String> cells, @Relief List<String> relief, List<Room> rooms,
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

    /** A stroke of the 3D view's brush is the rows it touched: cells in the grid's own, the ground in the relief's, added where there was none. */
    fun testCellsAndTheGroundAreWrittenAsTheirRows() {
        myFixture.configureByText("test.duke", MAP)
        val flat = List(5) { "0 0 0 0 0 0" }
        edit { document, map -> MapEdits.paint(document, map, listOf(Triple(1, 1, '1'), Triple(3, 2, '#'))) }
        edit { document, map -> MapEdits.shape(document, map, flat.toMutableList().also { it[1] = "0 1 2 1 0 0" }) }
        edit { document, map -> MapEdits.shape(document, map, flat.toMutableList().also { it[1] = "0 1 3 1 0 0"; it[4] = "-1 0 0 0 0 0" }) }
        myFixture.checkResult(
            MAP.replace("\"#000#\"", "\"#100#\"").replace("\"#0/0#\"", "\"#0/##\"").replace(
                "  ]\n  Rooms",
                "  ]\n  Relief = [\n" + (flat.toMutableList().also { it[1] = "0 1 3 1 0 0"; it[4] = "-1 0 0 0 0 0" })
                    .joinToString("") { "    \"$it\",\n" } + "  ]\n  Rooms",
            ),
        )
    }

    /** A map made bigger: the rows rewritten at the new size, and everything on it moved with the floor it stands on. */
    fun testAMapGrowsAndEverythingOnItMovesWithItsFloor() {
        myFixture.configureByText("test.duke", MAP)
        edit { document, map -> MapEdits.resize(project, document, map, 7, 6, 1, 1) }

        val text = myFixture.file.text
        assertTrue(
            text,
            text.contains(
                "  Cells = [\n    \"#######\",\n    \"#######\",\n    \"##000##\",\n    \"##0/0##\",\n" +
                    "    \"#######\",\n    \"#######\",\n  ]\n",
            ),
        )
        assertTrue("the way in moved with the floor", text.contains("Entrance = [2, 2]"))
        assertTrue("and so did the boss", text.contains("Boss = Brute 4 3"))
        assertTrue("and the monsters", text.contains("Ghoul 3 2"))
        assertTrue("and the rooms the floor was cut into", text.contains("2 2 3 2 0"))
    }

    /** And made smaller: the rows cut, the relief cut with them — one more number a row than there are cells. */
    fun testAMapShrinksWithItsRelief() {
        myFixture.configureByText("test.duke", MAP.replace("  Rooms = [", """  Relief = [
    "0 1 2 3 4 5",
    "1 2 3 4 5 6",
    "2 3 4 5 6 7",
    "3 4 5 6 7 8",
    "4 5 6 7 8 9",
  ]
  Rooms = ["""))
        edit { document, map -> MapEdits.resize(project, document, map, 4, 3, 0, 0) }

        val text = myFixture.file.text
        assertTrue(text, text.contains("  Cells = [\n    \"####\",\n    \"#000\",\n    \"#0/0\",\n  ]\n"))
        assertTrue(
            text,
            text.contains("  Relief = [\n    \"0 1 2 3 4\",\n    \"1 2 3 4 5\",\n    \"2 3 4 5 6\",\n    \"3 4 5 6 7\",\n  ]\n"),
        )
        assertTrue("nothing moved, so nothing on it did", text.contains("Entrance = [1, 1]") && text.contains("Ghoul 2 1"))
    }

    /** Nothing is ever cut off: a map too small for what stands on it says what would be left outside. */
    fun testAMapTooSmallSaysWhatWouldFallOutside() {
        myFixture.configureByText("test.duke", MAP)
        val map = MapModels.build(myFixture.file as DukeFile)!!

        assertEmpty(map.outside(5, 4, 0, 0))
        val outside = map.outside(3, 3, 0, 0)
        assertTrue(outside.toString(), outside.any { it.startsWith("Brute at 3, 2") })
        assertTrue("a room that no longer fits is one of them", outside.any { it.startsWith("room ") })
    }

    /** The picture that sits beside a map: a square a cell, rock dark, and what stands on the map marked on it. */
    fun testTheMapIsDrawnAsThePictureBesideIt() {
        myFixture.configureByText("test.duke", MAP)
        val map = MapModels.build(myFixture.file as DukeFile)!!
        val image = MapPreview.picture(map, 50)

        assertEquals("a cell is whole pixels, so 5 by 4 cells at 10 a cell", 50 to 40, image.width to image.height)
        assertEquals("the border is rock", MapCanvas.ROCK.rgb, image.getRGB(5, 5))
        assertTrue("and the floor inside it is not", image.getRGB(15, 15) != MapCanvas.ROCK.rgb)
        assertEquals("the way in is drawn where it stands", MapCanvas.ENTRANCE.rgb, image.getRGB(15, 15))
    }

    /** What the game would refuse the map for, said on the line that says it. */
    fun testAMapIsCheckedWhereItIsDrawn() {
        myFixture.configureByText(
            "checked.duke",
            MAP.replace("    Ghoul 2 1,", "    <error descr=\"'Ghoul' at 0, 0 is inside stone\">Ghoul 0 0</error>,"),
        )
        myFixture.checkHighlighting()

        myFixture.configureByText(
            "off.duke",
            MAP.replace(
                "    Ghoul 2 1,",
                "    <error descr=\"'Ghoul' at 9, 1 is off the edge of the map, which is 5 by 4\">Ghoul 9 1</error>,",
            ),
        )
        myFixture.checkHighlighting()
    }

    /** A row short of the others reads as stone to its right — a map that plays almost right, which is the worst kind. */
    fun testRowsOfDifferentWidthsAreFound() {
        myFixture.configureByText(
            "ragged.duke",
            MAP.replace(
                "    \"#0/0#\",",
                "    <error descr=\"The rows of 'Cells' are not all the same width: row 2 is 4 cells, and the first is 5\">\"#0/0\"</error>,",
            ),
        )
        myFixture.checkHighlighting()
    }

    /** Sharing a cell is a rule a game makes, so it is said rather than refused. */
    fun testTwoThingsOnOneCellAreWarnedAbout() {
        myFixture.configureByText(
            "shared.duke",
            MAP.replace("    Ghoul 2 1,", "    <warning descr=\"'Ghoul' at 3, 2 is standing on 'Brute'\">Ghoul 3 2</warning>,"),
        )
        myFixture.checkHighlighting()
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
