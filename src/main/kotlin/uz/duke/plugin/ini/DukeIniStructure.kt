package uz.duke.plugin.ini

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.ASTNode
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

class DukeIniStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder =
        object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = DukeIniStructureViewModel(psiFile, editor)
        }
}

/** Blocks, and under each the sections it is made of. */
class DukeIniStructureViewModel(file: PsiFile, editor: Editor?) :
    StructureViewModelBase(file, editor, DukeIniStructureElement(file)), StructureViewModel.ElementInfoProvider {

    init {
        withSuitableClasses(DukeIniSection::class.java)
    }

    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)
    override fun isAlwaysShowsPlus(element: StructureViewTreeElement) = false
    override fun isAlwaysLeaf(element: StructureViewTreeElement) = (element.value as? DukeIniSection)?.parts?.isEmpty() == true
}

class DukeIniStructureElement(private val element: NavigatablePsiElement) : StructureViewTreeElement, SortableTreeElement {
    override fun getValue() = element
    override fun navigate(requestFocus: Boolean) = element.navigate(requestFocus)
    override fun canNavigate() = element.canNavigate()
    override fun canNavigateToSource() = element.canNavigateToSource()
    override fun getAlphaSortKey() = (element as? DukeIniSection)?.presentableText ?: element.name.orEmpty()

    override fun getPresentation(): ItemPresentation =
        if (element is PsiFile) PresentationData(element.name, null, element.getIcon(0), null)
        else element.presentation ?: PresentationData()

    override fun getChildren(): Array<TreeElement> {
        val children = when (element) {
            is DukeIniFile -> element.blocks
            is DukeIniSection -> element.parts
            else -> emptyList()
        }
        return children.map { DukeIniStructureElement(it) }.toTypedArray<TreeElement>()
    }
}

/** Folds a block, or a section, down to its first line (a comment on that line stays visible). */
class DukeIniFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> =
        PsiTreeUtil.findChildrenOfType(root, DukeIniSection::class.java)
            .filter { it.isClosed }
            .mapNotNull { section ->
                val firstLine = document.getLineNumber(section.textRange.startOffset)
                if (firstLine == document.getLineNumber(section.textRange.endOffset)) return@mapNotNull null
                FoldingDescriptor(section.node, TextRange(document.getLineEndOffset(firstLine), section.textRange.endOffset))
            }
            .toTypedArray()

    override fun getPlaceholderText(node: ASTNode) = "..."
    override fun isCollapsedByDefault(node: ASTNode) = false
}
