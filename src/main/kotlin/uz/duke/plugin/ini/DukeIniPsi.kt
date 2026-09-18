package uz.duke.plugin.ini

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.lang.ASTNode
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import uz.duke.plugin.ini.DukeIniTypes as T

/** A block or an Object's module: a first line, fields, and (if closed) an End. */
abstract class DukeIniSection(node: ASTNode) : ASTWrapperPsiElement(node) {
    val isClosed: Boolean
        get() = node.findChildByType(T.END) != null

    val fields: List<DukeIniField>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, DukeIniField::class.java)

    /** The first line, without its comment. */
    val headerRange: TextRange
        get() {
            var end = textRange.startOffset
            for (child in node.getChildren(null)) {
                if (child.psi is PsiWhiteSpace) {
                    if (child.textContains('\n')) break
                } else if (child.elementType != T.COMMENT) {
                    end = child.textRange.endOffset
                }
            }
            return TextRange(textRange.startOffset, end)
        }

    /** The first line as written, spacing normalised: `Object Rogue`, `Update = MoveUpdate Tag`. */
    abstract val presentableText: String

    /** What kind of section this is, lower case: `dungeonhero`, `module moveupdate`. */
    abstract val sectionType: String

    override fun getPresentation(): ItemPresentation = PresentationData(presentableText, null, getIcon(0), null)
}

class DukeIniBlock(node: ASTNode) : DukeIniSection(node), PsiNameIdentifierOwner {
    val header: DukeIniHeader
        get() = PsiTreeUtil.getChildOfType(this, DukeIniHeader::class.java)!!

    val blockType: String
        get() = header.blockType

    val modules: List<DukeIniModule>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, DukeIniModule::class.java)

    override val presentableText: String
        get() = header.words.joinToString(" ")

    override val sectionType: String
        get() = blockType.lowercase()

    /** A one-name header declares that name; `DungeonSkill Rogue Q` only points at Rogue. */
    override fun getNameIdentifier(): DukeIniWord? = header.names.singleOrNull()

    override fun getName(): String? = nameIdentifier?.text

    override fun setName(name: String): PsiElement = throw IncorrectOperationException("Renaming INI blocks is not supported yet")

    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun getIcon(flags: Int) =
        if (blockType.equals("Object", ignoreCase = true)) AllIcons.Nodes.Class else AllIcons.Json.Object
}

class DukeIniModule(node: ASTNode) : DukeIniSection(node) {
    /** `MoveUpdate` in `Update = MoveUpdate Tag`; null while the line has no name yet. */
    val moduleName: DukeIniModuleName?
        get() = PsiTreeUtil.getChildOfType(this, DukeIniModuleName::class.java)

    override val presentableText: String
        get() {
            val words = node.getChildren(null).takeWhile { it.elementType != T.FIELD_ELEMENT && it.elementType != T.END }
                .filter { it.elementType in LINE_WORDS }.map { it.text }
            return (listOf(words.first(), "=") + words.drop(1)).joinToString(" ")
        }

    override val sectionType: String
        get() = "module " + moduleName?.text.orEmpty().lowercase()

    override fun getIcon(flags: Int) = AllIcons.Nodes.Plugin

    private companion object {
        val LINE_WORDS = TokenSet.create(T.MODULE_KEY, T.MODULE_NAME_ELEMENT, T.VALUE, T.NUMBER, T.STRING)
    }
}

/** Points at the engine class the module is built from; see [DukeModuleReference]. */
class DukeIniModuleName(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun getReference(): PsiReference = DukeModuleReference(this)
    override fun getReferences(): Array<PsiReference> = arrayOf(reference)
}

class DukeIniHeader(node: ASTNode) : ASTWrapperPsiElement(node) {
    val blockType: String
        get() = firstChild.text

    val names: List<DukeIniWord>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, DukeIniWord::class.java)

    /** Block type followed by every name, quoted ones included. */
    val words: List<String>
        get() = node.getChildren(WORDS).map { it.text }

    private companion object {
        val WORDS = TokenSet.create(T.BLOCK_TYPE, T.NAME_ELEMENT, T.STRING)
    }
}

class DukeIniField(node: ASTNode) : ASTWrapperPsiElement(node) {
    val keyText: String
        get() = firstChild.text

    /** Lower case: the engine looks fields up case-insensitively. */
    val key: String
        get() = keyText.lowercase()

    /** `ui/click_2.ogg` in `File = ui/click_2.ogg`: the value as a word, when it is one. */
    val valueWord: DukeIniWord?
        get() = PsiTreeUtil.getChildOfType(this, DukeIniWord::class.java)

    /** The first value on the line, number or word: `30` in `FigureIcon = 30`. */
    val valueText: String?
        get() = node.findChildByType(VALUES)?.text

    private companion object {
        val VALUES = TokenSet.create(T.VALUE_ELEMENT, T.NUMBER, T.STRING)
    }
}

class DukeIniBadLine(node: ASTNode) : ASTWrapperPsiElement(node)

/** A block name in a header or a field value; either may name another block, and a value may name an asset. */
class DukeIniWord(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun getReferences(): Array<PsiReference> {
        if (DukeAssets.isPath(this)) return arrayOf(DukeAssetReference(this))
        val header = parent as? DukeIniHeader
        return if (header == null || header.names.size > 1) arrayOf<PsiReference>(DukeIniReference(this)) else PsiReference.EMPTY_ARRAY
    }

    override fun getReference(): PsiReference? = references.firstOrNull()
}

/**
 * Soft: most values (`Speed = 27.2`, `Kind = MANA`) name nothing, so an unresolved
 * one is not an error. A resolved one may have several targets — `Rogue` is an
 * `Object`, a `DungeonHero` and a `DungeonPortrait` — and Objects come first.
 */
class DukeIniReference(element: DukeIniWord) :
    PsiPolyVariantReferenceBase<DukeIniWord>(element, TextRange(0, element.textLength), true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ResolveCache.getInstance(element.project).resolveWithCaching(this, RESOLVER, false, incompleteCode)

    override fun getVariants(): Array<Any> = emptyArray()

    private companion object {
        val RESOLVER = ResolveCache.PolyVariantResolver<DukeIniReference> { ref, _ ->
            val own = PsiTreeUtil.getParentOfType(ref.element, DukeIniBlock::class.java)
            PsiElementResolveResult.createResults(DukeIniDeclarations.find(ref.element.project, ref.element.text).filter { it != own })
        }
    }
}

object DukeIniDeclarations {
    // ponytail: walks every INI file per lookup (each file caches its own map); a stub index when projects hold hundreds.
    fun find(project: Project, name: String): List<DukeIniBlock> =
        files(project)
            .flatMap { it.declarations[name].orEmpty() }
            .sortedBy { if (it.blockType.equals("Object", ignoreCase = true)) 0 else 1 }

    fun files(project: Project): List<DukeIniFile> {
        val psiManager = PsiManager.getInstance(project)
        return FileTypeIndex.getFiles(DukeIniFileType, GlobalSearchScope.projectScope(project))
            .sortedBy { it.path }
            .mapNotNull { psiManager.findFile(it) as? DukeIniFile }
    }
}

class DukeIniFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner =
        object : DefaultWordsScanner(
            DukeIniLexer(), TokenSet.create(T.NAME, T.VALUE, T.MODULE_NAME), TokenSet.create(T.COMMENT), TokenSet.create(T.STRING),
        ) {
            // Module names joined the word index in version 1: Find Usages and Rename of a module class reach INI files.
            override fun getVersion() = 1
        }

    override fun canFindUsagesFor(element: PsiElement) = element is DukeIniBlock && element.name != null
    override fun getHelpId(element: PsiElement): String? = null
    override fun getType(element: PsiElement) = (element as? DukeIniBlock)?.blockType.orEmpty()
    override fun getDescriptiveName(element: PsiElement) = (element as? DukeIniBlock)?.presentableText.orEmpty()
    override fun getNodeText(element: PsiElement, useFullName: Boolean) = getDescriptiveName(element)
}
