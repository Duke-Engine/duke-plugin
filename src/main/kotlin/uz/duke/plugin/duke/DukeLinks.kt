package uz.duke.plugin.duke

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecordComponent
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiUtil
import uz.duke.plugin.ini.AssetKind
import uz.duke.plugin.ini.DukeAssets
import uz.duke.plugin.ini.DukeClips

/**
 * What a value names beyond its own line, as the game's records mark it: a component marked
 * `@Link(AnimationSet.class)` is the `Name` of a block of that record, `Animations = Humanoid`, and
 * one marked `@Clip` is a clip inside the model and animation files the block is drawn from — those
 * written in it and in the blocks around it, and those of what they link. The clips are read out of
 * the files themselves, so a file that gains one offers it at once. Nothing here names a game.
 */
object DukeLinks {
    private const val CLIP = "uz.duke.core.data.Clip"
    private const val LINK = "uz.duke.core.data.Link"

    /** The component [value] is written for, when its block is a record and it is that field's one value. */
    fun componentOf(value: DukeValue): PsiRecordComponent? {
        if (value.isInList) return null
        val field = value.field ?: return null
        val record = field.block?.let(DukeRecords::recordOf) ?: return null
        return DukeRecords.component(record, field.key)
    }

    fun isClip(component: PsiRecordComponent) = component.hasAnnotation(CLIP)

    /** The record a linking [component] names a block of: `AnimationSet` for `@Link(AnimationSet.class)`. */
    fun linkOf(component: PsiRecordComponent): PsiClass? {
        val named = component.getAnnotation(LINK)?.findAttributeValue("value") as? PsiClassObjectAccessExpression
        return named?.operand?.type?.let(PsiUtil::resolveClassInType)
    }

    /** Every block of [record] at the top of the project's data files, by the name it gives itself. */
    fun blocksOf(record: PsiClass, context: PsiElement): Map<String, DukeBlock> {
        val name = record.qualifiedName ?: return emptyMap()
        return everyNamedBlock(context)[name].orEmpty()
    }

    private fun everyNamedBlock(context: PsiElement): Map<String, Map<String, DukeBlock>> {
        val project = context.project
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val psi = PsiManager.getInstance(project)
            val blocks = FileTypeIndex.getFiles(DukeFileType, GlobalSearchScope.projectScope(project))
                .mapNotNull { psi.findFile(it) as? DukeFile }
                .flatMap { it.blocks }
                .mapNotNull { block ->
                    val record = DukeRecords.recordOf(block)?.qualifiedName ?: return@mapNotNull null
                    val name = block.field("Name")?.valueText ?: return@mapNotNull null
                    Triple(record, name, block)
                }
                .groupBy({ it.first }, { it.second to it.third })
                .mapValues { (_, named) -> named.toMap() }
            CachedValueProvider.Result.create(blocks, PsiModificationTracker.getInstance(project))
        }
    }

    /** Every clip the files [block] is drawn from hold, in the order the files are written, each once. */
    fun clipsFor(block: DukeBlock): List<String> =
        filesOf(block, mutableSetOf()).flatMap { path ->
            val file = DukeAssets.rootOf(path)?.let { DukeAssets.find(it, path.unquoted) }
            if (file == null) emptyList() else DukeClips.namesIn(file)
        }.distinct()

    /** The model and animation files written in [block] and the blocks around it, and in what they link. */
    private fun filesOf(block: DukeBlock, seen: MutableSet<DukeBlock>): List<DukeValue> {
        val files = mutableListOf<DukeValue>()
        var at: DukeBlock? = block
        while (at != null && seen.add(at)) {
            val record = DukeRecords.recordOf(at)
            for (field in at.fields) {
                files += field.values.filter { AssetKind.of(it.unquoted) == AssetKind.MODEL }
                val link = record?.let { DukeRecords.component(it, field.key) }?.let(::linkOf) ?: continue
                val linked = field.valueText?.let { blocksOf(link, field)[it] } ?: continue
                files += filesOf(linked, seen)
            }
            at = at.owner
        }
        return files
    }
}

/** `Animations = Humanoid`: Ctrl+Click opens the block of that name the component links. */
class DukeLinkReference(value: DukeValue, private val record: PsiClass) :
    PsiReferenceBase<DukeValue>(value, TextRange(0, value.textLength), true) {
    override fun resolve(): PsiElement? = DukeLinks.blocksOf(record, element)[element.unquoted]

    override fun getVariants(): Array<Any> = emptyArray()
}
