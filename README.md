# Duke-plugin

[![Twitter Follow](https://img.shields.io/badge/follow-%40JBPlatform-1DA1F2?logo=twitter)](https://twitter.com/JBPlatform)
[![Developers Forum](https://img.shields.io/badge/JetBrains%20Platform-Join-blue)][jb:forum]

## Overview

An IntelliJ Platform plugin that lets the IDE read Duke Engine's INI files (`Object Rogue` … `End`,
`Update = MoveUpdate Tag` … `End`, `DungeonSkill Rogue Q` …). The format itself it reads alone; module
lines it checks against the engine's Java classes on the project classpath.

## Features

Every `*.ini` file opens as **Duke INI** and gets:

- **Highlighting** for block types, `End`, keys, module names, values, numbers, strings and `;` comments.
- **Sections inside a block**, as a game's `World` block holds them: `Generation = Layout`, its fields, and an
  `End` of its own. Which keys open a section is the game's code, which the editor cannot see while it reads
  the text, so it goes by the layout: a `Key = Name` line with lines indented under it, or with its `End` at its
  own indent, opens one. A field whose next line is indented deeper by mistake reads as a section.
- **Folding** of each block, module and section down to its first line.
- **Structure view** (Alt+7) listing every block, with its modules and sections under it.
- **Checks:**
  - error on a block with no `End`, and on an `End` with no open block;
  - warning on a line that is neither a header nor `Key = value`.
  - A key set more than once in a block is not flagged: many fields are lists (`Kind`, `Holds`, `File`).
  - Outside module blocks, field names are not checked: `fsdgge = 345` is valid syntax there. The engine
    rejects it when it loads the file.
- **Navigation:** Ctrl+Click a name used elsewhere to reach its definition, e.g. `Rogue` in `DungeonSkill Rogue Q`
  or `HeavyArrow` in `Projectile = HeavyArrow`. Ctrl+Click a block's own name to list its usages.

### Modules

Every concrete subclass of `uz.duke.core.module.Module` that an INI file's module can see on its
classpath (the engine's jars, or its sources when the engine is the open project) is a module, and its
INI name is its class name. The list is read from IntelliJ's Java model, not `java.lang.reflect`: the IDE
runs on Java 21 and the engine is compiled for 25. It is cached, and dropped whenever a Java file, a class
file or the classpath changes, so a new module shows up as soon as its class exists.

- **Completion** of module names after `Update =`, `Body =`, `Behavior =`, `Draw =` and `ClientUpdate =`
  (the list opens on the space after `=`), and inside a module block, of the fields that module reads.
- **Checks:** error on a module name no class carries (`Unknown module 'MoveUpdat'`; names are
  case-sensitive, as `ModuleFactory` is); warning on a field the module's `FieldParseTable` does not read.
  Fields are read from the `FieldParseTable.add("Speed", …)` calls in the module class, so they are not
  checked for a class with no source to read or one that builds no table.
- **Navigation:** Ctrl+Click a module name to open its class. Renaming the class renames the INI lines.
- A class with a `TAG_PREFIX` constant owns every name that starts with it: `Script:HeroBrain` is a
  `ScriptModule`. The part after the prefix comes from game data and is not checked.
- **Registration check** (Java): `ModuleFactory.register("mover", … new MoveUpdate(…) …)` is a warning,
  `Registered name 'mover' does not match class name 'MoveUpdate'`. The quick fix registers the class name
  and renames the INI lines that used `mover`.

Without the engine on the classpath, module lines are not checked and completion says
`Duke Engine not found on classpath`; everything else works as before.

### Assets

Every asset is written as its whole path from the resource root the INI file sits in
(`dungeon/src/main/resources`, the classpath root jME loads from): `Model = models/heroes/rogue.glb`,
`Icon = icons/skills/skill_arrow_shot.png`. No folder is put in front of a name, so a game may keep its files
in whatever structure it likes. Paths are read from disk; the engine is never started. A value is an asset
path when it ends in a model, image, sound or font extension (`.glb .gltf .obj .j3o`,
`.png .jpg .jpeg .tga .dds`, `.ogg .wav .mp3`, `.fnt`).

- **Completion** after an asset key: every file under the root of the kind that key takes, as a whole path.
  `Model` takes models, `Texture`/`Image`/`Icon` images, `Sound` sounds, `Font` fonts; other keys take the
  kind their other values have. Typing the file name finds it wherever it sits. Files are listed from disk
  each time, so a new asset shows up at once. `Icon = flask` and `FigureIcon = 30` are not paths, and get none.
- **Checks:** error on a path with no file behind it, letter case included (`Models/` is not `models/`, as
  on Linux CI), with `Did you mean …?` and a quick fix when a close match exists; warning on a file of the
  wrong kind (`Model = x.png`).
- **Navigation:** Ctrl+Click a path to open the file. Renaming or moving an asset does not update INI lines;
  the check flags them instead.

Outside a resource root (a loose file, an unexpected layout) asset paths are not checked or completed.

### Duke Inspector

A tool window on the right that opens the first time an INI file is selected, and shows that file as a form.
The file stays the truth: every change is written into it as text (Ctrl+Z undoes it, comments stay), and the
form is read again from the file.

- **Sections:** each block folds open; each module of a unit, and each section of a block (`Generation = Layout`
  in `World Dungeon`), is a group of its own inside it. The block the caret is in opens and scrolls into view.
- **Editors by what a field is:** a checkbox for `Yes`/`No`, a list for an enum, a list of files of the right
  kind for an asset path, a list of block names for a field that names a block (`Look = ArcherShot` lists the
  `DungeonEffect`s), a list of clips for an animation (`Attack = Melee_Attack`, read from the models'
  headers), and a text field otherwise. A number field takes only a number.
- **Adding:** `+ Field` lists the fields the block's code reads that it does not write yet, and the sections it
  may hold, written with their own `End` and the fields such sections usually have; `+ Module` lists
  the engine's modules by `@ModuleGroup`, written under the key files use for it and with the fields most
  files give it. Add Block (toolbar) adds any block type the game reads.
- **Add to this unit:** what units like this one have and it does not — a hero's portrait, a skill, a sound
  for each moment (`DungeonSound hurt.Goblin`) — one click each, with the fields such blocks usually have.
  **In other files** lists the unit's blocks that live elsewhere, as links.
- **New unit** (toolbar, or File > New > Duke Unit): a name, and either an empty unit with the blocks chosen
  or a copy of a unit renamed (headers, `Script:<Name>Brain`, `DisplayName`). The file goes beside the units
  like it and is added to the game's list of files (`DungeonContent`), after the others in its folder.

Nothing in it names a game. Block types and fields are read from the game's code: every
`initFromIni(x, TABLE)` and `TABLE`'s `add("Field", Ini.real(...))`, a section wherever a field's parser reads a table
of its own to `End` (`add("Generation", Ini.section(LAYOUT))`), and every template block a game registers
with `loader.type("Monster", Monster.class, ...)`, which takes the engine fields of the capabilities its record
implements (`Solid` → `Geometry…`, `Sighted` → `VisionRange`). What a field names, which blocks a unit usually
has and what a new block starts with are read from the INI files themselves. A unit is any block that carries
modules — `Object`, or a game's `Monster` — and `Body =`, `Update =` and the rest open a module in any of them.

Run it with `./gradlew runIde`, then open the duke-engine project in the IDE that starts. Tests:
`./gradlew test`. They also check that every file in `dungeon/src/main/resources/ini` loads with no problems.

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
