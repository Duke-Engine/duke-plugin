package uz.dukeengine.plugin.duke

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
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ProcessingContext
import uz.dukeengine.plugin.assets.AssetKind
import uz.dukeengine.plugin.assets.DukeAssets

/**
 * What a line may be, from the record its block is. On a line being begun: the keys the record has
 * not been given and its maps; in a list of blocks, the records the list holds. After `=`: the
 * records the field may be, an enum's constants, `Yes`/`No`, or the files of the kind the key takes.
 * At the top of a file, the words the project's files open with.
 */
class DukeCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, psiElement().withParent(DukeWord::class.java), provider { parameters, result ->
            val block = (parameters.position.parent as DukeWord).block
            when (val holder = block.parent) {
                is DukeField -> choices(holder, result, closing = false)
                is DukeList -> (holder.parent as? DukeField)?.let { choices(it, result, closing = true) }
                is DukeBlock -> DukeRecords.recordOf(holder)?.let { keys(it, holder, result, withEquals = true) }
                else -> topLevelWords(parameters.originalFile).forEach {
                    result.addElement(LookupElementBuilder.create(it).withInsertHandler(::closeBlock))
                }
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
            // A word being begun in an empty `Modules = [` reads as a value until it has a body: offer the blocks.
            value.field?.let { choices(it, result, closing = value.isInList) }
            assets(value, result)
            linksAndClips(value, result)
            entryKeys(value, result)
        })
    }

    /** The names a linking field may be — the blocks of its record there are — and the clips a clip may be. */
    private fun linksAndClips(value: DukeValue, result: CompletionResultSet) {
        val component = DukeLinks.componentOf(value) ?: return
        DukeLinks.linkOf(component)?.let { record ->
            for (name in DukeLinks.blocksOf(record, value).keys.sorted()) {
                result.addElement(LookupElementBuilder.create(name).withTypeText(record.name))
            }
        }
        if (!DukeLinks.isClip(component)) return
        val block = value.field?.block ?: return
        for (clip in DukeLinks.clipsFor(block)) result.addElement(LookupElementBuilder.create(clip).withTypeText("clip"))
    }

    /**
     * An entry of the map being written, `Armor = [FLAME = 0.5]`: the constants its key type has, or — where
     * the keys link, `BossGuards` of a `Descent` — the blocks it may name, each with the ` = ` that follows.
     */
    private fun entryKeys(value: DukeValue, result: CompletionResultSet) {
        val field = value.field ?: return
        val block = field.block ?: return
        val record = DukeRecords.recordOf(block) ?: return
        val component = DukeRecords.component(record, field.key)?.takeIf { DukeRecords.isMap(it.type) } ?: return
        val written = field.values.filter { it != value }.mapNotNull { DukeRecords.entryOf(it.unquoted)?.first }
        val link = DukeLinks.linkOf(component)
        val names = link?.let { DukeLinks.blocksOf(it, value).keys.sorted() }
            ?: DukeRecords.constantsOf(DukeRecords.typeArgument(component.type, 0))?.map { it.name }
            ?: return
        for (name in names) {
            if (written.any { it.equals(name, ignoreCase = true) }) continue
            result.addElement(LookupElementBuilder.create(name).withTypeText(link?.name ?: "key").withInsertHandler { context, _ ->
                val at = context.tailOffset
                context.document.insertString(at, " = ")
                context.editor.caretModel.moveToOffset(at + 3)
            })
        }
    }

    /** The record's components not yet written, by the name a file writes them with. */
    private fun keys(record: PsiClass, block: DukeBlock, result: CompletionResultSet, withEquals: Boolean) {
        val written = block.fields.map { it.key.lowercase() } + block.blocks.map { it.wordText.lowercase() }
        for (component in record.recordComponents) {
            if (component.name.lowercase() in written) continue
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

    /** The records a field may be written as: after `Geometry = `, or as an item of `Modules = [`. */
    private fun choices(field: DukeField, result: CompletionResultSet, closing: Boolean) {
        val record = field.block?.let(DukeRecords::recordOf) ?: return
        val component = DukeRecords.component(record, field.key) ?: return
        // An item of a list is a block only where the field is a list: `SkillDistance = [20, 60]` holds numbers.
        if (closing && !DukeRecords.isCollection(component.type)) return
        val type = DukeRecords.blockClass(component.type)?.takeIf(DukeRecords::isChoosable) ?: return
        for ((word, target) in DukeRecords.choices(type, field)) {
            var lookup = LookupElementBuilder.create(target, word).withIcon(target.getIcon(0)).withTypeText(typeText(target))
            if (closing) lookup = lookup.withInsertHandler(::closeBlock)
            result.addElement(lookup)
        }
    }

    private fun typeText(target: PsiElement): String? = (target as? PsiClass)?.qualifiedName?.substringBeforeLast('.')

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
