package uz.duke.plugin.engine

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.io.File

/** Against the engine next to the plugin: its real classes, registrations and game files are the spec. */
class DukeEngineSourcesTest : LightJavaCodeInsightFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.testDataPath = File("..").canonicalPath
        for (module in ENGINE_MODULES) {
            myFixture.copyDirectoryToProject("$module/src/main/java", "")
        }
    }

    fun testGameFilesAreCleanAgainstTheEngine() {
        val files = File("../dungeon/src/main/resources/ini").walkTopDown().filter { it.extension == "ini" }.toList()
        assertTrue("no INI files found next to the plugin", files.size >= 5)
        assertEmpty(files.flatMap { file ->
            myFixture.configureByText(file.name, file.readText())
            myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).map { "${file.invariantSeparatorsPath.substringAfter("resources/")}: ${it.description} at '${it.text}'" }
        })
        // Clean because it was checked, not because the engine went unseen.
        myFixture.configureByText("check.ini", "Object Hero\n  Update = MoveUpdat Tag\n  End\nEnd\n")
        assertEquals(listOf("Unknown module 'MoveUpdat'"), myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description })
    }

    fun testModulesAndFieldsComeFromTheClasses() {
        myFixture.configureByText("check.ini", "")
        val engine = DukeModules.of(myFixture.file)!!
        assertContainsElements(
            engine.modules.map { it.name },
            "ActiveBody", "MoveUpdate", "WeaponUpdate", "ExperienceModule", "GrowableBody", "Bow", "Swing", "Script:",
        )
        assertDoesntContain(engine.modules.map { it.name }, "Module", "UpdateModule", "BodyModule", "ScriptModule")
        assertEquals(listOf("Speed", "TurnRate"), engine.find("MoveUpdate")!!.fields)
        assertEquals(listOf("MaxHealth", "Armor"), engine.find("ActiveBody")!!.fields)
        assertEquals(emptyList<String>(), engine.find("Swing")!!.fields)
        assertEquals("uz.duke.game.script.ScriptModule", engine.find("Script:HeroBrain")!!.psiClass.qualifiedName)
    }

    /** The block types and their fields are the game's code, read as it is written. */
    fun testBlocksAndTheirFieldsComeFromTheGame() {
        myFixture.configureByText("check.ini", "")
        val schema = DukeSchemas.of(myFixture.file)!!
        assertTrue("too few block types to be a scan: ${schema.blocks.map { it.type }}", schema.blocks.size >= 15)

        val skill = schema.block("DungeonSkill")!!
        assertEquals(2, skill.names) // DungeonSkill Rogue Q
        assertEquals(FieldKind.ENUM, skill.field("Effect")!!.kind)
        assertContainsElements(skill.field("Effect")!!.choices, "STRIKE")
        assertEquals(FieldKind.REAL, skill.field("Range")!!.kind)

        val sound = schema.block("DungeonSound")!!
        assertTrue(sound.field("File")!!.list)
        assertEquals(FieldKind.BOOL, sound.field("Positional")!!.kind)

        // Registered as `registry.put("Object", this::parseObject)`, its table built in a method of its own.
        val template = schema.block("Object")!!
        assertEquals(1, template.names)
        assertEquals(FieldKind.REAL, template.field("VisionRange")!!.kind)
        // Registered under a constant, `Map.of(MANIFEST, ...)`.
        assertTrue(schema.block("DungeonContent")!!.field("File")!!.list)

        // A game's unit block: the engine's fields for what its record can do, and the game's own,
        // its portrait's among them under Portrait... names.
        val monster = schema.block("Monster")!!
        assertTrue(monster.template)
        assertEquals(FieldKind.REAL, monster.field("SenseRadius")!!.kind)
        assertNotNull(monster.field("DisplayName"))
        assertNotNull(monster.field("GeometryHeight"))
        assertNotNull(monster.field("PortraitYaw"))
        assertNotNull(schema.block("Hero")!!.field("PortraitCalm"))
        // An arrow is not solid, so its block takes no shape; it may see.
        val projectile = schema.block("Projectile")!!
        assertNull(projectile.field("Geometry"))
        assertNotNull(projectile.field("VisionRange"))
        assertTrue(template.template)

        // A block of sections: the world, each section read by the field that opens it.
        val world = schema.block("World")!!
        assertTrue("too few sections to be a scan", world.fields.count { it.section != null } >= 30)
        assertEquals(FieldKind.REAL, world.field("LevelHeight")!!.kind)
        assertNull(world.field("MapWidth")) // Generation's, not the world's
        assertEquals(FieldKind.INTEGER, world.field("Generation")!!.section!!.field("MapWidth")!!.kind)
        assertEquals(FieldKind.REAL, world.field("StatBlock")!!.section!!.field("FigureIcon")!!.kind)
        val item = world.field("LootItem")!!
        assertTrue("a section written once per item", item.list)
        assertEquals(FieldKind.ENUM, item.section!!.field("Kind")!!.kind)
        assertEquals(2, world.field("Tone")!!.section!!.names) // Tone = Forest Wooded
        assertNull("a section is not a block of its own", schema.block("LootItem"))
    }

    fun testModulesAreGroupedAndKnowTheirKey() {
        myFixture.configureByText("check.ini", "")
        val engine = DukeModules.of(myFixture.file)!!
        assertEquals(listOf("Movement"), engine.find("MoveUpdate")!!.groups)
        assertEquals(listOf("Combat", "Movement", "Effect", "Body"), engine.find("SkillBook")!!.groups)
        assertEquals(FieldKind.REAL, engine.find("MoveUpdate")!!.specs!!.first { it.name == "Speed" }.kind)
        assertEquals("Body", engine.find("GrowableBody")!!.key)
        assertEquals("Update", engine.find("MoveUpdate")!!.key)
        assertEquals("Behavior", engine.find("ExperienceModule")!!.key)
    }

    fun testEveryRegisteredNameIsTheClassName() {
        myFixture.configureByText("check.ini", "")
        val engine = DukeModules.of(myFixture.file)!!
        val psiManager = PsiManager.getInstance(project)
        val names = REGISTERING_FILES
            .map { psiManager.findFile(myFixture.findFileInTempDir(it))!! }
            .flatMap { PsiTreeUtil.findChildrenOfType(it, PsiMethodCallExpression::class.java) }
            .filter { it.methodExpression.referenceName == "register" }
            .mapNotNull { (it.argumentList.expressions.firstOrNull() as? PsiLiteralExpression)?.value as? String }
        assertTrue("registrations not found: $names", names.size >= 25)
        assertEmpty(names.filter { engine.find(it) == null })

        myFixture.enableInspections(DukeModuleRegistrationInspection())
        for (path in REGISTERING_FILES) {
            myFixture.configureFromTempProjectFile(path)
            assertEmpty(myFixture.doHighlighting().mapNotNull { it.description }.filter { it.startsWith("Registered name") })
        }
    }

    private companion object {
        val ENGINE_MODULES = listOf("core", "rts", "game", "dungeon")
        val REGISTERING_FILES = listOf(
            "uz/duke/core/module/ModuleFactory.java",
            "uz/duke/rts/module/RtsModules.java",
            "uz/duke/dungeon/Dungeon.java",
        )
    }
}
