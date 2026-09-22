package uz.dukeengine.plugin.duke

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon
import uz.dukeengine.plugin.duke.DukeTypes as T

/**
 * A word, what is written in it, and — when it is closed — its `End`. A block is one of three: at the
 * top of a file; the value of a field, `Geometry = Cylinder` or an item of `Modules = [ … ]`; or a
 * block of entries written on its own inside another, named by its field.
 */
class DukeBlock(node: ASTNode) : ASTWrapperPsiElement(node) {
    val word: DukeWord
        get() = findNotNullChildByClass(DukeWord::class.java)

    val wordText: String
        get() = word.text

    val isClosed: Boolean
        get() = node.findChildByType(T.END) != null

    /**
     * Whether its `End` carries the comma that holds one block of a list apart from the next. The only
     * comma a block owns: the ones in `Key = [a, b]` belong to the list of its field.
     */
    val hasComma: Boolean
        get() = node.findChildByType(T.COMMA) != null

    val fields: List<DukeField>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, DukeField::class.java)

    /** The blocks written on their own inside it: its maps. */
    val blocks: List<DukeBlock>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, DukeBlock::class.java)

    /** The field this block is the value of, or an item of: `Geometry` for `Geometry = Cylinder`. */
    val owningField: DukeField?
        get() = when (val parent = parent) {
            is DukeField -> parent
            is DukeList -> parent.parent as? DukeField
            else -> null
        }

    /** The block whose record this one is part of; null at the top of a file. */
    val owner: DukeBlock?
        get() = parent as? DukeBlock ?: owningField?.block

    /** Every block under it, in the order written: its values and list items, and its maps. */
    val parts: List<DukeBlock>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, DukeField::class.java).flatMap { it.blocks } + blocks

    fun field(key: String): DukeField? = fields.firstOrNull { it.key.equals(key, ignoreCase = true) }

    /** As the structure view says it: `Monster Brute`, `Geometry = Cylinder`, `Layer Fire`. */
    val presentableText: String
        get() {
            val own = listOfNotNull(wordText, field("Name")?.valueText).joinToString(" ")
            val holder = owningField
            return if (holder != null && parent is DukeField) "${holder.key} = $own" else own
        }

    override fun getPresentation(): ItemPresentation = PresentationData(presentableText, null, getIcon(0), null)

    override fun getIcon(flags: Int): Icon = if (owner == null) AllIcons.Nodes.Class else AllIcons.Json.Object
}

/** A block's word, or the class after a field's `=`: Ctrl+Click opens the class it is read as. */
class DukeWord(node: ASTNode) : ASTWrapperPsiElement(node) {
    val block: DukeBlock
        get() = parent as DukeBlock

    override fun getReference(): PsiReference = DukeWordReference(this)
}

/** `Key = value`, `Key = [a, b]`, `Key = Class` with its fields under it, or `Key = [` blocks `]`. */
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

    /** The record written after its `=`, when its value is one: `Cylinder` and what is under it. */
    val nested: DukeBlock?
        get() = findChildByClass(DukeBlock::class.java)

    /** Its value, when that is one value and not a list or a record. */
    val value: DukeValue?
        get() = findChildByClass(DukeValue::class.java)

    /** Its value as the engine reads it, quotes taken off: null for a list, a record or nothing written. */
    val valueText: String?
        get() = value?.unquoted

    /** Every value it holds: the one, or each item of its list of values. */
    val values: List<DukeValue>
        get() = list?.items ?: listOfNotNull(value)

    /** The blocks it holds: the record after its `=`, or the items of its list of blocks. */
    val blocks: List<DukeBlock>
        get() = listOfNotNull(nested) + list?.blocks.orEmpty()
}

/** Ctrl+Click opens the record component it fills. */
class DukeKey(node: ASTNode) : ASTWrapperPsiElement(node) {
    val field: DukeField
        get() = parent as DukeField

    override fun getReference(): PsiReference = DukeKeyReference(this)
}

/** `[a, b]`, or `[` with a block for each item and `]` on a line of its own. */
class DukeList(node: ASTNode) : ASTWrapperPsiElement(node) {
    val items: List<DukeValue>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, DukeValue::class.java)

    val blocks: List<DukeBlock>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, DukeBlock::class.java)

    /** Whether it holds blocks rather than values. */
    val holdsBlocks: Boolean
        get() = node.elementType == T.BLOCK_LIST

    val isClosed: Boolean
        get() = node.findChildByType(T.RBRACKET) != null || node.findChildByType(T.LIST_END) != null
}

/** One value, alone after `=` or an item of a list; it may name an asset, an enum constant or a record. */
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
