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

> The release APK is signed with the standard debug keystore (no secrets needed on a
> public build server), so it can be installed on any device out of the box. For store
> distribution, sign with your own key.

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

GitHub Actions (`.github/workflows/build.yml`) builds both targets on every push and
publishes the artifacts as GitHub Release v1.0.0. CI logs of failed runs are
published to the `ci-logs` branch.

## Repository layout

```
shared/       Kotlin Multiplatform module: UI, editor engine, notes DB, settings
  commonMain  shared Compose UI + logic (expect declarations)
  androidMain Android actuals (SAF file pickers, clipboard, SQLDelight driver)
  desktopMain Desktop actuals (AWT dialogs/clipboard, JDBC SQLite driver)
androidApp/   Thin Android entry point (single MainActivity)
desktopApp/   Thin desktop entry point (jpackage configuration)
```
