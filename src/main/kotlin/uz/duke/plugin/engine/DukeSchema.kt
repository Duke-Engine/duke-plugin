package uz.duke.plugin.engine

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiParenthesizedExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiTypeCastExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.ConcurrentFactoryMap

/** How a field's value is read, which decides how it is edited. */
enum class FieldKind { REAL, INTEGER, BOOL, ENUM, COLOUR, WORD, LINE }

/**
 * One field a table reads. [list]: written once per item, each line adding one (`File`, `Kind`).
 * [section]: the field opens a section of its own, `Generation = Layout` to its End, and this is what it reads.
 */
data class FieldSpec(
    val name: String, val kind: FieldKind, val list: Boolean = false, val choices: List<String> = emptyList(), val section: BlockSpec? = null,
)

/** A block type the game's code parses: how many names follow the type in its header, and what it reads. */
data class BlockSpec(val type: String, val names: Int, val fields: List<FieldSpec>) {
    fun field(key: String) = fields.firstOrNull { it.name.equals(key, ignoreCase = true) }
}

class DukeSchema(val blocks: List<BlockSpec>) {
    fun block(type: String) = blocks.firstOrNull { it.type.equals(type, ignoreCase = true) }
}

/**
 * The INI block types and fields a game reads, taken from its code.
 *
 * - Every `initFromIni(x, TABLE)` is a block, named by the key it is registered under
 *   (`Map.of("World", reader -> ...)`), and `TABLE`'s `add("Field", Ini.real(...))`
 *   calls are its fields, followed through `addAll`, `on` and `prefixed("Portrait")`.
 * - A field whose parser reads a table of its own until End is a section inside the block:
 *   `add("Generation", Ini.section(LAYOUT))`, or a parser that calls `initFromIni` itself.
 *
 * The engine's own data — templates, a game's records — is `.duke`, read by records rather than
 * tables: see [uz.duke.plugin.duke.DukeRecords]. Nothing here names a game.
 */
object DukeSchemas {
    private const val INI_CLASS = "uz.duke.core.ini.Ini"
    private const val TABLE_CLASS = "uz.duke.core.ini.FieldParseTable"

    /** Sections inside sections are read this deep; a table that opens itself would otherwise never end. */
    private const val MAX_NESTING = 3

    private val SCHEMAS = Key.create<CachedValue<MutableMap<GlobalSearchScope, DukeSchema?>>>("duke.schemas")

    /** The schema as [context]'s module sees it, or null when the engine's `Ini` is not on that classpath. */
    fun of(context: PsiElement): DukeSchema? {
        val project = context.project
        val schemas = CachedValuesManager.getManager(project).getCachedValue(project, SCHEMAS, {
            CachedValueProvider.Result.create(
                ConcurrentFactoryMap.createMap<GlobalSearchScope, DukeSchema?> { scan(project, it) },
                PsiModificationTracker.getInstance(project).forLanguage(JavaLanguage.INSTANCE),
            )
        }, false)
        return schemas[context.resolveScope]
    }

    private fun scan(project: Project, scope: GlobalSearchScope): DukeSchema? {
        val facade = JavaPsiFacade.getInstance(project)
        facade.findClass(INI_CLASS, scope) ?: return null
        val specs = LinkedHashMap<String, BlockSpec>()
        // One block type may be read in two places, as a game's unit block is: its fields are both.
        fun put(spec: BlockSpec) = specs.merge(spec.type.lowercase(), spec) { a, b ->
            BlockSpec(a.type, maxOf(a.names, b.names), (a.fields + b.fields).distinctBy { it.name.lowercase() })
        }

        // Found by name, not by resolving: `reader` in `Map.entry("X", reader -> ...)` is typed by
        // inference through Map.ofEntries, which a project without its JDK set up cannot do.
        for (call in callsIn(project, scope, "initFromIni", "initFromIni")) {
            if (call.argumentList.expressionCount != 2) continue
            val parser = PsiTreeUtil.getParentOfType(call, PsiLambdaExpression::class.java, PsiMethod::class.java) ?: continue
            // A section's parser is a field of the block that holds it, not a block of its own.
            if (isTableAdd((parser.parent as? PsiExpressionList)?.parent as? PsiMethodCallExpression)) continue
            // A table read where no key registers it is not a block of its own.
            val types = blockTypesOf(parser).ifEmpty { continue }
            val fields = fieldsOf(call.argumentList.expressions[1])
            for (type in types) put(BlockSpec(type, namesRead(parser, call), fields))
        }
        return DukeSchema(specs.values.sortedBy { it.type.lowercase() })
    }

    /** Every call to a method called [method] in the Java files that contain [word]. */
    private fun callsIn(project: Project, scope: GlobalSearchScope, word: String, method: String): List<PsiMethodCallExpression> {
        val files = mutableListOf<PsiFile>()
        PsiSearchHelper.getInstance(project).processAllFilesWithWord(word, scope, { files += it; true }, true)
        return files.filterIsInstance<PsiJavaFile>().sortedBy { it.virtualFile?.path }.flatMap { file ->
            PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java).filter { it.methodExpression.referenceName == method }
        }
    }

    /** The key a block parser is registered under: the argument before it in `entry("X", parser)`. */
    private fun blockTypesOf(parser: PsiElement): List<String> = when (parser) {
        is PsiLambdaExpression -> listOfNotNull(keyBefore(parser))
        is PsiMethod -> MethodReferencesSearch.search(parser, LocalSearchScope(parser.containingFile), false).findAll()
            .mapNotNull { PsiTreeUtil.getParentOfType(it.element, PsiMethodReferenceExpression::class.java, false) }
            .mapNotNull(::keyBefore)
        else -> emptyList()
    }

    private fun keyBefore(parser: PsiExpression): String? {
        // `(Ini.BlockParser) reader -> ...` is still the argument.
        var argument = parser
        while (argument.parent is PsiTypeCastExpression || argument.parent is PsiParenthesizedExpression) argument = argument.parent as PsiExpression
        val arguments = (argument.parent as? PsiExpressionList)?.expressions ?: return null
        val at = arguments.indexOf(argument)
        return if (at > 0) constantString(arguments[at - 1]) else null
    }

    /** The fields a table expression reads, followed to where it is built and whatever it is built from. */
    fun fieldsOf(table: PsiExpression, depth: Int = 0): List<FieldSpec> {
        val adds = mutableListOf<Pair<PsiMethodCallExpression, String>>()
        collectAdds(table, adds, 0, "")
        // Every call of a chain starts where the chain does, so each is placed by its own arguments.
        return adds.distinctBy { it.first }.sortedBy { it.first.argumentList.textOffset }
            .mapNotNull { (add, prefix) -> specOf(add, depth)?.let { it.copy(name = prefix + it.name) } }
            .distinctBy { it.name.lowercase() }
    }

    /** Every `FieldParseTable.add` under [root], in source order, as field specs. */
    fun fieldsIn(root: PsiElement): List<FieldSpec> =
        PsiTreeUtil.findChildrenOfType(root, PsiMethodCallExpression::class.java).filter(::isTableAdd)
            .sortedBy { it.argumentList.textOffset }.mapNotNull(::specOf).distinctBy { it.name.lowercase() }

    /**
     * The `add` calls a table is made of, each with the prefix its names are read under: the
     * chain it is, the tables it adds ([FieldParseTable.addAll], `on`), the variables it is kept
     * in, and what a method that builds it returns. `prefixed("Portrait")` renames what it wraps.
     */
    private fun collectAdds(element: PsiElement?, into: MutableList<Pair<PsiMethodCallExpression, String>>, depth: Int, prefix: String) {
        if (element == null || depth > 8) return
        when (element) {
            is PsiParenthesizedExpression -> collectAdds(element.expression, into, depth + 1, prefix)
            is PsiReferenceExpression -> when (val target = element.resolve()) {
                is PsiLocalVariable -> collectAdds(target.initializer, into, depth + 1, prefix)
                is PsiField -> {
                    collectAdds(target.initializer, into, depth + 1, prefix)
                    // Built in a constructor rather than where it is declared: `this.table = buildTable()`.
                    if (target.initializer == null) {
                        PsiTreeUtil.findChildrenOfType(target.containingClass, PsiAssignmentExpression::class.java)
                            .filter { (it.lExpression as? PsiReferenceExpression)?.isReferenceTo(target) == true }
                            .forEach { collectAdds(it.rExpression, into, depth + 1, prefix) }
                    }
                }
            }
            is PsiMethodCallExpression -> {
                val qualifier = element.methodExpression.qualifierExpression
                // Down a chain the depth stays: a chain is as long as the table, and cannot loop.
                if (element.methodExpression.referenceName == "prefixed") {
                    collectAdds(qualifier, into, depth, prefix + constantString(element.argumentList.expressions.firstOrNull()).orEmpty())
                    return
                }
                if (isTableAdd(element)) {
                    // Its parser is no table; one that opens a section names the section's, not this one's.
                    into += element to prefix
                    collectAdds(qualifier, into, depth, prefix)
                    return
                }
                collectAdds(qualifier, into, depth, prefix)
                for (argument in element.argumentList.expressions) {
                    if (argument !is PsiLambdaExpression) collectAdds(argument, into, depth + 1, prefix)
                }
                // Built by a method of its own, `buildObjectTable()`: what it returns.
                val method = element.resolveMethod()
                if (method != null && method.containingClass?.qualifiedName != TABLE_CLASS) {
                    method.body?.let { body ->
                        PsiTreeUtil.findChildrenOfType(body, PsiReturnStatement::class.java)
                            .forEach { collectAdds(it.returnValue, into, depth + 1, prefix) }
                        // A table filled by statements rather than one chain: `table.add(...)` in the body.
                        PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java).filter(::isTableAdd)
                            .forEach { into += it to prefix }
                    }
                }
            }
        }
    }

    private fun isTableAdd(call: PsiMethodCallExpression?) =
        call != null && call.methodExpression.referenceName == "add" && call.resolveMethod()?.containingClass?.qualifiedName == TABLE_CLASS

    private fun specOf(add: PsiMethodCallExpression, depth: Int = 0): FieldSpec? {
        val arguments = add.argumentList.expressions
        val name = constantString(arguments.getOrNull(0)) ?: return null
        val parser = arguments.getOrNull(1) ?: return FieldSpec(name, FieldKind.LINE)
        return FieldSpec(name, kindOf(parser), isList(parser), choicesOf(parser), sectionOf(name, parser, depth))
    }

    /**
     * What a field reads when it opens a section: the table in `Ini.section(LAYOUT)`, or the one its own
     * parser reads to End, `(reader, s) -> { var item = new LootBuilder(reader.getNextToken()); reader.initFromIni(item, LOOT); ... }`.
     */
    private fun sectionOf(key: String, parser: PsiExpression, depth: Int): BlockSpec? {
        if (depth >= MAX_NESTING) return null
        if (parser is PsiMethodCallExpression && parser.methodExpression.referenceName == "section" && parser.argumentList.expressionCount == 1) {
            return BlockSpec(key, 1, fieldsOf(parser.argumentList.expressions[0], depth + 1))
        }
        if (parser !is PsiLambdaExpression) return null
        val read = PsiTreeUtil.findChildrenOfType(parser, PsiMethodCallExpression::class.java)
            .firstOrNull { it.methodExpression.referenceName == "initFromIni" && it.argumentList.expressionCount == 2 } ?: return null
        return BlockSpec(key, namesRead(parser, read), fieldsOf(read.argumentList.expressions[1], depth + 1))
    }

    /** How many names a parser reads off its header before [read]: `DungeonSkill Rogue Q` reads two. */
    private fun namesRead(parser: PsiElement, read: PsiMethodCallExpression) = PsiTreeUtil.findChildrenOfType(parser, PsiMethodCallExpression::class.java)
        .count { it.methodExpression.referenceName == "getNextToken" && it.textOffset < read.textOffset }

    private fun kindOf(parser: PsiExpression): FieldKind = when (parser) {
        is PsiMethodCallExpression -> when (parser.methodExpression.referenceName) {
            "real" -> FieldKind.REAL
            "integer" -> FieldKind.INTEGER
            "bool" -> FieldKind.BOOL
            "enumeration" -> FieldKind.ENUM
            "string" -> FieldKind.WORD
            "colour", "color" -> FieldKind.COLOUR
            else -> FieldKind.LINE
        }
        is PsiLambdaExpression -> {
            val body = parser.body?.text.orEmpty()
            when {
                "scanEnum" in body -> FieldKind.ENUM
                "scanReal" in body -> FieldKind.REAL
                "scanInt" in body -> FieldKind.INTEGER
                "scanBool" in body -> FieldKind.BOOL
                else -> FieldKind.LINE
            }
        }
        else -> FieldKind.LINE
    }

    /** A setter that adds to a collection reads one item per line: `(s, v) -> s.files.add(v)`. */
    private fun isList(parser: PsiExpression): Boolean {
        val setter = (parser as? PsiMethodCallExpression)?.argumentList?.expressions?.lastOrNull() ?: parser
        return setter is PsiLambdaExpression && setter.body?.text.orEmpty().contains(".add(")
    }

    private fun choicesOf(parser: PsiExpression): List<String> {
        val enumClass = PsiTreeUtil.findChildOfType(parser, PsiClassObjectAccessExpression::class.java)
            ?.operand?.type?.let { (it as? PsiClassType)?.resolve() }
            ?.takeIf { it.isEnum } ?: return emptyList()
        return enumClass.fields.filterIsInstance<PsiEnumConstant>().map { it.name }
    }

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
}
