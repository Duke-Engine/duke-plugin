package uz.dukeengine.plugin.duke

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
import uz.dukeengine.plugin.assets.AssetKind
import uz.dukeengine.plugin.assets.DukeAssets
import uz.dukeengine.plugin.assets.DukeClips

/**
 * What a value names beyond its own line, as the game's records mark it: a component marked
 * `@Link(AnimationSet.class)` is the `Name` of a block of that record, `Animations = Humanoid`, and
 * one marked `@Clip` is a clip inside the model and animation files the block is drawn from — those
 * written in it and in the blocks around it, and those of what they link. The clips are read out of
 * the files themselves, so a file that gains one offers it at once. Nothing here names a game.
 */
object DukeLinks {
    private const val CLIP = "uz.dukeengine.core.data.Clip"
    private const val LINK = "uz.dukeengine.core.data.Link"

    /**
     * The component [value] is written for, when its block is a record: the field's one value, or an item
     * of a field that is a list — each item of `Themes = [Forest, Dungeon]` is what a linking list links.
     */
    fun componentOf(value: DukeValue): PsiRecordComponent? {
        val field = value.field ?: return null
        val record = field.block?.let(DukeRecords::recordOf) ?: return null
        val component = DukeRecords.component(record, field.key) ?: return null
        return if (value.isInList && !DukeRecords.isCollection(component.type)) null else component
    }

    fun isClip(component: PsiRecordComponent) = component.hasAnnotation(CLIP)

    /** The record a linking [component] names a block of: `AnimationSet` for `@Link(AnimationSet.class)`. */
    fun linkOf(component: PsiRecordComponent): PsiClass? {
        val named = component.getAnnotation(LINK)?.findAttributeValue("value") as? PsiClassObjectAccessExpression
        return named?.operand?.type?.let(PsiUtil::resolveClassInType)
    }

    /** Whether [component] holds records each read from one line — `Warden 8 5`, a `Placed` — whose first word a link names. */
    fun isOneLine(component: PsiRecordComponent): Boolean =
        DukeRecords.blockClass(component.type)?.let { it.isRecord && DukeRecords.hasFactory(it) } == true

    /** What of [text] names the block [component] links: a one-line record's first word, else the whole of it. */
    fun linkedName(component: PsiRecordComponent, text: String): String =
        if (isOneLine(component)) text.trim().substringBefore(' ') else text

    /** Every block of [record] at the top of the project's data files, by the name it gives itself. */
    fun blocksOf(record: PsiClass, context: PsiElement): Map<String, DukeBlock> {
        val name = record.qualifiedName ?: return emptyMap()
        return everyNamedBlock(context)[name].orEmpty()
    }

    /** Every block at the top of the project's data files that gives itself a name, whatever its record. */
    fun namedBlocks(context: PsiElement): List<Pair<String, DukeBlock>> = everyNamedBlock(context).values.flatMap { it.toList() }

    /** Every block at the top of the project's files, named or not — the one `Sun` a game lights its maps by is not. */
    fun everyBlock(context: PsiElement): List<DukeBlock> {
        val project = context.project
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val psi = PsiManager.getInstance(project)
            val blocks = FileTypeIndex.getFiles(DukeFileType, GlobalSearchScope.projectScope(project))
                .mapNotNull { psi.findFile(it) as? DukeFile }
                .flatMap { it.blocks }
            CachedValueProvider.Result.create(blocks, PsiModificationTracker.getInstance(project))
        }
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
    fun clipsFor(block: DukeBlock): List<String> = clipFiles(block).flatMap { it.second }.distinct()

    /** The files [block] is drawn from, each with the clips it holds, in the order they are written. */
    fun clipFiles(block: DukeBlock): List<Pair<String, List<String>>> =
        filesOf(block, mutableSetOf()).distinctBy { it.unquoted }.mapNotNull { path ->
            val file = DukeAssets.resolve(path, path.unquoted) ?: return@mapNotNull null
            path.unquoted to DukeClips.namesIn(file)
        }

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
class DukeLinkReference(value: DukeValue, private val record: PsiClass, private val name: String) :
    PsiReferenceBase<DukeValue>(value, rangeOf(value, name), true) {
    override fun resolve(): PsiElement? = DukeLinks.blocksOf(record, element)[name]

    override fun getVariants(): Array<Any> = emptyArray()
}

/** Where [name] is in [value]'s text: all of it, or the first word of `Warden 8 5`. */
private fun rangeOf(value: DukeValue, name: String): TextRange {
    val at = value.text.indexOf(name)
    return if (at < 0 || name.isEmpty()) TextRange(0, value.textLength) else TextRange(at, at + name.length)
}
