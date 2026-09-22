package uz.dukeengine.plugin.engine

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.vfs.VirtualFile
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
import uz.dukeengine.plugin.map.MapModels
import uz.dukeengine.plugin.map.MapScenes
import uz.dukeengine.plugin.preview.HudScenes
import uz.dukeengine.plugin.preview.PreviewScene
import uz.dukeengine.plugin.preview.PreviewScenes
import java.io.File

/**
 * Against the engine's real records and the plugin's own game: the records are the spec, and the game is
 * what they are written into. Both are read off the disk rather than written inline — the engine because
 * the plugin mirrors it, the game because a file the checks pass over is a file a person can also open.
 */
class DukeEngineSourcesTest : LightJavaCodeInsightFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.testDataPath = Repos.engine.canonicalPath
        for (module in ENGINE_MODULES) {
            myFixture.copyDirectoryToProject("$module/src/main/java", "")
        }
        // The game's own records: what its data files are read as, and the only part of it that is Java.
        myFixture.testDataPath = Repos.game.canonicalPath
        myFixture.copyDirectoryToProject("java", "")
        myFixture.testDataPath = Repos.engine.canonicalPath
    }

    /** Every data file the game is made of reads as the engine reads it: not one may raise a problem. */
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

    /** The two forms 0.3.0 brought, over the files themselves: a list's comma, and a map as its entries. */
    fun testTheFormsOfTheCurrentEngineReadAsTheEngineReadsThem() {
        addGameData()
        myFixture.configureByText(
            "forms.duke",
            """
            |Monster
            |  Name = Brute
            |  Armor = [NORMAL = 2.0, FLAME = 1.5]
            |  Modules = [
            |    ActiveBody
            |      MaxHealth = 10
            |    End,
            |    MoveUpdate
            |    End
            |  ]
            |End
            |""".trimMargin(),
        )
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).map { "${it.description} at '${it.text}'" })

        myFixture.configureByText(
            "old.duke",
            """
            |Monster
            |  Name = Brute
            |  <error descr="'Armor' is a map: write it Armor = [key = value, key = value]">Armor</error>
            |    NORMAL = 2.0
            |  End
            |  Modules = [
            |    ActiveBody
            |    End
            |    <error descr="'Modules' separates its blocks with a comma: write 'End,' before 'MoveUpdate', or ']' if the list is done">MoveUpdate</error>
            |    End
            |  ]
            |End
            |""".trimMargin(),
        )
        myFixture.checkHighlighting()
    }

    /** The Inspector's form of a real unit: the groups its record marks, in order, and nothing it cannot read. */
    fun testTheInspectorReadsARealUnit() {
        val mage = addGameData().single { it.name == "skeleton_mage.duke" }
        val model = InspectorModels.build(mage as DukeFile, 0, null)
        assertEquals(listOf("Identity", "Body", "Modules", "Behaviour", "Look", "Animation", "Skills"), model.groups.map { it.title })
        assertEmpty(model.problems.map { "${it.help.key}: ${it.problem}" })
        val look = model.groups.single { it.title == "Look" }.rows.filterIsInstance<FieldRow>()
        assertEquals("the held model sits a step in from the unit's own", listOf(0, 1), look.filter { it.help.key == "Model" }.map { it.depth })
        val armor = model.groups.single { it.title == "Body" }.rows.filterIsInstance<FieldRow>().single { it.help.key == "Armor" }
        assertEquals("a map is its entries, read out of the list", listOf("ARMOR_PIERCING = 1.5", "FLAME = 0.5"), armor.items)
    }

    /** The map editor's reading of the game's map: its floor, its rooms, and what stands in them, on floor. */
    fun testTheMapEditorReadsAShippedMap() {
        val map = MapModels.build(addGameData().single { it.name == "first.map" } as DukeFile)!!
        assertEquals(8 to 6, map.width to map.height)
        assertEquals(1, map.areas.size)
        assertEquals(mapOf("Entrance" to 1, "Monsters" to 2, "Props" to 1), map.layers.associate { it.key to it.things.size })
        assertEmpty(map.layers.flatMap { it.things }.filter { map.isSolid(it.x, it.y) }.map { "${it.kind} ${it.x} ${it.y}" })
    }

    /**
     * The 3D view's reading of that map: the storey height its world gives, the theme and tone the game lays it in
     * for its seed, its relief, and each thing in the model its block gives it.
     */
    fun testThe3DViewDrawsAShippedMapAsTheGameDoes() {
        withGameResources { resources, files ->
            val scene = MapScenes.of(MapModels.build(files.single { it.name == "first.map" } as DukeFile)!!)!!
            assertEquals(resources.path, scene.root)
            for (expected in listOf(
                "\"levelHeight\":10.0",
                // 0xFFFAE6 and 0xE6F3FF, as the file writes them.
                "\"sun\":{\"pitch\":40.0,\"yaw\":219.0,\"strength\":100.0,\"ambient\":55.0,\"colour\":16775910,\"ambientTint\":15135743}",
                "\"relief\":[[0,0,0,0,0,0,0,0,0],[0,0,0,1,1,1,0,0,0]",
                "{\"layer\":\"Monsters\",\"kind\":\"Skeleton\",\"x\":2,\"y\":2,",
                "\"held\":[{\"model\":\"models/monsters/axe.gltf\",\"bone\":\"handslot.r\"",
            )) assertTrue("no $expected in ${scene.json.take(400)}…", expected in scene.json)
            // Which of the theme's tones is the seed's business; that it is one of them is the file's.
            assertTrue(scene.json.take(400), TONES.any { it in scene.json })

            // A map that says its own storey height is drawn at it, as the engine lays it — both records are
            // `Layered`, and the world's is only what a map is laid at when it has not said.
            val tall = myFixture.addFileToProject(
                "res/maps/tall/tall.map",
                File(Repos.game, "res/maps/first/first.map").readText().replace("  Seed =", "  LevelHeight = 24\n  Seed ="),
            )
            val ownHeight = MapScenes.of(MapModels.build(tall as DukeFile)!!)!!
            assertTrue(ownHeight.json.take(40), "\"levelHeight\":24.0" in ownHeight.json)
        }
    }

    /**
     * The preview over a skin of the hero's bar: the bar as the client lays it out, every skin the game paints it
     * with, its HUD's words — and the skin being edited, to point at where it goes. None over a block that is not
     * part of the bar.
     */
    fun testASkinIsPreviewedOnTheBarItPaints() {
        withGameResources { resources, files ->
            val hud = files.single { it.name == "hud.duke" } as DukeFile
            val scene = HudScenes.of(InspectorModels.build(hud, 0, hud.text.indexOf("Name = Minimap")))!!
            assertEquals(resources.path, scene.root)
            for (expected in listOf(
                "\"focus\":\"Minimap\"",
                "{\"name\":\"Minimap\",\"texture\":\"ui/borders/default/border/panel-border-016.png\",\"inset\":\"16\",\"scale\":\"1.15\",\"tint\":\"0xC9A24B\"}",
                "\"skillsWord\":\"Skills\"",
            )) assertTrue("no $expected in ${scene.json.take(400)}…", expected in scene.json)

            val mage = files.single { it.name == "skeleton_mage.duke" } as DukeFile
            assertNull("a unit is no part of the bar", HudScenes.of(InspectorModels.build(mage, 0, null)))
        }
    }

    /** The preview of a real unit: dressed as the client dresses it, moving by its clips, with the sounds named for it. */
    fun testThePreviewDrawsARealUnitAsTheGameDoes() {
        withGameResources { _, files ->
            val mage = PreviewScenes.of(InspectorModels.build(files.single { it.name == "skeleton_mage.duke" } as DukeFile, 0, null))!!
            assertEquals("models/monsters/skeleton_mage.glb", mage.model)
            assertEquals(0xFF9A6A, mage.tint)
            assertEquals(listOf(PreviewScene.Carried("models/monsters/staff.gltf", "handslot.r", 1f, 0f, 0f, 0f, 0f, 0f, 0f)), mage.held)
            assertEquals(
                "its own clips, and the set's for what it leaves out",
                listOf("Idle" to "Spellcast_Long", "Walk" to "Walking_A", "Attack" to "Spellcast_Shoot", "Death" to "Death_A"),
                mage.actions.map { it.label to it.clip },
            )
            assertContainsElements(mage.libraries, "animations/characters/general.gltf", "animations/characters/magic.gltf")

            val skeleton = PreviewScenes.of(InspectorModels.build(files.single { it.name == "skeleton.duke" } as DukeFile, 0, null))!!
            assertEquals(listOf("Died", "Hurt"), skeleton.sounds.map { it.label })
        }
    }

    /**
     * A unit links what it moves by, and its clip fields are offered the clips those files hold — read out of the
     * files, so any file with clips in it works. The link opens the set; a wrong one is said.
     */
    fun testAClipIsOneOfTheClipsTheFilesItMovesByHold() {
        val resources = copyGameAnimations()
        PsiTestUtil.addSourceRoot(module, resources, JavaResourceRootType.RESOURCE)
        try {
            myFixture.addFileToProject("res/data/animations/humanoid.duke",
                File(Repos.game, "res/data/animations/humanoid.duke").readText())
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
        assertEquals("uz.dukeengine.fixture.Monster", classAt("Mon<caret>ster\nEnd\n"))
        assertEquals("uz.dukeengine.rts.RtsTemplate", classAt("Obj<caret>ect\nEnd\n"))
        assertEquals("uz.dukeengine.core.content.Effect", classAt("Eff<caret>ect\nEnd\n"))
        assertEquals("uz.dukeengine.core.module.MoveUpdate", classAt("Monster\n  Modules = [\n    Move<caret>Update\n    End\n  ]\nEnd\n"))
        // A script is a module named by its own class, like any other: the word opens the script.
        assertEquals("uz.dukeengine.fixture.Brain", classAt("Monster\n  Modules = [\n    Bra<caret>in\n    End\n  ]\nEnd\n"))
        assertEquals("uz.dukeengine.core.thing.Geometry.Cylinder", classAt("Monster\n  Geometry = Cyl<caret>inder\n    Radius = 1\n  End\nEnd\n"))
        assertEquals("uz.dukeengine.fixture.Skill", classAt("Monster\n  Skills = [\n    Sk<caret>ill\n    End\n  ]\nEnd\n"))
        assertEquals("uz.dukeengine.fixture.PortraitArt", classAt("Monster\n  Portrait = Portrait<caret>Art\n    Yaw = 1\n  End\nEnd\n"))

        myFixture.configureByText("u.duke", "Monster\n  Port<caret>rait = PortraitArt\n    Yaw = 1\n  End\nEnd\n")
        assertEquals("portrait", (myFixture.elementAtCaret as PsiRecordComponent).name)
        myFixture.configureByText("u.duke", "Object\n  Modules = [\n    ActiveBody\n      Max<caret>Health = 1\n    End\n  ]\nEnd\n")
        assertEquals("maxHealth", (myFixture.elementAtCaret as PsiRecordComponent).name)
    }

    // Every one before any is checked: a unit links a set another file declares, and an effect of the kit's, as the
    // game reads them together.
    private fun addGameData(): List<PsiFile> {
        // The rules under data/, the kit's, and the maps -- folders of their own under maps/, not listed among
        // the files and read as .map rather than .duke. See uz.dukeengine.core.map.MapPackage.
        val resources = File(Repos.game, "res")
        val files = listOf(resources to "data", resources to "maps", File(Repos.kit, "src/main/resources") to "kit/data")
            .flatMap { (root, data) -> File(root, data).walkTopDown()
                .filter { it.extension == "duke" || it.extension == "map" }.map { root to it } }
        assertTrue("no data files found: is ${Repos.game} there?", files.size >= 20)
        return files.map { (root, file) -> myFixture.addFileToProject(file.relativeTo(root).invariantSeparatorsPath, file.readText()) }
    }

    /** The whole of the game under one resource root, as a project has it: what the previews read pictures from. */
    private fun withGameResources(use: (VirtualFile, List<PsiFile>) -> Unit) {
        val resources = myFixture.tempDirFixture.findOrCreateDir("res")
        PsiTestUtil.addSourceRoot(module, resources, JavaResourceRootType.RESOURCE)
        try {
            val root = File(Repos.game, "res")
            val files = root.walkTopDown().filter { it.extension == "duke" || it.extension == "map" }.map { file ->
                myFixture.addFileToProject("res/${file.relativeTo(root).invariantSeparatorsPath}", file.readText())
            }.toList()
            copyGameAnimations()
            use(resources, files)
        } finally {
            PsiTestUtil.removeSourceRoot(module, resources)
        }
    }

    /** The game's animation files, copied in: the clips a `@Clip` field may be are read out of them. */
    private fun copyGameAnimations(): VirtualFile {
        myFixture.testDataPath = Repos.game.canonicalPath
        try {
            return myFixture.copyDirectoryToProject("res/animations", "res/animations").parent
        } finally {
            myFixture.testDataPath = Repos.engine.canonicalPath
        }
    }

    private fun classAt(text: String): String? {
        myFixture.configureByText("u.duke", text)
        return (myFixture.elementAtCaret as PsiClass).qualifiedName
    }

    private companion object {
        // The client too: a block can be read straight into its records (OrderMark), and a game's
        // record can share a name with one of them (Fog).
        val ENGINE_MODULES = listOf("core", "rts", "game", "client3d")

        /** The two looks the game's theme may lay a floor in; which one is the seed's business. */
        val TONES = listOf(
            "\"floor\":\"models/tiles/forest/floor_rocky.gltf\",\"wall\":\"models/tiles/forest/tree_round.gltf\"",
            "\"floor\":\"models/tiles/forest/floor_mossy.gltf\",\"wall\":\"models/tiles/forest/tree_tall.gltf\"",
        )
    }
}
