package uz.duke.plugin.duke

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon
import uz.duke.plugin.duke.DukeTypes as T

/** A word, what is written in it, and — when it is closed — its `End`. */
class DukeBlock(node: ASTNode) : ASTWrapperPsiElement(node) {
    val word: DukeWord
        get() = findNotNullChildByClass(DukeWord::class.java)

    val wordText: String
        get() = word.text

    val isClosed: Boolean
        get() = node.findChildByType(T.END) != null

    val fields: List<DukeField>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, DukeField::class.java)

    val blocks: List<DukeBlock>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, DukeBlock::class.java)

    /** The block this one is written inside; null at the top of a file. */
    val container: DukeBlock?
        get() = parent as? DukeBlock

    fun field(key: String): DukeField? = fields.firstOrNull { it.key.equals(key, ignoreCase = true) }

    /** Its word, and its Name when it has one: `Monster Brute`. */
    val presentableText: String
        get() = listOfNotNull(wordText, field("Name")?.valueText).joinToString(" ")

    override fun getPresentation(): ItemPresentation = PresentationData(presentableText, null, getIcon(0), null)

    override fun getIcon(flags: Int): Icon = if (container == null) AllIcons.Nodes.Class else AllIcons.Json.Object
}

/** A block's word: Ctrl+Click opens the class it is read as. */
class DukeWord(node: ASTNode) : ASTWrapperPsiElement(node) {
    val block: DukeBlock
        get() = parent as DukeBlock

    override fun getReference(): PsiReference = DukeWordReference(this)
}

/** `Key = value`, or `Key = [a, b]`. */
class DukeField(node: ASTNode) : ASTWrapperPsiElement(node) {
    val keyElement: DukeKey
        get() = findNotNullChildByClass(DukeKey::class.java)

    val key: String
        get() = keyElement.text

    /** The block it is written in; null for a field outside any block. */
    val block: DukeBlock?
        get() = parent as? DukeBlock

    val list: DukeList?
        get() = findChildByClass(DukeList::class.java)

    /** Its value, when that is one value and not a list. */
    val value: DukeValue?
        get() = findChildByClass(DukeValue::class.java)

    /** Its value as the engine reads it, quotes taken off: null for a list, or for nothing written. */
    val valueText: String?
        get() = value?.unquoted

    /** Every value it holds: the one, or each item of its list. */
    val values: List<DukeValue>
        get() = list?.items ?: listOfNotNull(value)
}

/** Ctrl+Click opens the record component it fills. */
class DukeKey(node: ASTNode) : ASTWrapperPsiElement(node) {
    val field: DukeField
        get() = parent as DukeField

    override fun getReference(): PsiReference = DukeKeyReference(this)
}

class DukeList(node: ASTNode) : ASTWrapperPsiElement(node) {
    val items: List<DukeValue>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, DukeValue::class.java)

    val isClosed: Boolean
        get() = node.findChildByType(T.RBRACKET) != null
}

/** One value, alone after `=` or an item of a list; it may name an asset or an enum constant. */
class DukeValue(node: ASTNode) : ASTWrapperPsiElement(node) {
    /** As the engine reads it: a quoted value without its quotes, `\x` read as `x`. */
    val unquoted: String
        get() {
            val raw = text
            if (!raw.startsWith('"')) return raw
            val inner = raw.substring(1, if (raw.length > 1 && raw.endsWith('"')) raw.length - 1 else raw.length)
            return buildString {
                var i = 0
                while (i < inner.length) {
                    if (inner[i] == '\\' && i + 1 < inner.length) i++
                    append(inner[i])
                    i++
                }
            }
        }

    val field: DukeField?
        get() = PsiTreeUtil.getParentOfType(this, DukeField::class.java)

    val isInList: Boolean
        get() = parent is DukeList

    override fun getReferences(): Array<PsiReference> = DukeValueReferences.of(this)

    override fun getReference(): PsiReference? = references.firstOrNull()
}

class DukeBadLine(node: ASTNode) : ASTWrapperPsiElement(node)
