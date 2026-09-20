# Duke-plugin

[![Twitter Follow](https://img.shields.io/badge/follow-%40JBPlatform-1DA1F2?logo=twitter)](https://twitter.com/JBPlatform)
[![Developers Forum](https://img.shields.io/badge/JetBrains%20Platform-Join-blue)][jb:forum]

## Overview

An IntelliJ Platform plugin that lets the IDE read a Duke Engine game's content: its `.duke` data files — units,
projectiles, effects, sounds, the world and its maps. What a block may hold is read from the game's own Java
records, so the plugin knows no particular game.

## Features

### A new game

**File → New → Project → Duke Game** writes a game that runs before anything is changed: two units, a light
and a camera as `.duke` blocks, two of the kit's effects with a unit wearing one, a `Main` that opens the 3D
client, a `Content` that reads the files, and a test that loads the whole thing headless. Nothing in it is drawn from a model file — a template with no `Model` is
drawn as its `Geometry` — so a new project needs no art to show something, and a model is one line when there
is one.

The engine is not published to a repository yet, so the wizard asks where a checkout of it is and writes
`includeBuild(…)` into the generated `settings.gradle.kts`. Everything else in that file is an ordinary
dependency and stays as it is once the engine is published.

### `.duke` files

A `.duke` file is blocks: a line that is one word opens one, and `End` closes the innermost. The engine's
`Binder` reads each block as the record its word names, and every line inside it as one of that record's
components, by name — so every line starts with a field of the class:

| The field is | It is written |
|---|---|
| a value | `Speed = 10` |
| a list of values | `KindOf = [INFANTRY, CAN_ATTACK]`, over several lines if it likes |
| one record | `Geometry = Cylinder`, its fields under it, `End` — the class after `=` |
| a list of records | `Modules = [`, a block for each (`MoveUpdate` … `End`, no commas), `]` |
| a map | `Armor`, its entries (`FLAME = 0.5`), `End` |

`Key = Class` opens a block only when the next line is indented deeper; a record with nothing written in it is
the word alone, `Geometry = Sphere`. The plugin reads the records the same way, from IntelliJ's Java model
rather than by loading anything (the IDE runs on Java 21, the engine on 25).

```
Monster
  Name = Brute
  KindOf = [INFANTRY, CAN_ATTACK]
  Geometry = Cylinder
    Radius = 5
    Height = 14
  End
  Modules = [
    MoveUpdate
      Speed = 22
    End
  ]
End
```

- **Highlighting** for words, `End`, keys, values, numbers, strings, lists and `;` comments; brace matching
  for `[` `]`; Ctrl+/ comments a line.
- **Navigation:** Ctrl+Click a key to open the record component it is — `Geometry`, `Modules`, `Speed` — and a
  class to the class: `Monster` its record, `Object` what the game's template loader registers (`RtsTemplate`),
  `Cylinder` the shape of a `Geometry`, `MoveUpdate` the module's class, `Skill` the record a list of skills
  holds. Ctrl+Click an enum value to its constant, and a link — `Animations = Humanoid` — to the block it names.
- **Completion:** on a line being begun, the keys the record has not been given (written with ` = `) and its
  maps; after `Key = `, the classes the field may be, an enum's constants, `Yes`/`No`, or files; inside a list of
  records, the classes it holds, each written with its `End`.
- **Links and clips**, as the game's records mark them: a component marked `@Link(AnimationSet.class)` is offered
  the names of the blocks of that record there are, and a wrong one is flagged; one marked `@Clip` — `Walk = ` — is
  offered the clips inside the model and animation files the block is drawn from and those of the set it links,
  read out of the `.glb`/`.gltf` files themselves, so a file that gains a clip offers it at once.
- **Checks**, each in the engine's own words, so what the editor says is what the game would say at load:
  - syntax, as `DukeText` reads it: a block with no `End`, an `End` with nothing open, a key written twice, a
    list never closed, an empty item or a missing comma in a list, a line in a list of records that is not a
    record, a line that fits nothing. A header written the INI way, `Monster Brute`, has a quick fix: `Monster`
    with `Name = Brute` inside it.
  - against the records, as `Binder` reads them: a key its record has no component for, a class the field may
    not be, a value its component cannot read (a number, `Yes`/`No`, one of an enum's constants), a list where
    one value goes and one value where a list goes, a positional record with the wrong number of values. A
    block written on its own where a field should be says how it is written: `'Cylinder' is the value of its
    field: Geometry = Cylinder`, `'MoveUpdate' goes in its list: 'Modules = [' …`.
  - Without the engine on the classpath only the syntax is checked.
- **Refactoring:** renaming a module class, a record component (`senseRadius` → `SenseRadius` in the file) or
  an enum constant renames it in the data files.
- **Folding** of every block, and of every list over several lines, and a **structure view** of the blocks.

### Assets

Every asset is written as its whole path from the resource root the file sits in (`dungeon/src/main/resources`,
the classpath root jME loads from): `Model = models/heroes/rogue.glb`, `Icon = icons/skills/skill_arrow_shot.png`,
and a manifest's `Files = [data/units/brute.duke, …]`. No folder is put in front of a name, so a game may keep
its files in whatever structure it likes. A value is a path when it ends in a model, image, sound, font or data
extension (`.glb .gltf .obj .j3o`, `.png .jpg .jpeg .tga .dds`, `.ogg .wav .mp3`, `.fnt`, `.duke`).

- **Completion** after a key that names a kind (`Model`, `Texture`/`Image`/`Icon`, `Sound`, `Font`), or in a
  list whose other items are paths: every file of that kind under the root. Files are listed from disk each
  time, so a new asset shows up at once.
- **Checks:** error on a path with no file behind it, letter case included (`Models/` is not `models/`, as on
  Linux CI), with `Did you mean …?` and a quick fix when a close match exists; warning on a file of the wrong
  kind (`Model = x.png`).
- **Navigation:** Ctrl+Click a path to open the file. Moving an asset does not update the lines; the check
  flags them instead.

A path is looked for under the file's own resource root first and then under the project's others, as the
classpath finds it: the game's `kit/data/effects/fire/fireball.duke` is the kit's file. Outside a resource root (a loose file, an unexpected layout) paths are not checked or completed.

### Duke Inspector

A tool window that shows the `.duke` block under the caret as a form, drawn from its record: the groups its
components are marked with (`@Group("Look")`), and each field an editor by its type — a checkbox for `Yes`/`No`,
a list for an enum, a colour picker for a colour, files of the right kind for a path, block names for a
`@Link`, the clips of the files it moves by for a `@Clip`, a box for each number of a small record
(`KeepDistance = [35, 55]`). A record inside another is its fields a step in; a list of blocks — modules,
skills — is a card for each, moved up and down or taken out, with `+ Add` listing what the list may hold.

A field not written shows the record's default, from its `static final DEFAULTS`, and its help is the
component's `@param` line. The file stays the truth: each change is written into it as text — a new line where
the record puts it, before the comment over the next one — and Ctrl+Z undoes it. **New from Template** writes
a new file from a record, or a copy of a block there is, and lists it in the game's `Files`.

Above the form, a block with a `Model` is drawn as the game draws it — its `Texture`, its `Tint`, what it carries
on a `Bone` — in a page of the IDE's own browser, with a button for each of its clip fields that plays that clip:
written, or the one the set it links names. Picking a clip field in the form plays it too. Beside it, a button for
each sound named for the block (`died.Skeleton` for the Skeleton), or for the block's own files when it is a sound.
**Add Sound…** gives the block a sound for a moment the game's sounds are named for already — `died`, `hurt`,
`spawned` — as a copy of the last sound for that moment, written after it, for its files to be changed.
The page's files are served to the browser in-process; nothing listens on a port. It draws with
[three.js](https://threejs.org) (MIT), taken out of its webjar when the plugin is built.

Over the client's `PanelLook` — the hero's bar: which blocks, in what order, how big its sockets are, every
colour — and over each skin of it (a block with a `Name`, a `Texture`, an `Inset`, a `Scale` and a `Tint`, as the
game's `Skin` is), the preview is the bar itself, laid out as the client lays it out and painted with every skin
the game names, each picture cut into nine as the client cuts it; the skin being edited is outlined wherever it
goes, and a banner's is drawn on its plaque. A look that hangs blocks from the window's corners — `Places =
[Minimap TopRight 12 12]` — is drawn as the whole screen instead, each block on its own plate where it will hang. So a border is chosen by picking its picture from the gallery beside
the `Texture` field and seeing it on the bar at once, and its `Tint` with a colour picker, as every colour field is.

### Map

A file whose block has a component marked `@Grid` — a map's rows of cells — opens with a **Map** tab beside its
text. Maps are kept as `.map` files, read in the same language as `.duke` and named apart from it because a map
is a place rather than a rule: one folder a map, `maps/<name>/<name>.map`, with its `preview.png` and any `.duke`
files of its own beside it. **New from Template** on a map's record writes that whole folder, with a floor inside
a border of rock to start drawing on, and does not list it among the game's files — the game finds it by its
being there. The tab shows: the map in 3D as the game's client draws it — floor, walls and stairs laid from the theme the game
gives a map of that `Difficulty`, the tone its seed draws, under the world's `Sun`, bent over its relief — and
every thing on the map in the model its block gives it, or a marker where it has none. Right drag turns the camera,
middle drag slides it, the wheel brings it nearer. Things are read by the shape of the record: a component holding
a record with an `x` and a `y` is a thing, or a list of them — `Entrance = [9, 28]`, `Boss = Warden 8 5`,
`Monsters = [Skeleton 17 16, …]` — and the kinds a thing may be are the blocks its `@Link` names (the first word
of `Warden 8 5` is the Monster it links).

The tool on the left of the tab says what the left button does:

| Tool | What a click or a stroke does |
|---|---|
| Put down | puts down the list and kind chosen beside it; a drag moves a thing, a click on one makes it the tool |
| Take off | takes off what stands on the cell (a right click does, with any tool; so does Delete) |
| Raise / Lower / Smooth / Flatten ground | the relief under the brush, as big and as strong as the tool says |
| Paint floor / rock / stair | the cells the stroke passes over: floor at the storey chosen, rock, or a stair |

Each is lines of the file changed, one undoable command, and the Text tab shows it: a thing is its line, a stroke
over the ground the rows of the component marked `@Relief` it changed — a whole number of steps, a sixteenth of a
cell, at each corner of a cell (added under the cells when the map has none), and a stroke of paint the rows of
cells it passed over. Rock is not painted under a thing. Where the IDE runs without its browser the tab draws the
map from above, a square a cell, and takes the same clicks. A new map is drawn from a seed by the game —
`./gradlew :dungeon:newMap --args="crypt 42"` — and filled here.

A map is **checked where it is drawn**, so what the engine would refuse it for is read on the line that says it
rather than in a list when the game is started: a thing off the edge of the map, a thing inside stone, rows of
different widths (a short one reads as stone to its right), a ground that is not a corner of every cell. Two
things on one cell is a warning, not an error — the editor puts down one thing to a cell, and whether a game
allows two is the game's rule. Only what any map means is checked; what a particular game asks of its maps is
still the game's to say when it loads one.

**Save Preview** (on the tab) writes `preview.png` into the map's folder — the map from above, a square a cell,
with what stands on it marked — which is what the screen a map is chosen on shows of it. The game writes the same
picture for a map it draws (`./gradlew :dungeon:newMap`), and for maps already drawn
(`./gradlew :dungeon:writeMapPreviews`), so a map has one whether or not it was ever opened here.

**Resize Map…** (on the tab) makes the map bigger or smaller: the new size, and which
corner the floor keeps. Growing fills with rock and moves everything on the map with the floor it stands on —
the way in, the monsters, the props, the rooms and the relief with them. Nothing is ever cut off: a size too small
for what stands on it is refused, and says which of them would fall outside.

**Play** (▶ on the Map tab, and in the Inspector) saves every file and runs the game's own Gradle `run` task in
the Run window, told the map being edited by its file — `--args=--map=src/main/resources/maps/first/first.map` —
or from its start for any other block. The game reads the map from disk, so one drawn a minute ago plays before
it is listed anywhere; stop and rerun it from the Run window.

Run it with `./gradlew runIde`, then open the duke-engine project in the IDE that starts. Tests: `./gradlew test`.
They also check that every file in `dungeon/src/main/resources/data` loads with no problems against the
engine's own sources, and that the Inspector reads a real unit.

## Plugin structure

A generated project contains the following content structure:

```
.
├── .run/                   Predefined Run/Debug Configurations
├── gradle
│   ├── wrapper/            Gradle Wrapper
│   ├── libs.versions.toml  Version catalog
├── src                     Plugin sources
│   └── main
│       ├── kotlin/         Kotlin production sources
│       └── resources/      Plugin resources
│           ├── META-INF/   Plugin configuration file and logo
│           └── messages/   Message bundles
├── .gitignore              Git ignoring rules
├── build.gradle.kts        Gradle build configuration
├── gradle.properties       Gradle configuration properties
├── gradlew                 *nix Gradle Wrapper script
├── gradlew.bat             Windows Gradle Wrapper script
├── README.md               This file
└── settings.gradle.kts     Gradle project settings
```

In addition to the configuration files, the most crucial part is the `src` directory, which contains our implementation
and the manifest for our plugin – [plugin.xml][file:plugin.xml].

> [!NOTE]
> To use Java in your plugin, create the `/src/main/java` directory.

The plugin logo is placed in `src/main/resources/META-INF/pluginIcon.svg`.
See [Plugin Logo][docs:logo] for more information and logo requirements.

## Build script

The [build.gradle.kts][file:build.gradle.kts] is the core of the project definition.
It applies three Gradle plugins:

| Plugin                            | Description                                                                      |
|-----------------------------------|----------------------------------------------------------------------------------|
| `org.jetbrains.kotlin.jvm`        | Adds Kotlin support                                                              |
| `org.jetbrains.changelog`         | Simplifies patching the [CHANGELOG.md][file:CHANGELOG.md] file                   |
| `org.jetbrains.intellij.platform` | The [IntelliJ Platform Gradle Plugin][docs:intellij-platform-gradle-plugin-docs] |

The `intellijPlatform` dependencies block selects the IDE to compile against:

```kotlin
intellijIdea("2025.3.5")
```

See [Target Versions][docs:target-version] for more information.

The `intellijPlatform` dependencies block also contains a dependency on the platform testing framework:

```kotlin
testFramework(TestFrameworkType.Platform)
```

See [Testing][docs:testing] for more information

## Plugin configuration file

The plugin configuration file is a [plugin.xml][file:plugin.xml] file located in the `src/main/resources/META-INF`
directory.
It provides general information about the plugin, its dependencies, extensions, and listeners.

You can read more about this file in the [Plugin Configuration File][docs:plugin.xml] section of our documentation.

### Plugin ID and name

Generated plugin ID and name may require adjustment.

These values are generated based on _Group ID_ and _Artifact ID_ provided in the IDE Plugin wizard.
It is recommended to review `<id>` and `<name>` elements in the plugin.xml file, and adjust them if needed.

Please note that Gradle properties `rootProject.name` and `project.group` don't need to match the `<id>` and `<name>`
elements.
There is no IntelliJ Platform-related reason they should as they serve different functions.

## Predefined Run/Debug configurations

Within the default project structure, there is a `.run` directory provided containing predefined *Run/Debug
configurations* that expose corresponding Gradle tasks:

| Configuration name  | Description                                                                                                                                                                           |
|---------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Run IDE with Plugin | Runs [`:runIde`][docs:intellij-platform-gradle-plugin-runIde] IntelliJ Platform Gradle Plugin task. Use the *Debug* icon for plugin debugging.                                        |
| Run Tests           | Runs [`:check`][gradle:lifecycle-tasks] Gradle task.                                                                                                                                  |
| Run Verifications   | Runs [`:verifyPlugin`][docs:intellij-platform-gradle-plugin-verifyPlugin] IntelliJ Platform Gradle Plugin task to check the plugin compatibility against the specified IntelliJ IDEs. |

> [!NOTE]
> You can find the logs from the running task in the `idea.log` tab.

## Publishing the plugin

> [!TIP]
> Make sure to follow all guidelines listed in [Publishing a Plugin][docs:publishing] to follow all recommended and
required steps.

Releasing a plugin to [JetBrains Marketplace](https://plugins.jetbrains.com) is a straightforward operation that uses
the `publishPlugin` Gradle task provided by
the [intellij-platform-gradle-plugin][docs:intellij-platform-gradle-plugin-docs].

You can also upload the plugin to the [JetBrains Plugin Repository](https://plugins.jetbrains.com/plugin/upload)
manually via UI.

## Useful links

- [IntelliJ Platform SDK Plugin SDK][docs]
- [IntelliJ Platform Gradle Plugin Documentation][docs:intellij-platform-gradle-plugin-docs]
- [IntelliJ Platform Explorer][jb:ipe]
- [JetBrains Marketplace Quality Guidelines][jb:quality-guidelines]
- [IntelliJ Platform UI Guidelines][jb:ui-guidelines]
- [JetBrains Marketplace Paid Plugins][jb:paid-plugins]
- [IntelliJ SDK Code Samples][gh:code-samples]

[docs]: https://plugins.jetbrains.com/docs/intellij
[docs:plugin.xml]: https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html?from=IJPluginReadmeFile
[docs:publishing]: https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginReadmeFile
[docs:intellij-platform-gradle-plugin-docs]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html?from=IJPluginReadmeFile
[docs:intellij-platform-gradle-plugin-runIde]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html?from=IJPluginReadmeFile#runIde
[docs:intellij-platform-gradle-plugin-verifyPlugin]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html?from=IJPluginReadmeFile#verifyPlugin
[docs:logo]: https://plugins.jetbrains.com/docs/intellij/plugin-icon-file.html?from=IJPluginReadmeFile
[docs:target-version]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html?from=IJPluginReadmeFile#target-versions
[docs:testing]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html?from=IJPluginReadmeFile#testing

[file:build.gradle.kts]: ./build.gradle.kts
[file:CHANGELOG.md]: ./CHANGELOG.md
[file:gradle.properties]: ./gradle.properties
[file:plugin.xml]: ./src/main/resources/META-INF/plugin.xml

[gh:code-samples]: https://github.com/JetBrains/intellij-sdk-code-samples

[gradle:lifecycle-tasks]: https://docs.gradle.org/current/userguide/java_plugin.html#lifecycle_tasks

[jb:github]: https://github.com/JetBrains/.github/blob/main/profile/README.md
[jb:forum]: https://platform.jetbrains.com/
[jb:quality-guidelines]: https://plugins.jetbrains.com/docs/marketplace/quality-guidelines.html
[jb:paid-plugins]: https://plugins.jetbrains.com/docs/marketplace/paid-plugins-marketplace.html
[jb:ipe]: https://jb.gg/ipe
[jb:ui-guidelines]: https://jetbrains.github.io/ui
