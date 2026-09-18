package uz.duke.plugin.ini

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.modcommand.Presentation
import com.intellij.modcommand.PsiUpdateModCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.intellij.util.text.EditDistance
import org.jetbrains.jps.model.java.JavaModuleSourceRootTypes
import uz.duke.plugin.DukeBundle

/** What an extension says a file is. */
enum class AssetKind(private val label: String, val extensions: List<String>) {
    MODEL("a model", listOf("glb", "gltf", "obj", "j3o")),
    IMAGE("an image", listOf("png", "jpg", "jpeg", "tga", "dds")),
    AUDIO("a sound", listOf("ogg", "wav", "mp3")),
    FONT("a font", listOf("fnt"));

    override fun toString() = "$label (${extensions.joinToString { ".$it" }})"

    companion object {
        fun of(path: String): AssetKind? {
            val extension = path.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { extension in it.extensions }
        }

        /** What a key's own name promises: `Model`, `CmdMoveIcon`, `TitleFont`. */
        fun named(key: String): AssetKind? = when {
            key.endsWith("model", ignoreCase = true) -> MODEL
            listOf("texture", "image", "icon").any { key.endsWith(it, ignoreCase = true) } -> IMAGE
            key.endsWith("sound", ignoreCase = true) -> AUDIO
            key.endsWith("font", ignoreCase = true) -> FONT
            else -> null
        }
    }
}

/**
 * Asset paths as the game loads them: whole, from the resource root the INI file sits in, the
 * classpath root jME loads from (`Model = models/heroes/rogue.glb`). No folder is put in front of
 * a name, so a game may keep its files in whatever structure it likes.
 */
object DukeAssets {
    fun isPath(word: DukeIniWord) = word.parent is DukeIniField && AssetKind.of(word.text) != null

    /** Null outside a resource root: nothing to check against, so nothing is checked. */
    fun rootOf(element: PsiElement): VirtualFile? {
        val file = element.containingFile?.originalFile?.virtualFile ?: return null
        val index = ProjectFileIndex.getInstance(element.project)
        if (!index.isUnderSourceRootOfType(file, JavaModuleSourceRootTypes.RESOURCES)) return null
        return index.getSourceRootForFile(file)
    }

    /**
     * [path] under [root], its letter case matched exactly: Linux CI tells `Models/` from `models/`,
     * so a lookup Windows would let through must fail here too.
     */
    fun find(root: VirtualFile, path: String): VirtualFile? {
        var at = root
        for (name in path.split('/')) {
            if (name.isEmpty()) continue
            at = at.findChild(name)?.takeIf { it.name == name } ?: return null
        }
        return at.takeIf { !it.isDirectory }
    }

    fun resolve(word: DukeIniWord): VirtualFile? = rootOf(word)?.let { find(it, word.text) }

    /** The key's values in the other blocks of this type: how the file writes it. */
    fun otherValues(field: DukeIniField): List<String> {
        val all = (field.containingFile as? DukeIniFile)?.valuesByKey?.get(keyOf(field)).orEmpty()
        return all.toMutableList().apply { remove(field.valueText) }
    }

    /** The key's own name decides (`Model`); failing that, what its other values are. */
    fun expectedKinds(field: DukeIniField): Set<AssetKind> =
        AssetKind.named(field.keyText)?.let { setOf(it) } ?: otherValues(field).mapNotNull(AssetKind::of).toSet()

    /** The same path in other letter case, or one a few typos away, among files of the same kind. */
    fun suggest(root: VirtualFile, value: String): String? {
        val kind = AssetKind.of(value)
        val candidates = filesUnder(root).filter { AssetKind.of(it) == kind && it != value }
        candidates.firstOrNull { it.equals(value, ignoreCase = true) }?.let { return it }
        return candidates.minByOrNull { EditDistance.levenshtein(it, value, true) }
            ?.takeIf { EditDistance.levenshtein(it, value, true) <= maxOf(2, value.length / 5) }
    }

    fun filesUnder(root: VirtualFile): List<String> {
        val paths = mutableListOf<String>()
        VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
            if (!file.isDirectory) VfsUtilCore.getRelativePath(file, root)?.let(paths::add)
            true
        }
        return paths
    }

    private fun keyOf(field: DukeIniField) = "${(field.parent as? DukeIniSection)?.sectionType}.${field.key}"

    private val DukeIniFile.valuesByKey: Map<String, List<String>>
        get() = CachedValuesManager.getCachedValue(this) {
            val values = PsiTreeUtil.findChildrenOfType(this, DukeIniField::class.java)
                .mapNotNull { field -> field.valueText?.let { keyOf(field) to it } }
                .groupBy({ it.first }, { it.second })
            CachedValueProvider.Result.create(values, this)
        }
}

/** Ctrl+Click on an asset path opens the file. */
class DukeAssetReference(word: DukeIniWord) : PsiReferenceBase<DukeIniWord>(word, TextRange(0, word.textLength), true) {
    override fun resolve(): PsiElement? = DukeAssets.resolve(element)?.let(element.manager::findFile)

    // ponytail: navigation only; renaming or moving an asset leaves the INI line as it was, and the
    // check flags it. Take part in renames (rewrite the path) if that proves a chore.
    override fun isReferenceTo(element: PsiElement) = false
}

/**
 * A missing asset is an error, letter case included; a file of the wrong kind for its key is a
 * warning. Outside a resource root nothing is checked.
 */
class DukeAssetAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is DukeIniWord || !DukeAssets.isPath(element)) return
        val root = DukeAssets.rootOf(element) ?: return
        val path = element.text
        if (DukeAssets.find(root, path) == null) {
            val suggestion = DukeAssets.suggest(root, path)
            val message = if (suggestion == null) DukeBundle.message("asset.not.found", path)
            else DukeBundle.message("asset.not.found.suggest", path, suggestion)
            val annotation = holder.newAnnotation(HighlightSeverity.ERROR, message).range(element)
            if (suggestion != null) annotation.withFix(UseAssetFix(element, suggestion).asIntention())
            annotation.create()
            return
        }
        val field = element.parent as DukeIniField
        val expected = DukeAssets.expectedKinds(field)
        if (expected.isNotEmpty() && AssetKind.of(path) !in expected) {
            val message = DukeBundle.message("asset.wrong.kind", field.keyText, expected.joinToString(" or "), path.substringAfterLast('.'))
            holder.newAnnotation(HighlightSeverity.WARNING, message).range(element).create()
        }
    }
}

class UseAssetFix(word: DukeIniWord, private val path: String) : PsiUpdateModCommandAction<DukeIniWord>(word) {
    override fun getFamilyName() = DukeBundle.message("asset.fix.family")

    override fun getPresentation(context: ActionContext, element: DukeIniWord): Presentation =
        Presentation.of(DukeBundle.message("asset.fix", path))

    override fun invoke(context: ActionContext, element: DukeIniWord, updater: ModPsiUpdater) {
        val file = PsiFileFactory.getInstance(context.project())
            .createFileFromText("dummy.ini", DukeIniFileType, "Dummy A\n  Key = $path\nEnd\n")
        element.replace(PsiTreeUtil.findChildOfType(file, DukeIniField::class.java)!!.valueWord!!)
    }
}

/**
 * After an asset key, every file under the resource root of the kind that key takes, as a whole
 * path. Typing a file name finds it too, wherever it sits. Read from disk on every call, so a file
 * added a moment ago is offered.
 */
class DukeAssetCompletionContributor : CompletionContributor(), DumbAware {
    init {
        extend(
            CompletionType.BASIC,
            psiElement().withParent(DukeIniWord::class.java).withSuperParent(2, DukeIniField::class.java),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                    val field = parameters.position.parent.parent as DukeIniField
                    val root = DukeAssets.rootOf(parameters.originalFile) ?: return
                    val others = DukeAssets.otherValues(field)
                    if (others.isNotEmpty() && others.none { AssetKind.of(it) != null }) return // `FigureIcon = 30`, `Icon = flask`
                    val kinds = DukeAssets.expectedKinds(field).ifEmpty { return }
                    for (path in DukeAssets.filesUnder(root)) {
                        if (AssetKind.of(path) !in kinds) continue
                        result.addElement(LookupElementBuilder.create(path).withLookupString(path.substringAfterLast('/')))
                    }
                }
            },
        )
    }
}

/**
 * The space after `Update =` or `Model =` opens the completion list. Any `Key =` does; where
 * nothing is offered, as after `Speed =`, no list appears.
 */
class DukeIniTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != ' ' || file !is DukeIniFile) return Result.CONTINUE
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
