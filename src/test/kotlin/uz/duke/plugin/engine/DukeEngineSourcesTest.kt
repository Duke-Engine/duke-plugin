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
        val files = File("../dungeon/src/main/resources/ini").listFiles { f -> f.extension == "ini" }.orEmpty()
        assertTrue("no INI files found next to the plugin", files.size >= 5)
        for (file in files) {
            myFixture.configureByText(file.name, file.readText())
            assertEmpty(myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).map { "${file.name}: ${it.description} at '${it.text}'" })
        }
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
