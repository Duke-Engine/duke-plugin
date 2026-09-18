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
 * Much of what the Inspector offers is written nowhere: that `Look` names a `DungeonEffect`, that
 * `died.Skeleton` is the Skeleton's death sound, that a monster's file holds a `DungeonMonster` block.
 * Every `Look` in the project is an effect's name and every monster has the block, so that is what
 * is offered. Nothing here names a game: it is read from the files, whatever game they belong to.
 */
class DukeIniProject private constructor(val blocks: List<Block>, private val roots: Map<VirtualFile, VirtualFile>) {
    /** A block as the index keeps it: no PSI, so a cached copy holds nothing it should not. */
    class Block(
        val type: String,
        val names: List<String>,
        val file: VirtualFile,
        val offset: Int,
        val fields: List<Pair<String, String>>,
        val modules: List<Module>,
        /** Its sections, and theirs, each under its [DukeIniSection.sectionType]. */
        val sections: List<Section>,
    ) {
        val name: String? get() = names.singleOrNull()
        val label: String get() = (listOf(type) + names).joinToString(" ")
    }

    class Module(val key: String, val name: String, val fields: List<Pair<String, String>>)

    class Section(val type: String, val names: List<String>, val fields: List<Pair<String, String>>)

    /** Every section that has fields: blocks, modules as `module <name>`, sections as `world/generation`, as [DukeIniSection.sectionType] spells them. */
    private val sections: Map<String, List<List<Pair<String, String>>>> = buildMap<String, MutableList<List<Pair<String, String>>>> {
        for (block in blocks) {
            getOrPut(block.type.lowercase()) { mutableListOf() } += block.fields
            for (module in block.modules) getOrPut("module " + module.name.lowercase()) { mutableListOf() } += module.fields
            for (section in block.sections) getOrPut(section.type) { mutableListOf() } += section.fields
        }
    }

    private val namesByType: Map<String, Set<String>> = blocks.filter { it.name != null }
        .groupBy({ it.type }, { it.name!! }).mapValues { it.value.toSet() }

    /**
     * The block types a thing is built from: `Object`, and every type whose blocks carry modules
     * somewhere in the project — a game's `Monster`, `Hero`. Most used first.
     */
    val unitTypes: List<String> = (blocks.filter { it.modules.isNotEmpty() }.map { it.type } + OBJECT)
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    fun isUnit(block: Block) = block.type in unitTypes

    /** The names of every unit: what a companion, a skill or a sound is named after. */
    val unitNames: Set<String> = blocks.filter(::isUnit).mapNotNull { it.name }.toSet()

    fun namesOf(type: String): Set<String> = namesByType.entries.firstOrNull { it.key.equals(type, ignoreCase = true) }?.value.orEmpty()

    fun valuesOf(sectionType: String, key: String): List<String> =
        sections[sectionType].orEmpty().flatMap { fields -> fields.filter { it.first.equals(key, ignoreCase = true) }.map { it.second } }

    fun mostCommon(sectionType: String, key: String): String? =
        valuesOf(sectionType, key).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /**
     * The block type a field names, when every value it has anywhere is the name of such a block:
     * `Look = ArcherShot` is a `DungeonEffect`. The smallest such type wins, so `Projectile` means a
     * `DungeonProjectile` rather than any `Object`.
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
        (block.fields + block.modules.flatMap { it.fields } + block.sections.flatMap { it.fields }).map { it.second }
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

    /** The units that have a block of [companion] named after them; any companion when null. */
    fun unitsWith(companion: String?): List<Block> = blocks.filter { unit ->
        isUnit(unit) && unit.name != null &&
            blocks.any { it.name == unit.name && (if (companion == null) it.type in companions else it.type == companion) }
    }

    /** The folder most of [units]' files are in. */
    fun folderOf(units: List<Block>): VirtualFile? =
        units.mapNotNull { it.file.parent }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /** Block types every block of which is named after a unit, in a game that writes a unit as several blocks. */
    val companions: List<String> = blocks.filter { !isUnit(it) }.groupBy { it.type }
        .filter { (_, of) -> of.all { it.name != null && it.name in unitNames } }.keys.toList()

    /** Block types headed by a unit's name and one more word: `DungeonSkill Rogue Q`. */
    val pointers: List<String> = blocks.filter { !isUnit(it) }.groupBy { it.type }
        .filter { (_, of) -> of.all { it.names.size == 2 && it.names[0] in unitNames } }.keys.toList()

    /** The second words a pointer type is written with, most common first: `Q`, `W`, `E`, `R`. */
    fun secondNames(type: String): List<String> =
        blocks.filter { it.type == type && it.names.size == 2 }.groupingBy { it.names[1] }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }

    /**
     * Block types named `<moment>.<Template>`, as a sound is by the moment it plays at: `died.Skeleton`.
     * A name after the dot that starts in upper case and is longer than a letter is a template's;
     * `music.industrial` and `skill.Q` are not.
     */
    val moments: Map<String, List<String>> = blocks.mapNotNull { block ->
        val name = block.name ?: return@mapNotNull null
        val dot = name.indexOf('.')
        val after = name.substring(dot + 1)
        if (dot <= 0 || after.length < 2 || !after[0].isUpperCase()) null else block.type to name.substring(0, dot)
    }.groupBy({ it.first }, { it.second }).mapValues { it.value.distinct().sorted() }

    /** The name sections of this type are most often given: `Layout`, for `world/generation`. */
    fun sectionName(sectionType: String): String? = blocks.flatMap { it.sections }.filter { it.type == sectionType }
        .map { it.names.joinToString(" ") }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /** The field a module is usually written under: `Behavior = ExperienceModule`. */
    fun moduleKey(name: String): String? =
        blocks.flatMap { it.modules }.filter { it.name == name }.groupingBy { it.key }.eachCount().maxByOrNull { it.value }?.key

    /** Every module name starting with [prefix] that some file uses: the scripts, for `Script:`. */
    fun moduleNames(prefix: String): List<String> =
        blocks.flatMap { it.modules }.map { it.name }.filter { it.startsWith(prefix) && it.length > prefix.length }.distinct().sorted()

    /**
     * What files add to a unit's own name after [prefix]: `Brain`, when `Object Skeleton` runs
     * `Script:SkeletonBrain` and most units do the same.
     */
    fun nameSuffix(prefix: String): String? = blocks.filter { isUnit(it) && it.name != null }.flatMap { unit ->
        unit.modules.map { it.name }.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            .filter { it.length > unit.name!!.length && it.startsWith(unit.name!!) }
            .map { it.removePrefix(unit.name!!) }
    }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /** Blocks that belong to the unit [name] other than its own: its companions, its pointers and its moments. */
    fun about(name: String): List<Block> = blocks.filter { block ->
        !isUnit(block) && (block.name == name || (block.type in pointers && block.names[0] == name) ||
            (block.type in moments && block.name?.substringAfter('.', "") == name))
    }

    /** The block listing the game's INI files, and the field it lists them under: `DungeonContent Files`, `File`. */
    val manifest: Pair<Block, String>? = blocks.flatMap { block ->
        block.fields.groupBy({ it.first }, { it.second })
            .filter { (_, values) -> values.size >= 2 && values.all { it.endsWith(".ini", ignoreCase = true) } }
            .map { (key, values) -> Triple(block, key, values.size) }
    }.maxByOrNull { it.third }?.let { it.first to it.second }

    /** The file that holds the `Object` [name], if some file does. */
    fun fileOf(name: String): VirtualFile? = blocks.firstOrNull { isUnit(it) && it.name == name }?.file

    /** The units written as blocks of [type]: every `Monster`. */
    fun unitsOf(type: String): List<Block> = blocks.filter { it.type == type && it.name != null }

    /** The unit [name]'s own block. */
    fun unit(name: String): Block? = blocks.firstOrNull { isUnit(it) && it.name == name }

    companion object {
        const val OBJECT = "Object"
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
                        block.fields.map { it.keyText to it.value },
                        block.modules.map { module -> Module(module.moduleKey, module.moduleName?.text.orEmpty(), module.fields.map { it.keyText to it.value }) },
                        sectionsOf(block),
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
