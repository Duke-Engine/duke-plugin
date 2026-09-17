package uz.duke.plugin.engine

import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementManipulators
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import uz.duke.plugin.DukeBundle
import uz.duke.plugin.ini.DukeIniDeclarations
import uz.duke.plugin.ini.DukeIniModuleName

/**
 * `register("mover", ...)` building a `MoveUpdate`: the engine accepts it, but then the INI name
 * is not the class name, and the class name is what the plugin offers and checks INI files against.
 */
class DukeModuleRegistrationInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : JavaElementVisitor() {
        override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
            if (call.methodExpression.referenceName != "register") return
            val args = call.argumentList.expressions
            val literal = args.firstOrNull() as? PsiLiteralExpression ?: return
            val registered = literal.value as? String ?: return
            if (call.resolveMethod()?.containingClass?.qualifiedName != DukeModules.FACTORY_CLASS) return
            val module = builtClass(args.drop(1)) ?: return
            val className = module.name ?: return
            if (registered == className) return
            if (DukeModules.prefixOf(module) != null) return // named per registration, by design
            holder.registerProblem(
                literal,
                DukeBundle.message("inspection.registration.problem", registered, className),
                MatchClassNameFix(registered, className),
            )
        }
    }

    /** The one module class the builder and parser arguments name, whatever form they take. */
    private fun builtClass(args: List<PsiExpression>): PsiClass? =
        args.flatMap(::classesIn)
            .filter { InheritanceUtil.isInheritor(it, DukeModules.MODULE_CLASS) }
            .distinctBy { it.qualifiedName }
            .singleOrNull()

    private fun classesIn(arg: PsiExpression): List<PsiClass> = when (arg) {
        is PsiClassObjectAccessExpression -> listOfNotNull(PsiUtil.resolveClassInType(arg.operand.type))
        is PsiMethodReferenceExpression -> listOfNotNull(
            when (val target = arg.resolve()) {
                is PsiMethod -> target.containingClass // MoveUpdate::parseData, Swing::new
                is PsiClass -> target // Foo::new with no constructor written
                else -> null
            },
        )
        else -> PsiTreeUtil.collectElementsOfType(arg, PsiNewExpression::class.java)
            .mapNotNull { it.classReference?.resolve() as? PsiClass }
    }
}

/** Registers the module under its class name, and renames the INI lines that used the old one. */
class MatchClassNameFix(private val registered: String, private val className: String) : LocalQuickFix {
    override fun getName() = DukeBundle.message("inspection.registration.fix", className)

    override fun getFamilyName() = DukeBundle.message("inspection.registration.fix.family")

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val literal = descriptor.psiElement as? PsiLiteralExpression ?: return
        literal.replace(JavaPsiFacade.getElementFactory(project).createExpressionFromText("\"$className\"", literal))
        if (IntentionPreviewUtils.isIntentionPreviewActive()) return // the preview shows this file only
        DukeIniDeclarations.files(project)
            .flatMap { PsiTreeUtil.findChildrenOfType(it, DukeIniModuleName::class.java) }
            .filter { it.text == registered }
            .forEach { ElementManipulators.handleContentChange(it, className) }
    }
}
