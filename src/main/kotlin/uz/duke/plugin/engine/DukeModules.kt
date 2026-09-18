package uz.duke.plugin.engine

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.ConcurrentFactoryMap

/**
 * A module an INI file may name. The name is the class's simple name, which is what every
 * `ModuleFactory.register` call is expected to use ([DukeModuleRegistrationInspection] holds
 * them to it). A class declaring `TAG_PREFIX` is registered once per name under that prefix
 * instead, as `ScriptModule` is under `Script:<name>`, so it owns every name the prefix starts.
 *
 * [specs] are what its `FieldParseTable` reads, or null when they cannot be known. [groups] are its
 * `@ModuleGroup` families, and [key] the `Object` field it is written under when no file says otherwise.
 */
class DukeModule(
    val name: String, val psiClass: PsiClass, val isPrefix: Boolean,
    val specs: List<FieldSpec>?, val groups: List<String>, val key: String,
) {
    val fields: List<String>?
        get() = specs?.map { it.name }

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
            DukeModule(prefix ?: cls.name!!, cls, prefix != null, fieldsOf(cls), groupsOf(cls), keyOf(cls))
        })
    }

    /**
     * What the class's `FieldParseTable.add("Speed", ...)` calls read, in source order.
     * Null for a class with no source to read, or one that builds no table and so may parse its
     * block elsewhere: better to check nothing than to flag fields that are fine.
     */
    private fun fieldsOf(cls: PsiClass): List<FieldSpec>? {
        val source = cls.navigationElement as? PsiClass
        if (source == null || source is PsiCompiledElement) return null
        val buildsTable = PsiTreeUtil.findChildrenOfType(source, PsiNewExpression::class.java)
            .any { it.classReference?.qualifiedName == FIELD_TABLE_CLASS }
        if (!buildsTable) return null
        return DukeSchemas.fieldsIn(source)
    }

    /** `@ModuleGroup` is `@Inherited`, so a class without one takes its nearest superclass's. */
    private fun groupsOf(cls: PsiClass): List<String> {
        for (type in generateSequence(cls) { it.superClass }) {
            val value = type.getAnnotation(GROUP_ANNOTATION)?.findAttributeValue("value") ?: continue
            val items = (value as? PsiArrayInitializerMemberValue)?.initializers?.toList() ?: listOf(value)
            return items.mapNotNull(DukeSchemas::constantString)
        }
        return emptyList()
    }

    private fun keyOf(cls: PsiClass): String = when {
        InheritanceUtil.isInheritor(cls, BODY_CLASS) -> "Body"
        InheritanceUtil.isInheritor(cls, UPDATE_CLASS) -> "Update"
        else -> "Behavior"
    }

    private const val GROUP_ANNOTATION = "uz.duke.core.module.ModuleGroup"
    private const val BODY_CLASS = "uz.duke.core.module.BodyModule"
    private const val UPDATE_CLASS = "uz.duke.core.module.UpdateModule"
}
