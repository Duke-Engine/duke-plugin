<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Duke-plugin Changelog

## Unreleased

### Added

- Duke language for `.duke` data files: highlighting, brace matching, line comments, folding of blocks and of lists
  over several lines, and a structure view of the blocks.
- Ctrl+Click from a block's word to the Java class it is read as — a record, a module's class, a record a sealed type
  permits — from a key to the record component it fills, and from an enum value to its constant.
- Keys, blocks, enum constants, `Yes`/`No` and asset paths completed from the game's records.
- The engine's own checks in the editor, in its own words: an unknown block or key, a value its type cannot read, a
  list where one value goes and one value where a list goes, a key or a block written twice, a block with no `End`,
  a list never closed. A header written `Monster Brute` is fixed to `Monster` with `Name = Brute` inside it.
- Renaming a module class, a record component or an enum constant renames it in the data files.
- `.duke` paths, as a manifest lists them, checked and opened like asset paths.

### Removed

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
