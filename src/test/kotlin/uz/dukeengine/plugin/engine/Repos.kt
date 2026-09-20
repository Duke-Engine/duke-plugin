package uz.dukeengine.plugin.engine

import java.io.File

/**
 * Where the checkouts this plugin is tested against are.
 *
 * <p>The plugin knows no particular game, but testing it needs one: its checks are only worth anything if
 * they are run over real records and real data rather than over fixtures written to agree with them. So the
 * tests read an engine checkout and a game's files off the disk beside them.
 *
 * <p>Which is why these are looked for rather than hard-coded. The plugin lives inside the engine's repository
 * today and will live beside it once they are split, and a build server puts them wherever it likes — the same
 * tests have to pass in all three. Each can be pointed anywhere with an environment variable.
 */
object Repos {

    /** The engine: the folder whose `settings.gradle.kts` includes `core`. */
    val engine: File = find(
        "DUKE_ENGINE", "an engine checkout",
        listOf("..", "../duke-engine", "../DukeEngine"),
    ) { File(it, "core/src/main/java").isDirectory }

    /**
     * A game whose data files are the material the checks are run over.
     *
     * <p>Duke Dungeon, because it is the richest: relief, held weapons, themes, animation sets and two map
     * packages. A thinner game would pass the same tests while checking less of the plugin.
     */
    val sample: File = find(
        "DUKE_SAMPLE", "a game to read",
        listOf("../dungeon", "$engine/dungeon", "../duke-dungeon"),
    ) { File(it, "src/main/resources/data/game.duke").isFile }

    /** The kit, for the effect blocks a game links. It ships with the engine. */
    val kit: File get() = File(engine, "kit")

    private fun find(variable: String, what: String, where: List<String>, holds: (File) -> Boolean): File {
        val named = System.getenv(variable)?.let(::File)
        if (named != null) {
            check(holds(named)) { "$variable points at ${named.absolutePath}, which is not $what" }
            return named
        }
        return where.map(::File).firstOrNull(holds)
            ?: error(
                "could not find $what. Looked in ${where.joinToString()} from ${File(".").absolutePath}. "
                    + "Set $variable to say where it is.",
            )
    }
}
