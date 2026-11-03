# Changelog

All notable changes to **Plugin Extension** are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.5] - 2026-11-03

### Added

- **Check for plugin updates.** A new *Check for updates* button in the Plugin Groups toolbar asks
  every installed plugin whether a newer version exists, using the update source each plugin declares
  in its `plugin.json` (`info.updateJSONURL`, format `{ "<plugin id>": { "latest": "...", "changes": [...] } }`).
  The check runs off the EDT, so the preferences dialog stays responsive.
- **Update markers in the plugin list.** A plugin with a newer version gets a blue `[update: <version>]`
  marker on its row.
- **Update summary dialog.** After a check, a dialog lists each plugin's `current → new` version together
  with the author-supplied change notes, and reports how many update sources could not be reached and how
  many plugins do not declare one at all.
- **Update counts per group.** The group column now shows how many plugins inside each group have an
  available update (e.g. *2 can update*), so you can see at a glance where updates are waiting without
  scrolling the plugin list. Groups with nothing to update show no extra line.
- **Open the plugin page.** A plugin with an update gains an *Open plugin page* entry in its row menu,
  which opens `https://mcreator.net/node/{pluginPageID}` in the browser.
- This plugin now ships an `updateJSONURL` and `pluginPageID` of its own, so MCreator's built-in update
  notification can report new versions of it.

### Changed

- **Version comparison is numeric, not textual.** MCreator itself only compares version strings for
  inequality, which reports a downgrade as an update. Versions are now compared piecewise on their
  numeric parts, so `2.10` is correctly newer than `2.9`, and a remote `2.3` is not offered as an update
  to a local `2.4`. Versions that cannot be parsed fall back to the plain inequality check so nothing is
  missed.
- **A failed update check is no longer reported as "up to date".** When an update source cannot be
  reached, it is counted as a failure and reported as such.
- **Switching groups no longer rescans the plugin archives.** Group membership is resolved through an
  index built once per reload instead of being recomputed per selection, so clicking between groups no
  longer walks every archive and allocates a fresh row list each time. Archive type and `plugin.json`
  metadata are additionally cached per archive (path + modification time + size) and invalidated when the
  archive changes. The initial load still scans the plugins folder once, on the UI thread.
- **Plugin archives are no longer overwritten when two plugins share a file name.** Moving an archive
  out of, or back into, the plugins folder now refuses to replace an existing file and reports the
  conflict instead of silently destroying one of the two archives.
- **Every failed move is now reported.** Both directions of a move are counted, a dialog explains which
  archives could not be moved and why, and the reason is written to the log. Previously a failed move
  back into the plugins folder left the plugin disabled with no message at all.
- **Group labels are fully localized.** Plugin metadata labels in the list were hardcoded English; they
  now follow the UI language.
- **The restart prompt distinguishes three outcomes** — moved, deferred until MCreator closes, and stuck
  — instead of always claiming the plugin would be moved on exit.
- **`Save`/`Apply` handlers are attached exactly once.** Reopening the preferences dialog no longer
  stacks a second listener, which previously could run the whole apply sequence twice per click.
- **Group configuration is hardened against hand-edited files.** Duplicate plugin IDs inside a group and
  duplicate IDs in the disabled list are collapsed, and group IDs that collide with the built-in
  *Ungrouped*/*Built-in* sections are rejected instead of corrupting the group list.

### Fixed

- **Disabling a plugin reported the wrong result.** A successful move into `.disabled` was reported as
  "will be moved when MCreator closes" and the plugin was still flagged as needing a manual move, because
  the decision was made from a stale snapshot. It now uses the actual outcome of the apply operation.
- **Packaged-code element fields no longer store the literal text `null`** when a combo box has no
  selection.
- **`RestartHelper` is now launched only when the old process has really exited.** On the 5-minute
  timeout it aborts instead of starting a second MCreator instance that would contend for the same
  workspace.
- **Restart failures are logged** instead of being swallowed silently.
- Folder-name validation and package-name normalization no longer recompile a regular expression on
  every keystroke.

### Known issues

- **`RestartHelper.main` is currently package-private.** It was made `public` during this release, because
  a `main` method that is not `public` cannot be started by the `java` launcher on JDK 21–24 and the
  helper's stderr is discarded — the result would be a restart that silently does nothing. A later
  "clean up comments" commit reverted the modifier along with the surrounding comments. It happens to
  work today because MCreator 26.2 bundles JBR 25, where JEP 512 relaxed the `main` lookup rule, and
  because `RestartService` spawns the helper with the running JVM's own `java.home`. Re-adding `public`
  would make this independent of the JVM version. See `RestartHelper.java` line 10.

### Internal

- New `UpdateService` holding the update-check logic, independent of the UI.
- New `PackageNames` holding the pure package/type-name normalization helpers, extracted from
  `PackagedCodeGUI` so they can be tested without loading Swing.
- Plugin metadata reading and archive type detection are cached behind a single lock; only positive
  results are cached, so an archive briefly held open by antivirus software is re-examined on the next
  call rather than being permanently ignored.

### Notes

- No automatic download or installation. Update metadata carries no download URL, and `mcreator.net`
  answers programmatic requests with a Cloudflare challenge, so the check only informs you and offers a
  link to the plugin page.
- Update results are not persisted: they live for as long as the preferences dialog is open. Run the
  check again after restarting MCreator.
- `pluginPageID` alone is not enough for a plugin to participate in checks. It is only used to build the
  link; `updateJSONURL` decides whether a plugin is checked at all. Both fields must be present for the
  *Open plugin page* entry to appear.

## [2.4] - 2026-11-03

Baseline release: plugin groups with enable switches, a disabled-plugin folder with restart handling,
and a *Packaged custom element* mod element type whose generated package path can be chosen freely.

