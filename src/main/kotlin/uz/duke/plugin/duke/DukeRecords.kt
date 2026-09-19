package uz.duke.plugin.duke

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiRecordComponent
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.util.containers.ConcurrentFactoryMap
import uz.duke.plugin.engine.DukeSchemas

/** What a block is, as `uz.duke.core.data.Binder` reads it. */
sealed interface DukeShape {
    /** What Ctrl+Click on the block's word opens. */
    val target: PsiElement

    /**
     * A record — `Monster`, a `Cylinder`, `MoveUpdate`'s `Data` — opened at a module's class, else at
     * itself. [component] is what holds it in the block it is written in; null at the top of a file.
     */
    class Record(val record: PsiClass, override val target: PsiElement, val component: PsiRecordComponent? = null) : DukeShape

    /** A `Map` component written as a block, `Armor`: each line one entry. */
    class Entries(val component: PsiRecordComponent) : DukeShape {
        override val target: PsiElement
            get() = component
    }
}

/**
 * The game's records, read as `Binder` reads them, from the Java model rather than by loading
 * anything: the IDE runs on an older Java than the engine is compiled for.
 *
 * - A block at the top of a file is what a template loader registers under its word,
 *   `loader.type("Object", RtsTemplate.class)`, else the record of that name: `Monster`, `Effect`.
 * - A block inside another fills the component named after it, else the one whose type it names:
 *   a record of that name, one a sealed type permits (`Cylinder` for a `Geometry`), or a word of an
 *   open type — each record implementing it, by the name of the class it is written in, so
 *   `MoveUpdate` for `MoveUpdate.Data`, as `ModuleFactory.nameOf` names a module.
 * - A key is the record's component of that name, any case.
 *
 * Nothing here names a game.
 */
object DukeRecords {
    private const val LOADER_CLASS = "uz.duke.core.thing.ThingTemplateLoader"

    private val REGISTERED = Key.create<CachedValue<MutableMap<GlobalSearchScope, Map<String, List<PsiClass>>>>>("duke.registered")
    private val VOCABULARIES = Key.create<CachedValue<MutableMap<GlobalSearchScope, MutableMap<String, Map<String, PsiClass>>>>>("duke.vocabularies")

    /** What [block] is; null when the engine is not on its classpath, while indexing, or when it is nothing known. */
    fun shapeOf(block: DukeBlock): DukeShape? {
        if (DumbService.isDumb(block.project)) return null
        return CachedValuesManager.getCachedValue(block) {
            CachedValueProvider.Result.create(compute(block), PsiModificationTracker.getInstance(block.project))
        }
    }

    /** The record [block] is, when it is one rather than a map. */
    fun recordOf(block: DukeBlock): PsiClass? = (shapeOf(block) as? DukeShape.Record)?.record

    private fun compute(block: DukeBlock): DukeShape? {
        val container = block.container ?: return topLevel(block, block.wordText)
        val holder = recordOf(container) ?: return null
        return slot(holder, block.wordText, block)
    }

    /** A block at the top of a file: what a loader registers under [word], else the record of that name. */
    fun topLevel(context: PsiElement, word: String): DukeShape? {
        registered(context)[word.lowercase()]?.firstOrNull()?.let { return DukeShape.Record(it, it) }
        val names = PsiShortNamesCache.getInstance(context.project)
        val record = listOf(word, word.replaceFirstChar(Char::uppercaseChar)).distinct()
            .flatMap { names.getClassesByName(it, context.resolveScope).asList() }
            .filter { it.isRecord }
            .minByOrNull { if (it.containingClass == null) 0 else 1 } ?: return null
        return DukeShape.Record(record, record)
    }

    /** Every word a template loader is told, a game's registration before the loader's own, which it replaces. */
    fun registered(context: PsiElement): Map<String, List<PsiClass>> = perScope(context, REGISTERED) { project, scope ->
        val loader = JavaPsiFacade.getInstance(project).findClass(LOADER_CLASS, scope) ?: return@perScope emptyMap()
        val method = loader.findMethodsByName("type", false).firstOrNull { it.parameterList.parametersCount == 2 }
            ?: return@perScope emptyMap()
        MethodReferencesSearch.search(method, scope, true).findAll()
            .mapNotNull { it.element.parent as? PsiMethodCallExpression }
            .mapNotNull { call ->
                val arguments = call.argumentList.expressions
                val word = DukeSchemas.constantString(arguments.getOrNull(0)) ?: return@mapNotNull null
                val record = classLiteral(arguments.getOrNull(1)) ?: return@mapNotNull null
                Triple(word.lowercase(), record, PsiTreeUtil.getParentOfType(call, PsiClass::class.java) == loader)
            }
            .sortedBy { it.third }
            .groupBy({ it.first }, { it.second })
    }

    /** Which of [record]'s components a block called [word] fills, as `Binder.slotFor` decides it. */
    fun slot(record: PsiClass, word: String, context: PsiElement): DukeShape? {
        // A block named after the component that holds it: Look for a MonsterLook look.
        for (component in record.recordComponents) {
            if (!component.name.equals(word, ignoreCase = true)) continue
            if (isMap(component.type)) return DukeShape.Entries(component)
            val type = blockClass(component.type)
            if (type != null && type.isRecord) return DukeShape.Record(type, type, component)
        }
        // Else after what it is: a Generation, a Cylinder of a Geometry, a module.
        for (component in record.recordComponents) {
            if (isMap(component.type)) continue
            val type = blockClass(component.type) ?: continue
            val found = accepting(type, word, context) ?: continue
            return DukeShape.Record(found, found.containingClass?.takeIf { type.isInterface && !isSealed(type) } ?: found, component)
        }
        return null
    }

    /** The record a block called [word] is, where a component of [type] holds it: `Binder.accepting`. */
    private fun accepting(type: PsiClass, word: String, context: PsiElement): PsiClass? {
        if (type.isRecord) return type.takeIf { it.name.equals(word, ignoreCase = true) }
        if (isSealed(type)) return permitted(type, context.resolveScope).firstNotNullOfOrNull { accepting(it, word, context) }
        return if (type.isInterface) vocabulary(type, context)[word.lowercase()] else null
    }

    private fun isSealed(type: PsiClass) = type.hasModifierProperty(PsiModifier.SEALED)

    /**
     * Every word a block inside [record] may be, with what it opens: a record component by its own
     * name, a list of records by the record's, and the words a sealed or open type is written as.
     */
    fun blockWords(record: PsiClass, context: PsiElement): Map<String, PsiElement> {
        val words = LinkedHashMap<String, PsiElement>()
        for (component in record.recordComponents) {
            if (isMap(component.type)) {
                words.putIfAbsent(capitalized(component.name), component)
                continue
            }
            val type = blockClass(component.type) ?: continue
            if (!isCollection(component.type) && type.isRecord) words.putIfAbsent(capitalized(component.name), type)
            else accepted(type, context).forEach { (word, target) -> words.putIfAbsent(word, target) }
        }
        return words
    }

    private fun accepted(type: PsiClass, context: PsiElement): List<Pair<String, PsiElement>> = when {
        type.isRecord -> listOf(type.name.orEmpty() to type)
        type.hasModifierProperty(PsiModifier.SEALED) -> permitted(type, context.resolveScope).flatMap { accepted(it, context) }
        type.isInterface -> vocabulary(type, context).values.map { nameOf(it) to (it.containingClass ?: it) }
        else -> emptyList()
    }

    /** The component [key] fills, any case. */
    fun component(record: PsiClass, key: String): PsiRecordComponent? =
        record.recordComponents.firstOrNull { it.name.equals(key, ignoreCase = true) }

    /** Whether [component] can be written `Key = value`: a map or a list of blocks cannot. */
    fun takesValue(component: PsiRecordComponent): Boolean {
        if (isMap(component.type)) return false
        val type = blockClass(component.type) ?: return true
        return !type.isInterface && (!isCollection(component.type) || !type.isRecord)
    }

    /**
     * The words an open type is written as: each record implementing it, by the class it is written
     * in, `MoveUpdate` for `MoveUpdate.Data`.
     */
    fun vocabulary(type: PsiClass, context: PsiElement): Map<String, PsiClass> {
        val name = type.qualifiedName ?: return emptyMap()
        val byType = perScope(context, VOCABULARIES) { project, scope ->
            ConcurrentFactoryMap.createMap<String, Map<String, PsiClass>> { qualified ->
                val base = JavaPsiFacade.getInstance(project).findClass(qualified, scope) ?: return@createMap emptyMap()
                ClassInheritorsSearch.search(base, scope, true).findAll().filter { it.isRecord }
                    .sortedBy { it.qualifiedName }.associateBy { nameOf(it).lowercase() }
            }
        }
        return byType[name].orEmpty()
    }

    /** `ModuleFactory.nameOf`: the name of the class a record is written in, else its own. */
    fun nameOf(record: PsiClass): String = record.containingClass?.name ?: record.name.orEmpty()

    fun capitalized(name: String) = name.replaceFirstChar(Char::uppercaseChar)

    private fun permitted(type: PsiClass, scope: GlobalSearchScope): Collection<PsiClass> =
        ClassInheritorsSearch.search(type, scope, false).findAll()

    // ---- types ----

    fun classOf(type: PsiType?): PsiClass? = PsiUtil.resolveClassInClassTypeOnly(type)

    fun isMap(type: PsiType) = qualifiedName(type) == "java.util.Map"

    /** A `List` or a `Set`: written `[a, b]`, or as one block per item. */
    fun isCollection(type: PsiType) = qualifiedName(type) in COLLECTIONS

    /** What a `List` or `Set` holds; null for any other type. */
    fun elementOf(type: PsiType): PsiType? = if (isCollection(type)) typeArgument(type, 0) else null

    /** The class a block for [type] is: what a collection holds, else the type's own. */
    private fun blockClass(type: PsiType): PsiClass? = classOf(if (isCollection(type)) elementOf(type) else type)

    fun typeArgument(type: PsiType, index: Int): PsiType? {
        val argument = (type as? PsiClassType)?.parameters?.getOrNull(index) ?: return null
        return (argument as? PsiWildcardType)?.extendsBound ?: argument
    }

    /**
     * A type's class by its whole name. A project whose JDK is not set up resolves none of
     * `java.util`, so a `List`, `Set` or `Map` that does not resolve is taken as the one imported.
     */
    private fun qualifiedName(type: PsiType): String? {
        classOf(type)?.qualifiedName?.let { return it }
        val name = (type as? PsiClassType)?.className ?: return null
        return if (name in JAVA_UTIL) "java.util.$name" else null
    }

    private val JAVA_UTIL = setOf("List", "Set", "Map")

    private fun classLiteral(expression: PsiElement?): PsiClass? =
        ((expression as? PsiClassObjectAccessExpression)?.operand?.type as? PsiClassType)?.resolve()

    private val COLLECTIONS = setOf("java.util.List", "java.util.Set")

    // ---- values ----

    /**
     * What is wrong with [text] as one value of [type], in the words `Binder` would use at load;
     * null when it reads, or when what reads it (an `of(String)` factory) cannot be run here.
     */
    fun problemOf(text: String, type: PsiType, key: String): String? {
        return when (plainName(type)) {
            "int" -> if (integer(text, long = false)) null else "'$key' is a number, not '$text'"
            "long" -> if (integer(text, long = true)) null else "'$key' is a number, not '$text'"
            "float", "double" -> if (real(text)) null else "'$key' is a number, not '$text'"
            "boolean" -> if (BOOLEANS.any { it.equals(text, ignoreCase = true) }) null else "'$key' is Yes or No, not '$text'"
            "char" -> if (text.length == 1) null else "'$key' is one character, not '$text'"
            else -> {
                val constants = constantsOf(type) ?: return null
                if (constants.any { it.name.equals(text, ignoreCase = true) }) null
                else "'$key' is one of ${constants.map { it.name }}, not '$text'"
            }
        }
    }

    fun isBoolean(type: PsiType) = plainName(type) == "boolean"

    /** The primitive a type is or boxes, by name; a box read by its name where `java.lang` does not resolve. */
    private fun plainName(type: PsiType): String =
        PsiPrimitiveType.getUnboxedType(type)?.canonicalText ?: BOXES[(type as? PsiClassType)?.className] ?: type.canonicalText

    private val BOXES = mapOf(
        "Integer" to "int", "Long" to "long", "Float" to "float", "Double" to "double", "Boolean" to "boolean", "Character" to "char",
    )

    /** The constants of [type] when it is an enum. */
    fun constantsOf(type: PsiType?): List<PsiEnumConstant>? =
        classOf(type)?.takeIf { it.isEnum }?.fields?.filterIsInstance<PsiEnumConstant>()

    private fun integer(text: String, long: Boolean): Boolean = try {
        val hex = text.length > 2 && text[0] == '0' && (text[1] == 'x' || text[1] == 'X')
        when {
            hex && long -> java.lang.Long.parseUnsignedLong(text.substring(2), 16)
            hex -> Integer.parseUnsignedInt(text.substring(2), 16)
            long -> java.lang.Long.parseLong(text)
            else -> Integer.parseInt(text)
        }
        true
    } catch (_: NumberFormatException) {
        false
    }

    private fun real(text: String): Boolean = try {
        java.lang.Double.parseDouble(text)
        true
    } catch (_: NumberFormatException) {
        false
    }

    private val BOOLEANS = listOf("Yes", "No", "true", "false")

    private fun <T : Any> perScope(
        context: PsiElement,
        key: Key<CachedValue<MutableMap<GlobalSearchScope, T>>>,
        compute: (Project, GlobalSearchScope) -> T,
    ): T {
        val project = context.project
        val byScope = CachedValuesManager.getManager(project).getCachedValue(project, key, {
            CachedValueProvider.Result.create(
                ConcurrentFactoryMap.createMap<GlobalSearchScope, T> { compute(project, it) },
                PsiModificationTracker.getInstance(project).forLanguage(JavaLanguage.INSTANCE),
            )
        }, false)
        return byScope[context.resolveScope]!!
    }
}
