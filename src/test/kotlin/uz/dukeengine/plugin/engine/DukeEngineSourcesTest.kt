package uz.dukeengine.plugin.engine

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecordComponent
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.jetbrains.jps.model.java.JavaResourceRootType
import uz.dukeengine.plugin.assets.DukeAssets
import uz.dukeengine.plugin.duke.DukeBlock
import uz.dukeengine.plugin.duke.DukeFile
import uz.dukeengine.plugin.duke.DukeValue
import uz.dukeengine.plugin.inspector.FieldRow
import uz.dukeengine.plugin.inspector.InspectorModels
import uz.dukeengine.plugin.inspector.RecordRow
import uz.dukeengine.plugin.map.MapModels
import uz.dukeengine.plugin.map.MapScenes
import uz.dukeengine.plugin.preview.HudScenes
import uz.dukeengine.plugin.preview.PreviewScene
import uz.dukeengine.plugin.preview.PreviewScenes
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
        val added = addGameData()
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

    /** The Inspector's form of a real unit: the groups its record marks, in order, and nothing it cannot read. */
    fun testTheInspectorReadsARealUnit() {
        val mage = addGameData().single { it.name == "skeleton_mage.duke" }
        val model = InspectorModels.build(mage as DukeFile, 0, null)
        assertEquals(listOf("Identity", "Body", "Modules", "Behaviour", "Spawning", "Look", "Animation", "Skills"), model.groups.map { it.title })
        assertEmpty(model.problems.map { "${it.help.key}: ${it.problem}" })
        val look = model.groups.single { it.title == "Look" }.rows.filterIsInstance<FieldRow>()
        assertEquals("the held model sits a step in from the unit's own", listOf(0, 1), look.filter { it.help.key == "Model" }.map { it.depth })
        assertEquals("Held", model.groups.single { it.title == "Look" }.rows.filterIsInstance<RecordRow>().single().word)
    }

    /** The map editor's reading of the map the game ships: its floor, its rooms, and what stands in them, on floor. */
    fun testTheMapEditorReadsAShippedMap() {
        val map = MapModels.build(addGameData().single { it.name == "first.map" } as DukeFile)!!
        assertEquals(50 to 36, map.width to map.height)
        assertEquals(9, map.areas.size)
        assertEquals(mapOf("Entrance" to 1, "Boss" to 1, "Monsters" to 25, "Props" to 8), map.layers.associate { it.key to it.things.size })
        assertEmpty(map.layers.flatMap { it.things }.filter { map.isSolid(it.x, it.y) }.map { "${it.kind} ${it.x} ${it.y}" })
    }

    /**
     * The 3D view's reading of the map the game ships: the storey height its world gives, the theme and tone the game
     * lays it in for its depth and seed, its relief, and each thing in the model its block or its theme gives it.
     */
    fun testThe3DViewDrawsAShippedMapAsTheGameDoes() {
        val resources = myFixture.tempDirFixture.findOrCreateDir("res")
        PsiTestUtil.addSourceRoot(module, resources, JavaResourceRootType.RESOURCE)
        try {
            val shipped = File("../dungeon/src/main/resources")
            val files = shipped.walkTopDown().filter { it.extension == "duke" || it.extension == "map" }.map { file ->
                myFixture.addFileToProject("res/${file.relativeTo(shipped).invariantSeparatorsPath}", file.readText())
            }.toList()
            val scene = MapScenes.of(MapModels.build(files.single { it.name == "first.map" } as DukeFile)!!)!!
            assertEquals(resources.path, scene.root)
            for (expected in listOf(
                "\"levelHeight\":10.0",
                "\"floor\":\"models/tiles/forest/floor_rocky.gltf\",\"wall\":\"models/tiles/forest/tree_round.gltf\"",
                "\"sun\":{\"pitch\":40.0,\"yaw\":219.0,\"strength\":100.0,\"ambient\":55.0,\"colour\":16775142,\"ambientTint\":15132415}",
                "\"relief\":[[0,1,2,3,5,5,5,5,6,4,3,1,0,",
                "{\"layer\":\"Boss\",\"kind\":\"Warden\",\"x\":8,\"y\":5,",
                "\"held\":[{\"model\":\"models/monsters/axe.gltf\",\"bone\":\"handslot.r\"",
            )) assertTrue("no $expected in ${scene.json.take(300)}…", expected in scene.json)

            // A map that says its own storey height is drawn at it, as the engine lays it — both records are
            // `Layered`, and the world's is only what a map is laid at when it has not said.
            val tall = myFixture.addFileToProject(
                "res/maps/tall/tall.map",
                File(shipped, "maps/first/first.map").readText().replace("  Seed =", "  LevelHeight = 24\n  Seed ="),
            )
            val ownHeight = MapScenes.of(MapModels.build(tall as DukeFile)!!)!!
            assertTrue(ownHeight.json.take(40), "\"levelHeight\":24.0" in ownHeight.json)
        } finally {
            PsiTestUtil.removeSourceRoot(module, resources)
        }
    }

    /**
     * The preview over a skin of the hero's bar: the bar as the game's look lays it out, every skin the game paints
     * it with, its HUD's words — and the skin being edited, to point at where it goes. None over a block that is not
     * part of the bar.
     */
    fun testASkinIsPreviewedOnTheBarItPaints() {
        val resources = myFixture.tempDirFixture.findOrCreateDir("res")
        PsiTestUtil.addSourceRoot(module, resources, JavaResourceRootType.RESOURCE)
        try {
            val shipped = File("../dungeon/src/main/resources")
            val files = shipped.walkTopDown().filter { it.extension == "duke" || it.extension == "map" }.map { file ->
                myFixture.addFileToProject("res/${file.relativeTo(shipped).invariantSeparatorsPath}", file.readText())
            }.toList()
            val hud = files.single { it.name == "hud.duke" } as DukeFile
            val scene = HudScenes.of(InspectorModels.build(hud, 0, hud.text.indexOf("Name = Minimap")))!!
            assertEquals(resources.path, scene.root)
            for (expected in listOf(
                "\"focus\":\"Minimap\"",
                "\"blocks\":[\"Minimap\",\"Hero\",\"Bag\",\"Skills\",\"Depth\"]",
                "\"slabRimColour\":\"0x6B5C46\"",
                "{\"name\":\"Minimap\",\"texture\":\"ui/borders/default/border/panel-border-016.png\",\"inset\":\"16\",\"scale\":\"1.15\",\"tint\":\"0xC9A24B\"}",
            )) assertTrue("no $expected in ${scene.json.take(300)}…", expected in scene.json)

            val mage = files.single { it.name == "skeleton_mage.duke" } as DukeFile
            assertNull("a unit is no part of the bar", HudScenes.of(InspectorModels.build(mage, 0, null)))
        } finally {
            PsiTestUtil.removeSourceRoot(module, resources)
        }
    }

    /** The preview of a real unit: dressed as the client dresses it, moving by its clips, with the sounds named for it. */
    fun testThePreviewDrawsARealUnitAsTheGameDoes() {
        val resources = myFixture.copyDirectoryToProject("dungeon/src/main/resources/animations", "res/animations").parent
        PsiTestUtil.addSourceRoot(module, resources, JavaResourceRootType.RESOURCE)
        try {
            val files = listOf("animations/humanoid.duke", "units/skeleton_mage.duke", "units/skeleton.duke", "sounds/sfx.duke").associateWith {
                myFixture.addFileToProject("res/data/$it", File("../dungeon/src/main/resources/data/$it").readText()) as DukeFile
            }
            val mage = PreviewScenes.of(InspectorModels.build(files.getValue("units/skeleton_mage.duke"), 0, null))!!
            assertEquals("models/monsters/skeleton_mage.glb", mage.model)
            assertEquals(0xFF9A6A, mage.tint)
            assertEquals(listOf(PreviewScene.Carried("models/monsters/staff.gltf", "handslot.r", 1f, 0f, 0f, 0f, 0f, 0f, 0f)), mage.held)
            assertEquals(
                "its own clips, and the set's for what it leaves out",
                listOf("Idle" to "Ranged_Magic_Spellcasting_Long", "Walk" to "Walking_A", "Attack" to "Ranged_Magic_Shoot", "Death" to "Death_A"),
                mage.actions.map { it.label to it.clip },
            )
            assertContainsElements(mage.libraries, "animations/characters/general.glb", "animations/characters/magic.glb")

            val skeleton = PreviewScenes.of(InspectorModels.build(files.getValue("units/skeleton.duke"), 0, null))!!
            assertEquals(listOf("Died"), skeleton.sounds.map { it.label })
        } finally {
            PsiTestUtil.removeSourceRoot(module, resources)
        }
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

    /** A path of the kit's is found from the game's files as the classpath finds it: its own root first, then the others. */
    fun testAPathIsFoundInAnyResourceRootOfTheProject() {
        val game = myFixture.tempDirFixture.findOrCreateDir("game")
        val kit = myFixture.tempDirFixture.findOrCreateDir("kit")
        PsiTestUtil.addSourceRoot(module, game, JavaResourceRootType.RESOURCE)
        PsiTestUtil.addSourceRoot(module, kit, JavaResourceRootType.RESOURCE)
        try {
            val star = myFixture.addFileToProject("kit/kit/effects/star.png", "")
            val file = myFixture.addFileToProject("game/data/game.duke", "Game\n  Files = [kit/effects/star.png]\nEnd\n")
            val value = PsiTreeUtil.findChildrenOfType(file, DukeValue::class.java).single()
            assertEquals(star.virtualFile, DukeAssets.resolve(value, "kit/effects/star.png"))
            assertNull(DukeAssets.resolve(value, "kit/effects/nothing.png"))
            assertEquals(star, value.references.single().resolve())
        } finally {
            PsiTestUtil.removeSourceRoot(module, kit)
            PsiTestUtil.removeSourceRoot(module, game)
        }
    }

    /** Ctrl+Click on a word opens the class the engine reads it as; on a key, the component it fills. */
    fun testWordsOpenTheClassesTheEngineReadsThemAs() {
        assertEquals("uz.dukeengine.dungeon.content.Monster", classAt("Mon<caret>ster\nEnd\n"))
        assertEquals("uz.dukeengine.rts.RtsTemplate", classAt("Obj<caret>ect\nEnd\n"))
        assertEquals("uz.dukeengine.core.content.Effect", classAt("Eff<caret>ect\nEnd\n"))
        assertEquals("uz.dukeengine.core.module.MoveUpdate", classAt("Monster\n  Modules = [\n    Move<caret>Update\n    End\n  ]\nEnd\n"))
        // A script is a module named by its own class, like any other: the word opens the script.
        assertEquals("uz.dukeengine.dungeon.ai.MonsterBrain", classAt("Monster\n  Modules = [\n    Monster<caret>Brain\n    End\n  ]\nEnd\n"))
        assertEquals("uz.dukeengine.core.thing.Geometry.Cylinder", classAt("Monster\n  Geometry = Cyl<caret>inder\n    Radius = 1\n  End\nEnd\n"))
        assertEquals("uz.dukeengine.dungeon.skill.Skill", classAt("Monster\n  Skills = [\n    Sk<caret>ill\n    End\n  ]\nEnd\n"))
        assertEquals("uz.dukeengine.dungeon.content.PortraitArt", classAt("Hero\n  Portrait = Portrait<caret>Art\n    Yaw = 1\n  End\nEnd\n"))

        myFixture.configureByText("u.duke", "Hero\n  Port<caret>rait = PortraitArt\n    Yaw = 1\n  End\nEnd\n")
        assertEquals("portrait", (myFixture.elementAtCaret as PsiRecordComponent).name)
        myFixture.configureByText("u.duke", "Object\n  Modules = [\n    ActiveBody\n      Max<caret>Health = 1\n    End\n  ]\nEnd\n")
        assertEquals("maxHealth", (myFixture.elementAtCaret as PsiRecordComponent).name)
    }

    // Every one before any is checked: a unit links a set another file declares, and an effect of the kit's, as the
    // game reads them together.
    private fun addGameData(): List<PsiFile> {
        // The rules under data/, the kit's, and the maps -- folders of their own under maps/, not listed among
        // the files and read as .map rather than .duke. See uz.dukeengine.core.map.MapPackage.
        val files = listOf(File("../dungeon/src/main/resources") to "data", File("../dungeon/src/main/resources") to "maps",
            File("../kit/src/main/resources") to "kit/data")
            .flatMap { (resources, data) -> File(resources, data).walkTopDown()
                .filter { it.extension == "duke" || it.extension == "map" }.map { resources to it } }
        assertTrue("no data files found next to the plugin", files.size >= 40)
        return files.map { (resources, file) -> myFixture.addFileToProject(file.relativeTo(resources).invariantSeparatorsPath, file.readText()) }
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
