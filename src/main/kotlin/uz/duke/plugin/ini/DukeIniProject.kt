package uz.duke.plugin.ini

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * What every Duke INI file in the project says, taken together, and what can be read off it.
 *
 * Much of what the Inspector offers is written nowhere: that `Look` names a block of some type, or
 * that `Floor` is a clip. Every value such a field has is such a name, so that is what is offered.
 * Nothing here names a game: it is read from the files, whatever game they belong to.
 */
class DukeIniProject private constructor(val blocks: List<Block>, private val roots: Map<VirtualFile, VirtualFile>) {
    /** A block as the index keeps it: no PSI, so a cached copy holds nothing it should not. */
    class Block(
        val type: String,
        val names: List<String>,
        val file: VirtualFile,
        val offset: Int,
        val fields: List<Pair<String, String>>,
        /** Its sections, and theirs, each under its [DukeIniSection.sectionType]. */
        val sections: List<Section>,
    ) {
        val name: String? get() = names.singleOrNull()
    }

    class Section(val type: String, val names: List<String>, val fields: List<Pair<String, String>>)

    /** Every section that has fields: blocks, and sections as `world/generation`, as [DukeIniSection.sectionType] spells them. */
    private val sections: Map<String, List<List<Pair<String, String>>>> = buildMap<String, MutableList<List<Pair<String, String>>>> {
        for (block in blocks) {
            getOrPut(block.type.lowercase()) { mutableListOf() } += block.fields
            for (section in block.sections) getOrPut(section.type) { mutableListOf() } += section.fields
        }
    }

    private val namesByType: Map<String, Set<String>> = blocks.filter { it.name != null }
        .groupBy({ it.type }, { it.name!! }).mapValues { it.value.toSet() }

    fun namesOf(type: String): Set<String> = namesByType.entries.firstOrNull { it.key.equals(type, ignoreCase = true) }?.value.orEmpty()

    fun valuesOf(sectionType: String, key: String): List<String> =
        sections[sectionType].orEmpty().flatMap { fields -> fields.filter { it.first.equals(key, ignoreCase = true) }.map { it.second } }

    fun mostCommon(sectionType: String, key: String): String? =
        valuesOf(sectionType, key).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /**
     * The block type a field names, when every value it has anywhere is the name of such a block.
     * The smallest such type wins.
     */
    fun referenceOf(sectionType: String, key: String): String? {
        val values = valuesOf(sectionType, key)
        if (values.isEmpty() || values.any { it.isEmpty() || ' ' in it || NUMBER.matches(it) }) return null
        val distinct = values.toSet()
        return namesByType.entries.filter { it.value.containsAll(distinct) }.minByOrNull { it.value.size }?.key
    }

    /** Every clip name the project's models hold. Read on first use; the files are cached by [DukeClips]. */
    val clips: Set<String> by lazy { modelFiles(blocks).flatMap(DukeClips::namesIn).toSet() }

    /** A field whose every value is a clip some model holds: `Attack = Melee_Attack`. */
    fun isClip(sectionType: String, key: String): Boolean {
        val values = valuesOf(sectionType, key)
        return values.isNotEmpty() && referenceOf(sectionType, key) == null && clips.containsAll(values.toSet())
    }

    /** Clips of the models [block] itself names first, then every other clip. */
    fun clipsFor(block: Block): List<String> = (modelFiles(listOf(block)).flatMap(DukeClips::namesIn) + clips.sorted()).distinct()

    private fun modelFiles(of: List<Block>): List<VirtualFile> = of.flatMap { block ->
        val root = roots[block.file] ?: return@flatMap emptyList()
        (block.fields + block.sections.flatMap { it.fields }).map { it.second }
            .filter { AssetKind.of(it) == AssetKind.MODEL }
            .mapNotNull { DukeAssets.find(root, it) }
    }.distinct()

    /** The resource root [file] sits in, which asset paths start from. */
    fun rootOf(file: VirtualFile): VirtualFile? = roots[file]

    /**
     * The fields at least half of the sections of this type write, each with its most common value,
     * in the order they are usually written: what a new block of the type starts with.
     */
    fun defaults(sectionType: String): List<Pair<String, String>> = commonFields(sections[sectionType].orEmpty())

    /** Fields at least half of [of] write, each with its most common value among them, in their usual order. */
    fun commonFields(of: List<List<Pair<String, String>>>): List<Pair<String, String>> {
        if (of.isEmpty()) return emptyList()
        val writers = of.flatMap { fields -> fields.map { it.first.lowercase() }.distinct() }.groupingBy { it }.eachCount()
        return of.flatMap { fields -> fields.map { it.first } }.distinctBy(String::lowercase)
            .filter { (writers[it.lowercase()] ?: 0) * 2 >= of.size }
            .mapNotNull { key ->
                of.flatMap { fields -> fields.filter { it.first.equals(key, ignoreCase = true) }.map { it.second } }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.let { key to it }
            }
    }

    /** The name sections of this type are most often given: `Layout`, for `world/generation`. */
    fun sectionName(sectionType: String): String? = blocks.flatMap { it.sections }.filter { it.type == sectionType }
        .map { it.names.joinToString(" ") }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    companion object {
        private val NUMBER = Regex("[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?%?|0[xX][0-9a-fA-F]+")
        private val KEY = Key.create<CachedValue<DukeIniProject>>("duke.ini.project")

        /** Every Duke INI file of [project]; rebuilt after any change to any file. Call under a read action. */
        fun of(project: Project): DukeIniProject = CachedValuesManager.getManager(project).getCachedValue(project, KEY, {
            CachedValueProvider.Result.create(build(project), PsiModificationTracker.getInstance(project))
        }, false)

        private fun build(project: Project): DukeIniProject {
            val blocks = mutableListOf<Block>()
            val roots = HashMap<VirtualFile, VirtualFile>()
            for (file in DukeIniDeclarations.files(project)) {
                val virtualFile = file.virtualFile ?: continue
                DukeAssets.rootOf(file)?.let { roots[virtualFile] = it }
                for (block in file.blocks) {
                    blocks += Block(
                        block.blockType, block.header.names.map { it.text }, virtualFile, block.textRange.startOffset,
                        block.fields.map { it.keyText to it.value }, sectionsOf(block),
                    )
                }
            }
            return DukeIniProject(blocks, roots)
        }

        private fun sectionsOf(section: DukeIniSection): List<Section> = section.parts.filterIsInstance<DukeIniSubsection>().flatMap {
            listOf(Section(it.sectionType, it.names.map(DukeIniWord::getText), it.fields.map { field -> field.keyText to field.value })) + sectionsOf(it)
        }
    }
}
