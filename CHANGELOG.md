<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Duke-plugin Changelog

## Unreleased

### Added

- Duke language for `.duke` data files: highlighting, brace matching, line comments, folding of blocks and of lists
  over several lines, and a structure view of the blocks.
- Every line of a block names a field of its class: `Geometry = Cylinder` with its fields under it, `Modules = [` a
  block for each `]`, `Armor` for a map — as the engine's `Binder` reads it.
- Ctrl+Click from a key to the record component it fills, from a class — a block's word or the class after `=` — to
  the Java class it is read as (a record, a module's class, a record a sealed type permits), and from an enum value
  to its constant.
- Keys, maps, the classes a field may be, the records a list holds (each written with its `End`), enum constants,
  `Yes`/`No` and asset paths completed from the game's records.
- A block written on its own where a field goes says how it is written: `Geometry = Cylinder`, `Modules = [ … ]`.
- The engine's own checks in the editor, in its own words: an unknown block or key, a value its type cannot read, a
  list where one value goes and one value where a list goes, a key or a block written twice, a block with no `End`,
  a list never closed. A header written `Monster Brute` is fixed to `Monster` with `Name = Brute` inside it.
- Renaming a module class, a record component or an enum constant renames it in the data files.
- `.duke` paths, as a manifest lists them, checked and opened like asset paths.
- Links between blocks: a component marked `@Link(AnimationSet.class)` names a block of that record —
  `Animations = Humanoid` — so its value is completed from the blocks there are, opened by Ctrl+Click, and flagged
  when no block is called that.
- Clip names completed: a component marked `@Clip` — `Walk`, `Idle`, a portrait's clips, a skill's `CastAnim` — is
  offered the clips inside the model and animation files the block is drawn from, and those of the set it links, read
  out of the files themselves.
- A misindented value: the line under `Effect = EmberEyes` indented by mistake is flagged on that field, where the
  engine says it, rather than as the block around it having no `End`.
- A block's word that a game's record shares with an engine one (a game's `Fog` beside the client's) opens the
  game's own, else the one whose fields the block writes.
- **Duke Inspector** for `.duke` files: the block under the caret as a form drawn from its record — groups from
  `@Group`, an editor for each type, defaults from `DEFAULTS`, help from the Javadoc — that writes each change
  into the file as one undoable edit. Lists of blocks are cards; a record inside another is a step in.
- **New from Template**: a new `.duke` file from a record or a copy of a block, listed in the game's `Files`.
- **Preview** above the Inspector's form: a block's model dressed as the game dresses it, its clips on buttons, and
  its sounds — played in the IDE's own browser with three.js, from the game's files.
- **Add Sound…**: a sound for a moment of the block's (`died.Skeleton`), copied from the last one for that moment.
- **Map** tab for a block with a `@Grid`: its cells, rooms and things drawn from above; things put down, moved and
  taken off by hand, each as a line of the file. Replaces the separate world builder.
- **Play**: the game run from the IDE — on the map being edited, or from its start — in the Run window.
- A path is found under any resource root of the project, as the classpath finds it: a game's file may name the
  kit's (`kit/effects/particles/star_04.png`).
- A `@Link` on a record written on one line, `Boss = Warden 8 5`, names its first word: checked, completed and
  opened like any link.

### Removed

- The Duke INI language — highlighting, checks, structure view and its Inspector: the world and maps are `.duke`
  files now, and the Inspector reads those.
- Module lines in INI files (`Update = MoveUpdate Tag`), with their completion, checks and navigation: the engine
  reads modules from `.duke` blocks named by their class.
- The check of `ModuleFactory.register` names: a module is registered by its `Data` record, with no name to get wrong.
- Template block types in INI files (`Object`, a game's `Monster`): they are `.duke` records.
- File > New > Duke Unit, and the Inspector's unit suggestions and module menu, which wrote INI units the engine no
  longer reads.

## 0.1.0-beta.1 - 2026-09-19

### Added

- Duke INI language: highlighting, folding, structure view, checks for unclosed blocks, stray `End`
  and unrecognized lines, and Ctrl+Click navigation between blocks.
- Module names and module fields completed and checked against the engine's `Module` classes, with
  Ctrl+Click from a module name to its class.
- Java inspection for a module registered under a name other than its class name, with a quick fix.
- Asset paths, each written whole from the resource root, completed from there, checked with exact letter
  case (with a suggested fix for near misses) and by kind, and opened by Ctrl+Click.
- Duke Inspector tool window: the selected INI file as a form, with editors chosen by what each field is,
  modules added by group, the blocks a unit is missing offered in one click, and every change written into
  the file as text.
- File > New > Duke Unit: an empty unit or a renamed copy of one, added to the game's list of files.
- Template block types a game registers (`Monster`, `Hero`…) are read with their fields: the engine's for the
  capabilities the record implements, and the game's own; module lines are recognised in any of them.
- Sections inside a block, `Generation = Layout` to an `End` of their own, as a game's `World` block holds them:
  highlighted, folded, listed in the structure view, and in the Inspector each a group with its own fields and
  the fields its code reads.
