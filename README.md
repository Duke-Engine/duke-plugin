# Duke-plugin

[![Twitter Follow](https://img.shields.io/badge/follow-%40JBPlatform-1DA1F2?logo=twitter)](https://twitter.com/JBPlatform)
[![Developers Forum](https://img.shields.io/badge/JetBrains%20Platform-Join-blue)][jb:forum]

## Overview

An IntelliJ Platform plugin that lets the IDE read a Duke Engine game's content: its `.duke` data files — units,
projectiles, effects, sounds — and the INI file of its world settings. What a block may hold is read from the
game's own Java records, so the plugin knows no particular game.

## Features

### `.duke` files

A `.duke` file is blocks: a line that is one word opens one, `End` closes the innermost, and each line inside is
`Key = value`, a list `[a, b]` (over several lines if it likes), or a block of its own. The engine's `Binder`
reads each block as the record its word names, each key as a component of that record; the plugin reads them
the same way, from IntelliJ's Java model rather than by loading anything (the IDE runs on Java 21, the engine
on 25).

```
Monster
  Name = Brute
  KindOf = [INFANTRY, CAN_ATTACK]
  Cylinder
    Radius = 5
    Height = 14
  End
  MoveUpdate
    Speed = 22
  End
End
```

- **Highlighting** for words, `End`, keys, values, numbers, strings, lists and `;` comments; brace matching
  for `[` `]`; Ctrl+/ comments a line.
- **Navigation:** Ctrl+Click a block's word to open the class it is read as — `Monster` its record, `Object`
  what the game's template loader registers (`RtsTemplate`), `MoveUpdate` the module's class, `Cylinder` the
  shape of a `Geometry`, `Skill` the record a list of skills holds. Ctrl+Click a key to open the record
  component it fills, and an enum value to its constant.
- **Completion:** on a line being begun, the keys the block's record has not been given (written with ` = `)
  and the blocks it may hold (written with their `End`); after `=`, an enum's constants, `Yes`/`No`, or files.
- **Checks**, each in the engine's own words, so what the editor says is what the game would say at load:
  - syntax, as `DukeText` reads it: a block with no `End`, an `End` with nothing open, a key written twice, a
    list never closed, an empty item or a missing comma in a list, a line that fits nothing. A header written
    the INI way, `Monster Brute`, has a quick fix: `Monster` with `Name = Brute` inside it.
  - against the records, as `Binder` reads them: a word no block can be where it is, a key its record has no
    component for, a value its component cannot read (a number, `Yes`/`No`, one of an enum's constants), a list
    where one value goes and one value where a list goes, a positional record with the wrong number of values,
    a block written twice where one is held.
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

Outside a resource root (a loose file, an unexpected layout) paths are not checked or completed.

### INI world settings

A game's world settings are still one INI block, `World Dungeon`, until they are records too. Every `*.ini` file
opens as **Duke INI** and gets highlighting, folding, a structure view, and checks for a block with no `End`,
an `End` with no open block, and a line that is neither a header nor `Key = value`. A block may hold sections,
`Generation = Layout` to an `End` of its own; which keys open one is the game's code, which the editor cannot
see while it reads the text, so it goes by the layout.

**Duke Inspector** is a tool window that opens the first time an INI file is selected and shows it as a form:
each block and section a group, and each field an editor by what it is — a checkbox for `Yes`/`No`, a list for
an enum, files of the right kind for a path, block names for a field that names a block, clips for an animation.
`+ Field` adds what the block's code reads that it does not write yet. The file stays the truth: every change is
written into it as text, and Ctrl+Z undoes it. The sections and their fields are read from the game's code —
every `initFromIni(x, TABLE)` and `TABLE`'s `add("Field", Ini.real(...))`.

Run it with `./gradlew runIde`, then open the duke-engine project in the IDE that starts. Tests: `./gradlew test`.
They also check that every file in `dungeon/src/main/resources/data` and the world's INI file load with no
problems against the engine's own sources.

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
