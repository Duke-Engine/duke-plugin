package uz.duke.plugin.duke

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecordComponent
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ProcessingContext
import uz.duke.plugin.ini.AssetKind
import uz.duke.plugin.ini.DukeAssets

/**
 * What a line may be, from the record its block is: on a line being begun, the keys the record has
 * not been given and the blocks it holds; after `=`, the constants of an enum, `Yes`/`No`, or the
 * files of the kind the key takes. At the top of a file, the words the project's files open with.
 */
class DukeCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, psiElement().withParent(DukeWord::class.java), provider { parameters, result ->
            val container = (parameters.position.parent as DukeWord).block.container
            if (container == null) {
                topLevelWords(parameters.originalFile).forEach { result.addElement(LookupElementBuilder.create(it).withInsertHandler(::closeBlock)) }
                return@provider
            }
            val record = DukeRecords.recordOf(container) ?: return@provider
            keys(record, container, result, withEquals = true)
            for ((word, target) in DukeRecords.blockWords(record, container)) {
                val typeText = when (target) {
                    is PsiClass -> target.qualifiedName?.substringBeforeLast('.')
                    is PsiRecordComponent -> target.type.presentableText
                    else -> null
                }
                result.addElement(LookupElementBuilder.create(target, word).withIcon(target.getIcon(0)).withTypeText(typeText).withInsertHandler(::closeBlock))
            }
        })
        extend(CompletionType.BASIC, psiElement().withParent(DukeKey::class.java), provider { parameters, result ->
            val block = (parameters.position.parent as DukeKey).field.block ?: return@provider
            DukeRecords.recordOf(block)?.let { keys(it, block, result, withEquals = false) }
        })
        extend(CompletionType.BASIC, psiElement().withParent(DukeValue::class.java), provider { parameters, result ->
            val value = parameters.position.parent as DukeValue
            val type = DukeValueReferences.typeOf(value)
            DukeRecords.constantsOf(type)?.forEach { result.addElement(LookupElementBuilder.create(it, it.name).withIcon(it.getIcon(0))) }
            if (type != null && DukeRecords.isBoolean(type)) listOf("Yes", "No").forEach { result.addElement(LookupElementBuilder.create(it)) }
            assets(value, result)
        })
    }

    /** The record's components not yet written, by the name a file writes them with. */
    private fun keys(record: PsiClass, block: DukeBlock, result: CompletionResultSet, withEquals: Boolean) {
        val written = block.fields.map { it.key.lowercase() } + block.blocks.map { it.wordText.lowercase() }
        for (component in record.recordComponents) {
            if (!DukeRecords.takesValue(component) || component.name.lowercase() in written) continue
            var lookup = LookupElementBuilder.create(component, DukeRecords.capitalized(component.name))
                .withIcon(component.getIcon(0)).withTypeText(component.type.presentableText)
            if (withEquals) lookup = lookup.withInsertHandler { context, _ ->
                val at = context.tailOffset
                context.document.insertString(at, " = ")
                context.editor.caretModel.moveToOffset(at + 3)
                AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
            }
            result.addElement(lookup)
        }
    }

    /** Files of the kind the key takes, or of the kind the list's other items are. */
    private fun assets(value: DukeValue, result: CompletionResultSet) {
        val field = value.field ?: return
        val root = DukeAssets.rootOf(value) ?: return
        val kinds = AssetKind.named(field.key)?.let(::setOf)
            ?: field.values.filter { it != value }.mapNotNull { AssetKind.of(it.unquoted) }.toSet()
        if (kinds.isEmpty()) return
        for (path in DukeAssets.filesUnder(root)) {
            if (AssetKind.of(path) in kinds) result.addElement(LookupElementBuilder.create(path).withLookupString(path.substringAfterLast('/')))
        }
    }

    /** Every word a block opens with at the top of a project file, and every one a template loader is told. */
    private fun topLevelWords(file: PsiFile): Set<String> {
        val project = file.project
        val psi = PsiManager.getInstance(project)
        val used = FileTypeIndex.getFiles(DukeFileType, GlobalSearchScope.projectScope(project))
            .mapNotNull { psi.findFile(it) as? DukeFile }.filter { it != file.originalFile }
            .flatMap { it.blocks }.map { it.wordText }
        return (used + DukeRecords.registered(file).keys.map(DukeRecords::capitalized)).toSortedSet()
    }

    /** A block's word and, on a line of its own under it, its `End`, the caret between them. */
    private fun closeBlock(context: InsertionContext, @Suppress("UNUSED_PARAMETER") item: LookupElement) {
        val document = context.document
        val line = document.getLineNumber(context.startOffset)
        val indent = document.getText(TextRange(document.getLineStartOffset(line), context.startOffset))
        if (indent.isNotBlank()) return
        val at = context.tailOffset
        document.insertString(at, "\n$indent  \n${indent}End")
        context.editor.caretModel.moveToOffset(at + 1 + indent.length + 2)
    }

    private inline fun provider(crossinline add: (CompletionParameters, CompletionResultSet) -> Unit) =
        object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) =
                add(parameters, result)
        }
}

/** The space after `Key =` opens the list of what the key takes; where nothing is offered, nothing opens. */
class DukeTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != ' ' || file !is DukeFile) return Result.CONTINUE
        val document = editor.document
        val offset = editor.caretModel.offset
        val line = document.getText(TextRange(document.getLineStartOffset(document.getLineNumber(offset)), offset))
        if (!KEY_EQUALS.matches(line)) return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }

    private companion object {
        val KEY_EQUALS = Regex("""\s*\w+\s*=""")
    }
}
