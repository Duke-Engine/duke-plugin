<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Duke-plugin Changelog

## [Unreleased]

### Added

- Duke INI language: highlighting, folding, structure view, checks for unclosed blocks, stray `End`,
  unrecognized lines and duplicate keys, and Ctrl+Click navigation between blocks.
- Module names and module fields completed and checked against the engine's `Module` classes, with
  Ctrl+Click from a module name to its class.
- Java inspection for a module registered under a name other than its class name, with a quick fix.
- Asset paths, each written whole from the resource root, completed from there, checked with exact letter
  case (with a suggested fix for near misses) and by kind, and opened by Ctrl+Click.

### Changed

- The completion list opens on the space after `Key =` through a typed handler, replacing the deprecated
  `CompletionContributor.invokeAutoPopup`.

### Removed

- The template's sample tool window.
