package uz.duke.plugin.duke

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiRecordComponent
import com.intellij.psi.PsiReferenceExpression
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
 * - A key is the record's component of that name, any case.
 * - `Key = Class` with its fields under it, and each item of `Key = [ … ]`, is the record the class
 *   names where that component holds it: the component's own record, one a sealed type permits
 *   (`Cylinder` for a `Geometry`), or a word of an open type — each record implementing it, by the
 *   name of the class it is written in, `MoveUpdate` for `MoveUpdate.Data`, as `ModuleFactory.nameOf`
 *   names a module.
 * - A block written on its own inside a record is one of its maps, named by its component.
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
        val owner = block.owner ?: return topLevel(block, block.wordText)
        val record = recordOf(owner) ?: return null
        val field = block.owningField
        if (field == null) {
            // A block on its own inside a record is one of its maps, named by its field.
            val component = component(record, block.wordText) ?: return null
            return if (isMap(component.type)) DukeShape.Entries(component) else null
        }
        val component = component(record, field.key) ?: return null
        val type = blockClass(component.type) ?: return null
        val found = accepting(type, block.wordText, block) ?: return null
        return DukeShape.Record(found, targetOf(found, type), component)
    }

    /** What Ctrl+Click on a record's word opens: a module's class for a word of an open type, else the record. */
    private fun targetOf(record: PsiClass, type: PsiClass): PsiElement =
        record.containingClass?.takeIf { type.isInterface && !isSealed(type) } ?: record

    /**
     * A block at the top of a file: what a loader registers under [word], else the record of that name.
     * A game and the engine it is built on may both have one — a game's `Fog` beside the client's — so
     * the game's own is taken first, then the one that has the keys the block writes, then a top-level
     * one over a nested one.
     */
    fun topLevel(context: PsiElement, word: String): DukeShape? {
        registered(context)[word.lowercase()]?.firstOrNull()?.let { return DukeShape.Record(it, it) }
        val names = PsiShortNamesCache.getInstance(context.project)
        val own = ModuleUtilCore.findModuleForPsiElement(context)?.let(GlobalSearchScope::moduleScope)
        val keys = (context as? DukeBlock)?.fields?.map { it.key }.orEmpty()
        val record = listOf(word, word.replaceFirstChar(Char::uppercaseChar)).distinct()
            .flatMap { names.getClassesByName(it, context.resolveScope).asList() }
            .filter { it.isRecord }
            .minWithOrNull(compareBy<PsiClass>(
                { if (own != null && PsiUtil.getVirtualFile(it)?.let(own::contains) == true) 0 else 1 },
                { candidate -> keys.count { component(candidate, it) == null } },
                { if (it.containingClass == null) 0 else 1 },
            )) ?: return null
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
                val word = constantString(arguments.getOrNull(0)) ?: return@mapNotNull null
                val record = classLiteral(arguments.getOrNull(1)) ?: return@mapNotNull null
                Triple(word.lowercase(), record, PsiTreeUtil.getParentOfType(call, PsiClass::class.java) == loader)
            }
            .sortedBy { it.third }
            .groupBy({ it.first }, { it.second })
    }

    /**
     * Why a block written on its own inside [record] is not one of its maps, said as `Binder` says
     * it: the files were written the other way first, so the way back is spelt out.
     */
    fun misplaced(record: PsiClass, holder: String, word: String, context: PsiElement): String {
        component(record, word)?.let { component ->
            val type = component.type
            if (isCollection(type)) {
                return if (isChoosable(blockClass(type))) "'$word' is a list of blocks: '$word = [', a block for each, then ']'"
                else "'$word' is a list: write it $word = [a, b]"
            }
            val cls = classOf(type)
            if (cls == null || !isChoosable(cls)) return "'$word' is a value: write it $word = …"
            return "'$word' is written '$word = " + choices(cls, context).joinToString("' or '$word = ") { it.first } + "', its fields under it"
        }
        for (component in record.recordComponents) {
            if (isMap(component.type)) continue
            val type = blockClass(component.type) ?: continue
            if (accepting(type, word, context) == null) continue
            val key = capitalized(component.name)
            return if (isCollection(component.type)) "'$word' goes in its list: '$key = [', then $word … End, then ']'"
            else "'$word' is the value of its field: $key = $word"
        }
        return "'$holder' holds no block '$word'"
    }

    /**
     * Whether a type is written as a record named by its word — a record, a sealed type, an open type
     * with records for words — rather than read from text, as a type with `of(String)` is.
     */
    fun isChoosable(type: PsiClass?): Boolean {
        if (type == null || hasFactory(type)) return false
        return type.isRecord || isSealed(type) || type.isInterface
    }

    fun hasFactory(type: PsiClass) = listOf("of", "valueOf").any { name ->
        type.findMethodsByName(name, false).any { it.hasModifierProperty(PsiModifier.STATIC) && it.parameterList.parametersCount == 1 }
    }

    /** The record written as [word] where [type] is held, or null: `Binder.accepting`. */
    fun accepting(type: PsiClass, word: String, context: PsiElement): PsiClass? {
        if (type.isRecord) return type.takeIf { it.name.equals(word, ignoreCase = true) }
        if (isSealed(type)) return permitted(type, context.resolveScope).firstNotNullOfOrNull { accepting(it, word, context) }
        return if (type.isInterface) vocabulary(type, context)[word.lowercase()] else null
    }

    private fun isSealed(type: PsiClass) = type.hasModifierProperty(PsiModifier.SEALED)

    /** The words a block of [type] may be written as, each with what it opens: the record's own, a sealed type's, an open type's. */
    fun choices(type: PsiClass, context: PsiElement): List<Pair<String, PsiElement>> = when {
        type.isRecord -> listOf(type.name.orEmpty() to type)
        // In the order they are written, as a sealed type's permits are; an open type's words in order of name.
        isSealed(type) -> permitted(type, context.resolveScope).sortedWith(compareBy({ it.containingFile?.name }, { it.textOffset }))
            .flatMap { choices(it, context) }
        type.isInterface -> vocabulary(type, context).values.map { nameOf(it) to targetOf(it, type) }.sortedBy { it.first }
        else -> emptyList()
    }

    /** The maps of [record], by the words their blocks are written with: the only blocks written on their own. */
    fun mapWords(record: PsiClass): Map<String, PsiRecordComponent> =
        record.recordComponents.filter { isMap(it.type) }.associateBy { capitalized(it.name) }

    /** The component [key] fills, any case. */
    fun component(record: PsiClass, key: String): PsiRecordComponent? =
        record.recordComponents.firstOrNull { it.name.equals(key, ignoreCase = true) }

    /** Whether [component] is written `Key = …`: every one but a map, whose entries are a block of their own. */
    fun takesValue(component: PsiRecordComponent): Boolean = !isMap(component.type)

    /** The class a block written for [type] is: what a list holds, else the type's own. */
    fun blockClass(type: PsiType): PsiClass? = classOf(if (isCollection(type)) elementOf(type) else type)

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

    /**
     * A literal, or a constant followed to its literal by hand: the evaluator gives up on a field
     * whose `String` type does not resolve, as in a project whose JDK is not set up.
     */
    fun constantString(expression: PsiElement?): String? = when (expression) {
        null -> null
        is PsiLiteralExpression -> expression.value as? String
        is PsiReferenceExpression -> (expression.resolve() as? PsiField)?.initializer?.let(::constantString)
        is PsiExpression -> JavaPsiFacade.getInstance(expression.project).constantEvaluationHelper.computeConstantExpression(expression) as? String
        else -> null
    }

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
    fun plainName(type: PsiType): String =
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
