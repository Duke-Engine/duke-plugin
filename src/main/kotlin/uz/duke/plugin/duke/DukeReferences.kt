package uz.duke.plugin.duke

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.openapi.util.TextRange
import com.intellij.psi.AbstractElementManipulator
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecordComponent
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiType
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.tree.TokenSet
import com.intellij.util.Processor
import uz.duke.plugin.ini.AssetKind
import uz.duke.plugin.ini.DukeAssets
import uz.duke.plugin.duke.DukeTypes as T

/**
 * Soft: an unknown word is the annotator's to report, in the engine's own words. A word is a
 * reference to its class only where it spells the class's name: `MoveUpdate`, `Cylinder`, `Monster`.
 * `Portrait` opens `PortraitArt` too, but renaming that class leaves the word alone, since the word
 * is the component's.
 */
class DukeWordReference(word: DukeWord) : PsiReferenceBase<DukeWord>(word, TextRange(0, word.textLength), true) {
    override fun resolve(): PsiElement? = DukeRecords.shapeOf(element.block)?.target

    override fun isReferenceTo(target: PsiElement) =
        target is PsiNamedElement && target.name.equals(element.text, ignoreCase = true) && super.isReferenceTo(target)

    override fun handleElementRename(newElementName: String): PsiElement =
        super.handleElementRename(if (resolve() is PsiRecordComponent) DukeRecords.capitalized(newElementName) else newElementName)
}

/** `MaxHealth` opens `ActiveBody.Data.maxHealth`; in a map's block, `FLAME` opens the enum constant. */
class DukeKeyReference(key: DukeKey) : PsiReferenceBase<DukeKey>(key, TextRange(0, key.textLength), true) {
    override fun resolve(): PsiElement? {
        val block = element.field.block ?: return null
        return when (val shape = DukeRecords.shapeOf(block)) {
            is DukeShape.Record -> DukeRecords.component(shape.record, element.text)
            is DukeShape.Entries -> constant(DukeRecords.typeArgument(shape.component.type, 0), element.text)
            null -> null
        }
    }

    override fun isReferenceTo(target: PsiElement) =
        target is PsiNamedElement && target.name.equals(element.text, ignoreCase = true) && super.isReferenceTo(target)

    override fun handleElementRename(newElementName: String): PsiElement =
        super.handleElementRename(if (resolve() is PsiRecordComponent) DukeRecords.capitalized(newElementName) else newElementName)
}

/** A value naming one of its enum's constants: `Effect = STRIKE`. */
class DukeConstantReference(value: DukeValue, private val type: PsiType) :
    PsiReferenceBase<DukeValue>(value, TextRange(0, value.textLength), true) {
    override fun resolve(): PsiElement? = constant(type, element.unquoted)

    override fun getVariants(): Array<Any> = emptyArray()
}

/** A path, whole from the resource root: Ctrl+Click opens the file. */
class DukeFileReference(value: DukeValue) : PsiReferenceBase<DukeValue>(value, TextRange(0, value.textLength), true) {
    override fun resolve(): PsiElement? =
        DukeAssets.rootOf(element)?.let { DukeAssets.find(it, element.unquoted) }?.let(element.manager::findFile)

    // ponytail: navigation only; moving an asset leaves the line as it was, and the check flags it.
    override fun isReferenceTo(element: PsiElement) = false
}

object DukeValueReferences {
    fun of(value: DukeValue): Array<PsiReference> {
        if (AssetKind.of(value.unquoted) != null) return arrayOf(DukeFileReference(value))
        val type = typeOf(value) ?: return PsiReference.EMPTY_ARRAY
        return if (DukeRecords.constantsOf(type) != null) arrayOf(DukeConstantReference(value, type)) else PsiReference.EMPTY_ARRAY
    }

    /** What [value] is read as: its component's type, an item's element type, a map's value type. */
    fun typeOf(value: DukeValue): PsiType? {
        val field = value.field ?: return null
        val block = field.block ?: return null
        val type = when (val shape = DukeRecords.shapeOf(block)) {
            is DukeShape.Record -> DukeRecords.component(shape.record, field.key)?.type
            is DukeShape.Entries -> DukeRecords.typeArgument(shape.component.type, 1)
            null -> null
        } ?: return null
        if (!value.isInList) return type
        DukeRecords.elementOf(type)?.let { return it }
        // A record written as its components in order: `SkillDistance = [20, 60]`.
        val record = DukeRecords.classOf(type)?.takeIf { it.isRecord } ?: return null
        val at = field.list?.items?.indexOf(value) ?: return null
        return record.recordComponents.getOrNull(at)?.type
    }
}

private fun constant(type: PsiType?, name: String): PsiEnumConstant? =
    DukeRecords.constantsOf(type)?.firstOrNull { it.name.equals(name, ignoreCase = true) }

/** A word, a key or a value rewritten by a rename: each is one token. */
class DukeLeafManipulator : AbstractElementManipulator<ASTWrapperPsiElement>() {
    override fun handleContentChange(element: ASTWrapperPsiElement, range: TextRange, newContent: String): ASTWrapperPsiElement {
        (element.firstChild as LeafPsiElement).replaceWithText(range.replace(element.text, newContent))
        return element
    }
}

/**
 * A key is its component's name with a capital, `SenseRadius` for `senseRadius`, which a search for
 * the component, being Java's, would not find: so the data files are searched for that word too.
 */
class DukeKeySearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val component = parameters.elementToSearch as? PsiRecordComponent ?: return
        val scope = (parameters.effectiveSearchScope as? GlobalSearchScope)
            ?.let { GlobalSearchScope.getScopeRestrictedByFileTypes(it, DukeFileType) } ?: parameters.effectiveSearchScope
        parameters.optimizer.searchWord(DukeRecords.capitalized(component.name), scope, UsageSearchContext.IN_CODE, false, component)
    }
}

/** Words, keys and values join the word index, so Find Usages and Rename of a class reach `.duke` files. */
class DukeFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner =
        DefaultWordsScanner(DukeLexer(), TokenSet.create(T.WORD, T.KEY, T.VALUE), TokenSet.create(T.COMMENT), TokenSet.create(T.STRING))

    override fun canFindUsagesFor(element: PsiElement) = false
    override fun getHelpId(element: PsiElement): String? = null
    override fun getType(element: PsiElement) = ""
    override fun getDescriptiveName(element: PsiElement) = ""
    override fun getNodeText(element: PsiElement, useFullName: Boolean) = ""
}
