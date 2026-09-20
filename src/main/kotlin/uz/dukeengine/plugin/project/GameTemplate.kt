package uz.dukeengine.plugin.project

/**
 * What a new Duke Engine game is made of, as a map of path to text.
 *
 * <p>Plain strings and no IDE at all, so what the wizard writes can be read back and checked by a test rather
 * than only by making a project and looking at it.
 *
 * <p>**The generated game runs.** It is not a skeleton with `TODO` in it: two units, a light, a camera and a
 * `Main` that opens the 3D client on them. Nothing in it is drawn from a model file, because a new project has
 * no art yet and the engine draws a template with no `Model` as its `Geometry` — so the first thing anybody sees
 * is their own game, running, made of shapes they can then replace one line at a time.
 *
 * @param game    what the game is called, on its window and in its files
 * @param pack    the Java package its two classes sit in
 * @param engine  where the duke-engine checkout is, for `includeBuild`; null once the engine is published and
 *     the ordinary dependencies resolve on their own
 */
object GameTemplate {

    /** The engine a new game is written against. Raise it when a newer one is on Maven Central. */
    const val ENGINE = "0.2.0"

    /** Where the engine is published: the namespace verified for duke-engine.uz. */
    private const val GROUP = "uz.duke-engine"

    fun of(game: String, pack: String, engine: String?): Map<String, String> {
        val path = pack.replace('.', '/')
        return mapOf(
            "settings.gradle.kts" to settings(game, engine),
            "build.gradle.kts" to build(pack),
            "gradle.properties" to "org.gradle.caching=true\n",
            ".gitignore" to "build/\n.gradle/\n*.iml\n.idea/\n",
            "README.md" to readme(game, engine),
            "src/main/java/$path/Main.java" to main(game, pack),
            "src/main/java/$path/Content.java" to content(pack),
            "src/main/resources/data/game.duke" to manifest(game),
            "src/main/resources/data/units/scout.duke" to scout(),
            "src/main/resources/data/units/turret.duke" to turret(),
            "src/main/resources/data/world/look.duke" to look(),
            "src/test/java/$path/GameLoadsTest.java" to test(pack),
        )
    }

    private fun settings(game: String, engine: String?) = buildString {
        appendLine("rootProject.name = \"${slug(game)}\"")
        if (engine != null) {
            appendLine()
            appendLine("// The engine, built alongside this game. Every `$GROUP:…` dependency below is answered")
            appendLine("// by this checkout rather than by a repository — delete this line once the engine you")
            appendLine("// want is published and the dependencies will resolve on their own.")
            appendLine("includeBuild(\"${engine.replace('\\', '/')}\")")
        }
    }

    private fun build(pack: String) = """
        plugins {
            java
            application
        }

        repositories {
            mavenCentral()
        }

        dependencies {
            // The 3D client, which brings core, rts and game with it.
            implementation("$GROUP:client3d:$ENGINE")
            // The starter set: effects every game may use, and the art they are drawn with.
            implementation("$GROUP:kit:$ENGINE")

            testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
            testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        }

        tasks.test {
            useJUnitPlatform()
        }

        java {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(25))
            }
        }

        application {
            mainClass.set("$pack.Main")
        }
    """.trimIndent() + "\n"

    private fun readme(game: String, engine: String?) = """
        # $game

        A game on [Duke Engine](https://github.com/abdurasul29052002/duke-engine).

        ```
        ./gradlew run
        ```

        Two shapes appear: a Scout you can select and order about, and a Turret that shoots at it.
        Neither is drawn from a model — a template with no `Model` is drawn as its `Geometry` — so the
        first thing you see is your own game rather than somebody else's art.

        ## Where everything is

        | | |
        |---|---|
        | `src/main/resources/data/game.duke` | the list of files the game is made of |
        | `src/main/resources/data/units/` | one file a unit |
        | `src/main/resources/data/world/` | the light and the camera |
        | `src/main/java/…/Main.java` | opens the 3D client |
        | `src/main/java/…/Content.java` | reads the files above |

        ## What to change first

        1. Open `data/units/scout.duke` and change `MaxHealth` or `Speed`. Run again — no rebuild of
           anything but this project, and nothing in Java to touch.
        2. Drop a `.glb` into `src/main/resources/models/` and write `Model = models/yours.glb` in the
           block. The **Duke Engine** plugin completes the path and opens the file from it.
        3. Add a third unit: copy a file, list it in `game.duke`, and it is in the game.

        ${if (engine != null) "The engine is built from `$engine` — see `settings.gradle.kts`." else ""}
    """.trimIndent() + "\n"

    private fun main(game: String, pack: String) = """
        package $pack;

        import java.awt.Color;
        import uz.dukeengine.client3d.Camera;
        import uz.dukeengine.client3d.Duke3D;
        import uz.dukeengine.client3d.Sun;
        import uz.dukeengine.client3d.Sunlight;
        import uz.dukeengine.client3d.Visuals;
        import uz.dukeengine.core.thing.Drawn;
        import uz.dukeengine.game.DukeGame;
        import uz.dukeengine.rts.RtsTemplate;

        /** $game — opens on a small field with two things standing on it. */
        public final class Main {

            private Main() {
            }

            public static void main(String[] args) {
                var game = DukeGame.create("$game")
                        .loadUnits(Content.units())
                        .map(40, 30);

                var you = game.addPlayer("You", Color.CYAN);
                var them = game.addPlayer("Them", Color.ORANGE);
                game.enemies(you, them).localPlayer(you).money(you, 500);

                game.spawn("Scout", you, 80f, 150f);
                game.spawn("Turret", them, 260f, 150f);

                Duke3D.launch(game, visuals());
            }

            /**
             * Everything the client is told, built out of the game's own files.
             *
             * <p>Nothing here names a unit: each block says what it looks like and the client is handed it.
             * Adding a unit is a file, not a line of Java.
             */
            private static Visuals visuals() {
                var visuals = Visuals.create();
                for (var block : Content.everything()) {
                    switch (block) {
                        case Drawn drawn -> visuals.draw(drawn, null);
                        case Sun sun -> visuals.sunlight(new Sunlight(sun.pitch(), sun.yaw(),
                                sun.strengthPercent() / 100f, sun.ambientPercent() / 100f,
                                sun.colour(), sun.ambientTint()));
                        default -> {
                            // A block the client is not told about.
                        }
                    }
                }
                return visuals;
            }
        }
    """.trimIndent() + "\n"

    private fun content(pack: String) = """
        package $pack;

        import java.io.IOException;
        import java.io.UncheckedIOException;
        import java.nio.charset.StandardCharsets;
        import java.util.ArrayList;
        import java.util.List;
        import java.util.Locale;
        import java.util.Map;
        import uz.dukeengine.core.content.AnimationSet;
        import uz.dukeengine.core.content.Effect;
        import uz.dukeengine.core.content.Game;
        import uz.dukeengine.core.content.Sound;
        import uz.dukeengine.core.data.Binder;
        import uz.dukeengine.core.data.DataException;
        import uz.dukeengine.core.data.DukeText;
        import uz.dukeengine.core.module.ModuleData;
        import uz.dukeengine.core.module.ModuleFactory;
        import uz.dukeengine.rts.RtsTemplate;
        import uz.dukeengine.rts.module.RtsModules;

        /**
         * The game's files, and the record each block of them is.
         *
         * <p>Every word below is the engine's own: an {@code Object} block is an {@link RtsTemplate}, which
         * carries a name, a size, a price, its modules <em>and</em> its look. A game that wants a field the
         * engine has no idea of writes a record of its own and adds it to {@code TYPES} — until then, there is
         * nothing here to change.
         */
        public final class Content {

            private static final String GAME = "data/game.duke";

            /** Every module a unit's block may hold: the engine's and the RTS library's. */
            public static final List<Class<? extends ModuleData>> MODULES =
                    java.util.stream.Stream.of(ModuleFactory.ENGINE_MODULES, RtsModules.MODULES)
                            .flatMap(List::stream).toList();

            private static final Map<String, Class<? extends Record>> TYPES = Map.of(
                    "object", RtsTemplate.class,
                    "animationset", AnimationSet.class,
                    "effect", Effect.class,
                    "sound", Sound.class,
                    "sun", uz.dukeengine.client3d.Sun.class,
                    "camera", uz.dukeengine.client3d.Camera.class);

            private Content() {
            }

            /** The game's own block: the files it is made of. */
            public static Game game() {
                var blocks = DukeText.parse(read(GAME), GAME);
                return new Binder().bind(blocks.getFirst(), Game.class);
            }

            /** Every unit block, as the text a world's template loader takes. */
            public static String units() {
                var text = new StringBuilder();
                for (var file : game().files()) {
                    if (file.startsWith("data/units/")) {
                        text.append(read(file)).append('\n');
                    }
                }
                return text.toString();
            }

            /** Every block of every file, as the record its word names. */
            public static List<Record> everything() {
                var binder = new Binder().vocabulary(ModuleData.class, ModuleFactory.vocabularyOf(MODULES));
                var records = new ArrayList<Record>();
                for (var file : game().files()) {
                    for (var block : DukeText.parse(read(file), file)) {
                        var type = TYPES.get(block.word().toLowerCase(Locale.ROOT));
                        if (type == null) {
                            throw new DataException(block.at(block.line()), "no block is called '" + block.word() + "'");
                        }
                        records.add(binder.bind(block, type));
                    }
                }
                return records;
            }

            /** A file of the game's, from the classpath — so a built game reads its data out of its own jar. */
            public static String read(String path) {
                try (var in = Content.class.getClassLoader().getResourceAsStream(path)) {
                    if (in == null) {
                        throw new DataException(path, "missing data file");
                    }
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("could not read " + path, e);
                }
            }
        }
    """.trimIndent() + "\n"

    private fun manifest(game: String) = """
        ; $game — the files this game is made of, in the order they are read.
        ;
        ; Everything the game knows is in these files. Add one, list it here, and it
        ; is in the game: there is nothing in Java that names a unit.

        Game
          Files = [
            ; The kit's: effects every game may draw from, listed before this game's own
            ; so a block of yours with the same Name is drawn instead of one of these.
            kit/data/effects/aura/focus.duke,
            kit/data/effects/strike/shot.duke,

            data/units/scout.duke,
            data/units/turret.duke,
            data/world/look.duke,
          ]
        End
    """.trimIndent() + "\n"

    private fun scout() = """
        ; Yours. Select it with the left button and order it about with the right.
        ;
        ; No Model, so the client draws it as its Geometry. Give it one —
        ; Model = models/scout.glb — and it is drawn as that instead.

        Object
          Name = Scout
          DisplayName = Scout
          KindOf = [INFANTRY, SELECTABLE, CAN_ATTACK]
          VisionRange = 160
          Geometry = Cylinder
            Radius = 4
            Height = 12
          End
          BuildCost = 100
          BuildTime = 5
          ; An effect out of the kit. Delete the line and it stops glowing; write one of
          ; your own with the same Name in a file listed after the kit's and yours is drawn.
          Effect = Focus
          Modules = [
            ActiveBody
              MaxHealth = 120
            End
            MoveUpdate
              Speed = 26
            End
            WeaponUpdate
              Damage = 12
              AttackRange = 40
              ReloadFrames = 30
            End
            ; Walks into range of whatever it is sent at, and stops there to shoot.
            ; Take this block out and it stands still and waits instead.
            PursueUpdate
              RepathFrames = 10
            End
          ]
        End
    """.trimIndent() + "\n"

    private fun turret() = """
        ; Theirs. It cannot move, so it has no MoveUpdate and no PursueUpdate —
        ; which is the whole of how a template says what a thing can do.

        Object
          Name = Turret
          DisplayName = Turret
          KindOf = [STRUCTURE, SELECTABLE, CAN_ATTACK]
          VisionRange = 120
          Geometry = Box
            MajorRadius = 6
            MinorRadius = 6
            Height = 14
          End
          Modules = [
            ActiveBody
              MaxHealth = 300
            End
            WeaponUpdate
              Damage = 8
              AttackRange = 90
              ReloadFrames = 45
            End
          ]
        End
    """.trimIndent() + "\n"

    private fun look() = """
        ; How the world is lit and how the camera behaves. Both are the client's own
        ; blocks, so the plugin completes every field in them from its records.

        Sun
          Pitch = 50
          Yaw = 200
          StrengthPercent = 110
          AmbientPercent = 60
        End

        Camera
          EdgeMargin = 12
          EdgeSpeedPercent = 100
        End
    """.trimIndent() + "\n"

    private fun test(pack: String) = """
        package $pack;

        import static org.junit.jupiter.api.Assertions.assertEquals;
        import static org.junit.jupiter.api.Assertions.assertFalse;

        import java.awt.Color;
        import org.junit.jupiter.api.Test;
        import uz.dukeengine.game.DukeGame;

        /**
         * The game loads out of its own files and runs.
         *
         * <p>Kept because it is the test that catches the mistake everybody makes: a unit renamed in a block
         * and not in the code that spawns it, or a file added and not listed in {@code game.duke}. It never
         * opens a window, so it runs anywhere — including on a build server.
         */
        class GameLoadsTest {

            @Test
            void theGameRunsOutOfItsOwnFiles() {
                var game = DukeGame.create("test")
                        .loadUnits(Content.units())
                        .map(40, 30);
                var you = game.addPlayer("You", Color.CYAN);
                var them = game.addPlayer("Them", Color.ORANGE);
                game.enemies(you, them);

                game.spawn("Scout", you, 80f, 150f);
                game.spawn("Turret", them, 260f, 150f);
                game.runHeadless(30);

                assertEquals(2, game.getLogic().getObjects().size(), "both units should be standing");
                assertFalse(Content.everything().isEmpty(), "every block of every file should read");
            }
        }
    """.trimIndent() + "\n"

    /** A project name as a folder and a Gradle project are willing to spell it. */
    fun slug(name: String): String {
        val slug = name.trim().lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
            .trim('-').replace(Regex("-+"), "-")
        return slug.ifEmpty { "duke-game" }
    }

    /** A Java package a project name can be put in, with nothing in it a compiler would refuse. */
    fun packageOf(name: String): String {
        val word = name.trim().lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "game" }
        return "com.example." + if (word.first().isDigit()) "g$word" else word
    }
}
