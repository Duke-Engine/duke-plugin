package uz.duke.plugin.ini

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.AbstractElementManipulator
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import uz.duke.plugin.DukeBundle
import uz.duke.plugin.engine.DukeModules
import uz.duke.plugin.ini.DukeIniTypes as T

/** Ctrl+Click on `MoveUpdate` in `Update = MoveUpdate Tag` opens the engine class. */
class DukeModuleReference(element: DukeIniModuleName) :
    PsiReferenceBase<DukeIniModuleName>(element, TextRange(0, element.textLength), true) {
    override fun resolve(): PsiElement? = DukeModules.of(element)?.find(element.text)?.psiClass
}

/** Lets a rename of the module class, or [uz.duke.plugin.engine.MatchClassNameFix], rewrite the name. */
class DukeIniModuleNameManipulator : AbstractElementManipulator<DukeIniModuleName>() {
    override fun handleContentChange(element: DukeIniModuleName, range: TextRange, newContent: String): DukeIniModuleName {
        val line = "Object A\n  Update = ${range.replace(element.text, newContent)}\n  End\nEnd\n"
        val file = PsiFileFactory.getInstance(element.project).createFileFromText("dummy.ini", DukeIniFileType, line)
        return element.replace(PsiTreeUtil.findChildOfType(file, DukeIniModuleName::class.java)!!) as DukeIniModuleName
    }
}

/** Module names after `Update =` and its kin; inside a module block, the fields its class reads. */
class DukeModuleCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, psiElement().withParent(DukeIniModuleName::class.java), provider { parameters, result ->
            val engine = DukeModules.of(parameters.originalFile) ?: return@provider
            for (module in engine.modules) {
                result.addElement(
                    LookupElementBuilder.createWithSmartPointer(module.name, module.psiClass)
                        .withIcon(module.psiClass.getIcon(0))
                        .withTailText(if (module.isPrefix) "<name>" else null, true)
                        .withTypeText(module.psiClass.superClass?.name),
                )
            }
        })
        extend(CompletionType.BASIC, psiElement(T.KEY).withSuperParent(2, DukeIniModule::class.java), provider { parameters, result ->
            val name = PsiTreeUtil.getParentOfType(parameters.position, DukeIniModule::class.java)?.moduleName?.text ?: return@provider
            val fields = DukeModules.of(parameters.originalFile)?.find(name)?.fields ?: return@provider
            fields.forEach { result.addElement(LookupElementBuilder.create(it).withTypeText(name)) }
        })
    }

    override fun handleEmptyLookup(parameters: CompletionParameters, editor: Editor): String? {
        val inModule = PsiTreeUtil.getParentOfType(parameters.position, DukeIniModule::class.java) != null
        return if (inModule && DukeModules.of(parameters.originalFile) == null) DukeBundle.message("engine.not.found") else null
    }

    private inline fun provider(crossinline add: (CompletionParameters, CompletionResultSet) -> Unit) =
        object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) =
                add(parameters, result)
        }
}

/**
 * What only the engine can tell: a module name no class carries, a field its class does not read.
 * Without the engine on the classpath there is nothing to check against, so nothing is flagged.
 */
class DukeModuleAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is DukeIniModuleName -> {
                val engine = DukeModules.of(element) ?: return
                if (engine.find(element.text) == null) {
                    holder.problem(HighlightSeverity.ERROR, element, "annotator.unknown.module", element.text)
                }
            }
            is DukeIniField -> {
                val module = (element.parent as? DukeIniModule)?.moduleName?.text ?: return
                val fields = DukeModules.of(element)?.find(module)?.fields ?: return
                // The engine's field tables ignore case.
                if (fields.none { it.equals(element.keyText, ignoreCase = true) }) {
                    holder.problem(HighlightSeverity.WARNING, element.firstChild, "annotator.unknown.field", element.keyText, module)
                }
            }
        }
    }

    private fun AnnotationHolder.problem(severity: HighlightSeverity, at: PsiElement, key: String, vararg params: Any) {
        newAnnotation(severity, DukeBundle.message(key, *params)).range(at).create()
    }
}
