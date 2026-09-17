package uz.duke.plugin.engine

import com.intellij.codeInsight.completion.JavaCompletionAutoPopupTestCase
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.runInEdtAndWait

/** Against a few classes shaped like the engine's. */
class DukeModulesTest : LightJavaCodeInsightFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addEngineStubs()
    }

    fun testModuleNamesAreOffered() {
        myFixture.configureByText("units.ini", "Object Hero\n  Update = <caret>\n  End\nEnd\n")
        myFixture.completeBasic()
        assertSameElements(myFixture.lookupElementStrings!!, "MoveUpdate", "Script:")
    }

    fun testFieldsOfTheModuleAreOffered() {
        myFixture.configureByText("units.ini", "Object Hero\n  Update = MoveUpdate Tag\n    <caret>\n  End\nEnd\n")
        myFixture.completeBasic()
        assertSameElements(myFixture.lookupElementStrings!!, "Speed", "TurnRate")
    }

    fun testANewModuleClassIsOfferedAtOnce() {
        myFixture.configureByText("units.ini", "Object Hero\n  Update = <caret>\n  End\nEnd\n")
        myFixture.doHighlighting() // fills the cache
        myFixture.addClass("package game; public final class Recovery extends uz.duke.core.module.UpdateModule {}")
        myFixture.completeBasic()
        assertSameElements(myFixture.lookupElementStrings!!, "MoveUpdate", "Recovery", "Script:")
    }

    fun testUnknownModulesAndFields() {
        myFixture.configureByText(
            "units.ini",
            """
            |Object Hero
            |  Update = <error descr="Unknown module 'MoveUpdat'">MoveUpdat</error> Tag
            |    Anything = 1
            |  End
            |  Update = <error descr="Unknown module 'moveupdate'">moveupdate</error> Tag
            |  End
            |  Update = <error descr="Unknown module 'UpdateModule'">UpdateModule</error> Tag
            |  End
            |  Update = MoveUpdate Tag
            |    speed = 1
            |    <warning descr="Unknown field 'Sped' for module 'MoveUpdate'">Sped</warning> = 2
            |  End
            |  Update = Script:HeroBrain Tag
            |    <warning descr="Unknown field 'Speed' for module 'Script:HeroBrain'">Speed</warning> = 2
            |  End
            |  Update = <error descr="Unknown module 'Script:'">Script:</error> Tag
            |  End
            |End
            """.trimMargin(),
        )
        myFixture.checkHighlighting()
    }

    fun testModuleNameLeadsToItsClass() {
        myFixture.configureByText("units.ini", "Object Hero\n  Update = Move<caret>Update Tag\n  End\nEnd\n")
        assertEquals("uz.duke.core.module.MoveUpdate", (myFixture.elementAtCaret as PsiClass).qualifiedName)
        myFixture.configureByText("units.ini", "Object Hero\n  Update = Script:Hero<caret>Brain Tag\n  End\nEnd\n")
        assertEquals("uz.duke.game.script.ScriptModule", (myFixture.elementAtCaret as PsiClass).qualifiedName)
    }

    fun testRenamingTheClassRenamesTheIniLine() {
        val ini = myFixture.addFileToProject("units.ini", "Object Hero\n  Update = MoveUpdate Tag\n  End\nEnd\n")
        myFixture.renameElement(myFixture.findClass("uz.duke.core.module.MoveUpdate"), "Mover")
        assertEquals("Object Hero\n  Update = Mover Tag\n  End\nEnd\n", ini.text)
    }

    fun testRegisteredNameMustBeTheClassName() {
        myFixture.enableInspections(DukeModuleRegistrationInspection())
        val ini = myFixture.addFileToProject("units.ini", "Object Hero\n  Update = mover Tag\n  End\nEnd\n")
        myFixture.configureByText(
            "Setup.java",
            """
            import uz.duke.core.module.*;
            import uz.duke.game.script.ScriptModule;
            class Setup {
                void setUp(ModuleFactory factory) {
                    factory.register(<warning descr="Registered name 'mover' does not match class name 'MoveUpdate'">"mo<caret>ver"</warning>, (owner, data) -> new MoveUpdate());
                    factory.register("MoveUpdate", (owner, data) -> new MoveUpdate());
                    factory.register("Script:Hero", (owner, data) -> new ScriptModule());
                    factory.register("Mixed", (owner, data) -> data == null ? new MoveUpdate() : new ScriptModule());
                }
            }
            """.trimIndent(),
        )
        myFixture.checkHighlighting()
        myFixture.launchAction(myFixture.findSingleIntention("Rename to 'MoveUpdate'"))
        assertTrue(myFixture.editor.document.text.contains("factory.register(\"MoveUpdate\", (owner, data) -> new MoveUpdate());\n        factory.register(\"MoveUpdate\""))
        assertEquals("Object Hero\n  Update = MoveUpdate Tag\n  End\nEnd\n", PsiManager.getInstance(project).findFile(ini.virtualFile)!!.text)
    }
}

class DukeModuleAutoPopupTest : JavaCompletionAutoPopupTestCase() {
    override fun setUp() {
        super.setUp()
        runInEdtAndWait { myFixture.addEngineStubs() }
    }

    fun testTheSpaceAfterTheEqualsSignOpensTheModuleList() {
        myFixture.configureByText("units.ini", "Object Hero\n  Update =<caret>\n  End\nEnd\n")
        type(" ")
        assertSameElements(lookup.items.map { it.lookupString }, "MoveUpdate", "Script:")
    }
}

private fun JavaCodeInsightTestFixture.addEngineStubs() {
    addClass(
        """
        package uz.duke.core.ini;
        public final class FieldParseTable<T> {
            public FieldParseTable<T> add(String token, Object parser) { return this; }
        }
        """.trimIndent(),
    )
    addClass("package uz.duke.core.module; public abstract class Module {}")
    addClass("package uz.duke.core.module; public abstract class UpdateModule extends Module {}")
    addClass(
        """
        package uz.duke.core.module;
        public final class ModuleFactory {
            public interface Builder { Module build(Object owner, Object data); }
            public void register(String tag, Builder builder) {}
        }
        """.trimIndent(),
    )
    addClass(
        """
        package uz.duke.core.module;
        import uz.duke.core.ini.FieldParseTable;
        public final class MoveUpdate extends UpdateModule {
            private static final FieldParseTable<Object> DATA_TABLE = new FieldParseTable<Object>()
                    .add("Speed", null)
                    .add("TurnRate", null);
        }
        """.trimIndent(),
    )
    addClass(
        """
        package uz.duke.game.script;
        import uz.duke.core.ini.FieldParseTable;
        import uz.duke.core.module.*;
        public final class ScriptModule extends UpdateModule {
            public static final String TAG_PREFIX = "Script:";
            public static void registerScript(ModuleFactory factory, String name) {
                factory.register(TAG_PREFIX + name, (owner, data) -> new ScriptModule());
                new FieldParseTable<Object>();
            }
        }
        """.trimIndent(),
    )
}
