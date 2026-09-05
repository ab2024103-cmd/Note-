<img src="assets/branding/notepad-pro.png" width="72" height="72" alt="NotePad Pro logo">

# NotePad Pro

A lightweight, offline-first rich-text notepad for Windows and Android, rebuilt in
**Kotlin Multiplatform + Compose Multiplatform** (shared UI in one codebase, thin
platform entry points). No Electron, no WebView, no network — your notes never leave
your device.

- Windows: native **`.exe`** and **`.msi`** installers (jpackage)
- Android: **`.apk`** (minSdk 21, API 5.0+)
- Notes stored locally in SQLite (`notepad-pro.sqlite3`), line-based rich editor with
  `LazyColumn`, undo/redo, find & replace, highlight colors, checklists, autosave
- Exactly two themes: **Light** and **Dark** (plus Follow-System)

## Download

Built automatically by GitHub Actions on every push and published to the GitHub
Release **[v1.0.0](https://github.com/ab2024103-cmd/Note-/releases/tag/v1.0.0)**:

| File | Link | About |
| ---- | ---- | ----- |
| Windows installer (.exe) | [NotePadPro-1.0.0.exe](https://github.com/ab2024103-cmd/Note-/releases/download/v1.0.0/NotePadPro-1.0.0.exe) | Per-user native installer (EXE) |
| Windows installer (.msi) | [NotePadPro-1.0.0.msi](https://github.com/ab2024103-cmd/Note-/releases/download/v1.0.0/NotePadPro-1.0.0.msi) | Per-user MSI installer |
| Android APK (release) | [androidApp-release.apk](https://github.com/ab2024103-cmd/Note-/releases/download/v1.0.0/androidApp-release.apk) | Minified/shrunk, signed with the debug key so it installs directly |
| Android APK (debug) | [androidApp-debug.apk](https://github.com/ab2024103-cmd/Note-/releases/download/v1.0.0/androidApp-debug.apk) | Debug build |

Checksums: [CHECKSUMS.md5](https://github.com/ab2024103-cmd/Note-/releases/download/v1.0.0/CHECKSUMS.md5)

> The APKs use a CI debug signing key, not a stable production key. Export/back up
> your notes before updating: keys can differ between builds and block in-place
> updates. **Do not uninstall the old app before backing up** — uninstalling removes
> local notes. For store distribution and reliable updates, use a stable signing key.

## Features

- **Notes sidebar** with search, pinning (★) and delete; auto-refreshes from the DB
- **Multi-tab editing** with session restore across restarts
- **Rich line editor**: whole-line background colors, inline highlights (six colors),
  bullet / numbered / checklist lists with indent & outdent
- **Find & Replace** panel (case-sensitive, next/prev, replace one/all)
- **Extract by color** panel with copy-to-clipboard output
- **Autosave** (debounced) into SQLite; plain-text file import/export (Open/Save/Save As)
- **Status bar**: save state (Ready/Autosaving/Saving/Saved), word count, Ln/Col, zoom
- **Reduced-motion** setting disables panel slide/fade animations
- Offline-first, memory-conscious: lazy line rendering, bounded undo, largeHeap disabled

### Keyboard shortcuts

| Shortcut | Action |
| -------- | ------ |
| Ctrl+N / Ctrl+O | New note / Open file |
| Ctrl+S / Ctrl+Shift+S | Save / Save As |
| Ctrl+F / Ctrl+H | Find / Replace |
| Ctrl+B | Toggle sidebar |
| Ctrl+Z / Ctrl+Y (or Ctrl+Shift+Z) | Undo / Redo |
| Ctrl+Shift+8 / 7 / 9 | Bullet / Numbered / Checklist list |
| Tab / Shift+Tab | Indent / Outdent (list lines) |
| Ctrl+= / Ctrl+− | Font size + / − |
| Esc | Close the top-most overlay |

## Building

Prerequisites: JDK 17, Android SDK 34 (for the APK).

```bash
./gradlew :desktopApp:packageExe   # Windows EXE installer
./gradlew :desktopApp:packageMsi   # Windows MSI installer
./gradlew :androidApp:assembleRelease
```

Run the shared editor regression tests (autosave/cancellation and Compose focus)
on the desktop JVM target:

```bash
./gradlew :shared:desktopTest
```

These tests also run in the Windows CI job before packaging. On headless Linux,
use `xvfb-run -a ./gradlew :shared:desktopTest` if a display is required.

GitHub Actions (`.github/workflows/build.yml`) builds both targets on every push and
publishes the artifacts as GitHub Release v1.0.0 only after both platform jobs
succeed. Build logs and editor test reports are available as Actions artifacts,
including for failed runs; CI does not write a separate log branch.

## App logo and icons

The original vector artwork is `assets/branding/notepad-pro.svg`. The same logo is
used in the app header/About dialog and desktop window, Android legacy/adaptive
launchers (including Android 13 themed icons), and Windows EXE/MSI shortcuts.

To regenerate the checked-in PNG, ICO and vector resources, install ImageMagick:

```bash
python3 tools/generate_icons.py
python3 tools/generate_icons.py --check
```

The check command uses only Python's standard library and also runs in CI. Normal
app builds use the checked-in resources and do not require an image-generation tool.

## Repository layout

```
shared/       Kotlin Multiplatform module: UI, editor engine, notes DB, settings
  commonMain  shared Compose UI + logic (expect declarations)
  androidMain Android actuals (SAF file pickers, clipboard, SQLDelight driver)
  desktopMain Desktop actuals (AWT dialogs/clipboard, JDBC SQLite driver)
androidApp/   Thin Android entry point (single MainActivity)
desktopApp/   Thin desktop entry point (jpackage configuration)
```
