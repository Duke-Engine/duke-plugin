<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Duke-plugin Changelog

## Unreleased

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
