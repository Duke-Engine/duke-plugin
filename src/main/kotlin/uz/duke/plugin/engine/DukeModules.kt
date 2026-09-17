package uz.duke.plugin.engine

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.ConcurrentFactoryMap

/**
 * A module an INI file may name. The name is the class's simple name, which is what every
 * `ModuleFactory.register` call is expected to use ([DukeModuleRegistrationInspection] holds
 * them to it). A class declaring `TAG_PREFIX` is registered once per name under that prefix
 * instead, as `ScriptModule` is under `Script:<name>`, so it owns every name the prefix starts.
 *
 * [fields] are what its `FieldParseTable` reads, or null when they cannot be known.
 */
class DukeModule(val name: String, val psiClass: PsiClass, val isPrefix: Boolean, val fields: List<String>?) {
    fun isNamedBy(text: String) = if (isPrefix) text.length > name.length && text.startsWith(name) else text == name
}

/** Every concrete `Module` subclass one classpath holds. */
class DukeEngine(val modules: List<DukeModule>) {
    // Case-sensitive, as ModuleFactory's map is.
    fun find(name: String): DukeModule? = modules.firstOrNull { it.isNamedBy(name) }
}

object DukeModules {
    const val MODULE_CLASS = "uz.duke.core.module.Module"
    const val FACTORY_CLASS = "uz.duke.core.module.ModuleFactory"
    private const val FIELD_TABLE_CLASS = "uz.duke.core.ini.FieldParseTable"

    private val ENGINES = Key.create<CachedValue<MutableMap<GlobalSearchScope, DukeEngine?>>>("duke.engines")

    /**
     * The engine as [context]'s module sees it, or null when `Module` is not on that classpath.
     *
     * The hierarchy is asked of IntelliJ's Java model, not `java.lang.reflect`: the IDE runs on
     * Java 21 and the engine is compiled for 25, so its classes cannot be loaded here. The model
     * reads the same jars and class files (and sources, when the engine is the open project), and
     * any change to a Java file, a class file or the classpath drops the cache.
     */
    fun of(context: PsiElement): DukeEngine? {
        val project = context.project
        val engines = CachedValuesManager.getManager(project).getCachedValue(project, ENGINES, {
            CachedValueProvider.Result.create(
                ConcurrentFactoryMap.createMap<GlobalSearchScope, DukeEngine?> { scan(project, it) },
                PsiModificationTracker.getInstance(project).forLanguage(JavaLanguage.INSTANCE),
            )
        }, false)
        return engines[context.resolveScope]
    }

    /** `TAG_PREFIX` of a module registered once per name, as `ScriptModule` is. */
    fun prefixOf(cls: PsiClass): String? {
        val initializer = cls.findFieldByName("TAG_PREFIX", false)?.initializer ?: return null
        // Evaluated directly: PsiField.computeConstantValue() gives up when the field's String type
        // does not resolve, as it does in a project whose JDK is not set up.
        return JavaPsiFacade.getInstance(cls.project).constantEvaluationHelper.computeConstantExpression(initializer) as? String
    }

    private fun scan(project: Project, scope: GlobalSearchScope): DukeEngine? {
        val base = JavaPsiFacade.getInstance(project).findClass(MODULE_CLASS, scope) ?: return null
        val classes = ClassInheritorsSearch.search(base, scope, true).findAll()
            .filter { it.qualifiedName != null && !it.isInterface && !it.hasModifierProperty(PsiModifier.ABSTRACT) }
            .sortedBy { it.qualifiedName }
        return DukeEngine(classes.map { cls ->
            val prefix = prefixOf(cls)
            DukeModule(prefix ?: cls.name!!, cls, prefix != null, fieldsOf(cls))
        })
    }

    /**
     * The tokens `FieldParseTable.add("Speed", ...)` registers in the class, in source order.
     * Null for a class with no source to read, or one that builds no table and so may parse its
     * block elsewhere: better to check nothing than to flag fields that are fine.
     */
    private fun fieldsOf(cls: PsiClass): List<String>? {
        val source = cls.navigationElement as? PsiClass
        if (source == null || source is PsiCompiledElement) return null
        val buildsTable = PsiTreeUtil.findChildrenOfType(source, PsiNewExpression::class.java)
            .any { it.classReference?.qualifiedName == FIELD_TABLE_CLASS }
        if (!buildsTable) return null
        return PsiTreeUtil.findChildrenOfType(source, PsiMethodCallExpression::class.java)
            .filter { it.methodExpression.referenceName == "add" }
            .mapNotNull { call ->
                val token = call.argumentList.expressions.firstOrNull() as? PsiLiteralExpression
                token?.takeIf { it.value is String && call.resolveMethod()?.containingClass?.qualifiedName == FIELD_TABLE_CLASS }
            }
            .sortedBy { it.textOffset } // a chain's last add() is the outermost call, so the tree lists it first
            .map { it.value as String }
            .distinct()
    }
}
