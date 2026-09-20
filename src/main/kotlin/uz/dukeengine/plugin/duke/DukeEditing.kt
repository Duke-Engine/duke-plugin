package uz.dukeengine.plugin.duke

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
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
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
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import uz.dukeengine.plugin.duke.DukeTypes as T

class DukeStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder =
        object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = DukeStructureViewModel(psiFile, editor)
        }
}

/** Blocks, and under each the blocks it holds: `Monster Brute`, then `Geometry = Cylinder`, its modules, its `Skill`s. */
class DukeStructureViewModel(file: PsiFile, editor: Editor?) :
    StructureViewModelBase(file, editor, DukeStructureElement(file)), StructureViewModel.ElementInfoProvider {

    init {
        withSuitableClasses(DukeBlock::class.java)
    }

    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)
    override fun isAlwaysShowsPlus(element: StructureViewTreeElement) = false
    override fun isAlwaysLeaf(element: StructureViewTreeElement) = (element.value as? DukeBlock)?.parts?.isEmpty() == true
}

class DukeStructureElement(private val element: NavigatablePsiElement) : StructureViewTreeElement, SortableTreeElement {
    override fun getValue() = element
    override fun navigate(requestFocus: Boolean) = element.navigate(requestFocus)
    override fun canNavigate() = element.canNavigate()
    override fun canNavigateToSource() = element.canNavigateToSource()
    override fun getAlphaSortKey() = (element as? DukeBlock)?.presentableText ?: element.name.orEmpty()

    override fun getPresentation(): ItemPresentation =
        if (element is PsiFile) PresentationData(element.name, null, element.getIcon(0), null)
        else element.presentation ?: PresentationData()

    override fun getChildren(): Array<TreeElement> {
        val children = when (element) {
            is DukeFile -> element.blocks
            is DukeBlock -> element.parts
            else -> emptyList()
        }
        return children.map { DukeStructureElement(it) }.toTypedArray<TreeElement>()
    }
}

/** A block folds to its word (a comment on that line stays in view), and a list over several lines to `[...]`. */
class DukeFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val blocks = PsiTreeUtil.findChildrenOfType(root, DukeBlock::class.java).filter { it.isClosed }.mapNotNull { block ->
            val firstLine = document.getLineNumber(block.textRange.startOffset)
            if (firstLine == document.getLineNumber(block.textRange.endOffset)) return@mapNotNull null
            FoldingDescriptor(block.node, TextRange(document.getLineEndOffset(firstLine), block.textRange.endOffset))
        }
        val lists = PsiTreeUtil.findChildrenOfType(root, DukeList::class.java).filter { list ->
            list.isClosed && document.getLineNumber(list.textRange.startOffset) != document.getLineNumber(list.textRange.endOffset)
        }.map { FoldingDescriptor(it.node, it.textRange) }
        return (blocks + lists).toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode) = if (node.elementType == T.LIST || node.elementType == T.BLOCK_LIST) "[...]" else "..."
    override fun isCollapsedByDefault(node: ASTNode) = false
}

class DukeCommenter : Commenter {
    override fun getLineCommentPrefix() = "; "
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class DukeBraceMatcher : PairedBraceMatcher {
    override fun getPairs() = arrayOf(BracePair(T.LBRACKET, T.RBRACKET, false))
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int) = openingBraceOffset
}
