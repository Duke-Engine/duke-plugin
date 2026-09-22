package uz.dukeengine.plugin.engine

import java.io.File

/**
 * Where the engine checkout the plugin is tested against is.
 *
 * <p>The plugin knows no particular game, but testing it needs one: its checks are only worth anything if
 * they are run over real records rather than over fixtures written to agree with them. The records are the
 * engine's own — the plugin mirrors `Binder`, so the engine is the spec — and the game those records are
 * written into is the plugin's own, under `src/test/testData/game`. No second game checkout: a syntax the
 * engine grows is written into that game and the checks are what prove the plugin reads it.
 *
 * <p>Which is why this is looked for rather than hard-coded. The plugin lives beside the engine today and a
 * build server puts them wherever it likes — the same tests have to pass in both. It can be pointed anywhere
 * with `DUKE_ENGINE`.
 */
object Repos {

    /** The engine: the folder whose `core/src/main/java` is there. */
    val engine: File = find(
        "DUKE_ENGINE", "an engine checkout",
        listOf("..", "../duke-engine", "../DukeEngine"),
    ) { File(it, "core/src/main/java").isDirectory }

    /** The kit, for the effect blocks a game links. It ships with the engine. */
    val kit: File get() = File(engine, "kit")

    /** The plugin's own game: the records under `java`, everything it is made of under `res`. */
    val game: File = File("src/test/testData/game").absoluteFile

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
