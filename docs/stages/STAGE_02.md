# Stage 02 — App shell, home and settings

## Goal

Navigation and the non-editor screens: Home (import, project list), Settings, and
placeholders for the Editor and the Export sheet, on phones, foldables, tablets and in
landscape. No editor internals.

## Definition of Done

- [x] All routes work: Home → Editor(projectId) → Export sheet, Settings → section,
      Settings → Debug → gallery (debug builds); proven on the emulator by `NavigationTest`
- [x] Picking a photo creates a project row and navigates to the Editor placeholder
      (`HomeViewModel`: one imported photo → `OpenEditor`; `ProjectLibraryTest` imports a real image)
- [x] Settings rows exist; the reserved ones are labelled with their stage
- [x] Screenshot tests: Home (empty, filled, undo) and Settings at compact and expanded
      widths, plus Editor (portrait and landscape) and Export sheet
- [x] Room migration test (`ProjectDatabaseMigrationTest`, 1 → 2, built from the committed schemas)
- [x] CI green; APK built (links under Proof)

## What was built

| Module | Content |
| --- | --- |
| `:app` | `LineOverApp` (lazy `AppContainer`, empty `onCreate`), Navigation 3 routes and `LineOverNavigation` (predictive back, reduced-motion transitions, export sheet as a dialog scene), SplashScreen theme, `enableOnBackInvokedCallback`, `DebugTools` (gallery in debug, `null` elsewhere) |
| `:core:model` | `Project` |
| `:core:io` | Room `ProjectDatabase` v2 + `MIGRATION_1_2`, `ProjectDao`, `ProjectRepository`, `ProjectLibrary` (import / rename / duplicate / delete / restore / purge / opened), `Thumbnailer` (ImageDecoder at ≤ 512 px), `ProjectStorage`, `ProjectNames` |
| `:core:ui` | `rememberLoWindowInfo()` (WindowSizeClass breakpoints), `ProjectThumbnail` + `FileThumbnailLoader` (IO dispatcher, LRU cache) |
| `:core:designsystem` | 11 more Material Symbols (settings, folder, add photo, edit, copy, delete, …) |
| `:feature:home` | `HomeViewModel` (MVI), `HomeScreen`, `HomeRoute` (photo picker, document picker) |
| `:feature:editor` | `EditorViewModel` (marks the project opened), `EditorScreen` placeholder |
| `:feature:export` | `ExportViewModel`, `ExportSheet` placeholder (Stage 12 slots) |
| `:feature:settings` | `SettingsViewModel`, `SettingsScreen` (two panes when expanded), `SettingsSectionScreen`, `SettingsSection` (stages), `AiModelRow`, open-source licence list |

Tests added: `ProjectDatabaseMigrationTest` (2), `ProjectLibraryTest` (7, real Room + real
image decode), `ProjectNamesTest`, `ThumbnailMathTest`, `LoWindowInfoTest`,
`HomeViewModelTest` (8), `FormatSizeTest`, 19 screenshots; instrumented `NavigationTest`
(3, replaces Stage 1's `DebugMenuTest`). Unit + screenshot total: 121.

## Tools delivered

None. `TOOL_CATALOG.md` is unchanged: the Settings rows are settings, not tools, and the
`home/…` slots in the catalog belong to Stages 25, 27 and 28.

## Decisions and deviations

1. **Navigation 3 1.2.1** (stable). Its adaptive list-detail scene lives in
   `material3-adaptive-navigation3`, which needs compileSdk 37, so Settings does its own
   two panes and narrow windows open a section as its own route.
2. **WindowSizeClass from `androidx.window` 1.5.1** instead of `material3-adaptive` 1.3
   (compileSdk 37). Same breakpoints (600 / 840 dp).
3. **Sources**: a persistable read permission is taken for photo-picker and SAF URIs
   (both support it); only if that fails is the original copied to
   `projects/<id>/original` (the brief's plan B, implemented as the fallback path).
   Persisted permissions are released when no project uses them any more.
4. **Schema version 2 in the first release.** v1 is exactly the brief's table; v2 adds
   `opened` for the Recent tab, so the migration path and its test exist from day one. No
   device ever had v1; the migration is still real and tested.
5. **Multi-select import** (up to 50) stays on Home and reports the count; only a single
   photo opens the editor directly.
6. **Delete with undo**: the row disappears at once; files are removed only after the
   5 s undo window (or when Home's ViewModel is cleared), on the app scope.
7. **Debug gallery** moved from the Stage 1 floating debug button to Settings → Debug; the
   row exists only in debug builds and release builds contain no gallery code.
8. **One-handed layout**: tab switch, Open file and the large Import button sit in an
   opaque bar at the bottom; Settings is a secondary action at the top right.
9. **"Open file"** accepts `image/*` (JPEG, RAW, TIFF…) and `application/octet-stream`
   (.loproj). A .loproj is recognised and explained ("arrives in Stage 8"). TIFF and most
   RAW files get a placeholder thumbnail until their decoders exist (Stages 12 / 25).
10. **Strings are in Kotlin**, English only, for now.
11. **detekt**: `TooManyFunctions` thresholds raised to 20 (DAO / repository / library APIs
    and Compose screen files) and `MagicNumber.ignoreEnums` (stage numbers in enums).
12. **Branch**: session branch `ccr-a94fce54-a1j7s3`, as in earlier stages. Version 0.2.0.

## Dependencies added (R10)

All Apache-2.0, all checked to build against compileSdk 36.

| Dependency | Version | Why |
| --- | --- | --- |
| androidx.navigation3 runtime + ui | 1.2.1 | Navigation, predictive back |
| androidx.lifecycle viewmodel-navigation3, viewmodel-compose, runtime-compose | 2.10.0 | Per-entry ViewModels, `collectAsStateWithLifecycle` |
| androidx.room runtime, ktx, compiler (KSP), testing; Room Gradle plugin | 2.8.5 | Projects database, schema export, migration tests |
| com.google.devtools.ksp | 2.3.14 | Room annotation processing |
| androidx.core:core-splashscreen | 1.2.0 | SplashScreen API |
| androidx.window:window-core | 1.5.1 | WindowSizeClass |
| Material Symbols Rounded (11 more icons) | — | Icons |

## Golden images changed (R4)

None changed. New screenshots under `feature/*/src/test/screenshots/`.

## Troubleshooting log (R11)

| Symptom | Root cause | Fix |
| --- | --- | --- |
| `computeWindowSizeClass` unresolved | It is a top-level extension in `WindowSizeClassSelectors` | Import it explicitly (found with `javap` on the AAR) |
| Home screenshot: a card visible through the tab switch | The bottom actions had no background over the scrolling grid | Opaque bottom bar plus an opaque strip behind the navigation bar |
| Tab switch stretched across tablets | `weight(1f)` overrides `widthIn(max)` | Weighted box containing a width-capped switch |
| `FormatSizeTest`: 716 KB instead of 717 KB | Truncation | Round |

Plan B used: no.

## Proof

- CI run and APK artifact: see the Stage 2 report (PR and `main` run links)

## Phone checklist

POCO F7 Ultra, the app (`lineover-<sha>.apk`):

- [ ] First launch: dark splash with the icon, then Home with "Nothing here yet".
- [ ] Tap **Import**, pick **3 photos** → "Imported 3 photos"; three thumbnails appear.
- [ ] Tap **Import**, pick **1 photo** → the Editor placeholder opens with its name.
- [ ] In the Editor tap **Export** → the sheet slides up; tap outside or ✕ to close.
- [ ] Swipe back from the edge slowly → the **predictive back preview** shows Home behind
      the Editor; release → Home.
- [ ] Long press a project → Rename (new name sticks), Duplicate ("… copy" appears),
      Delete → "Deleted …" with **Undo** (tap it: the project returns).
- [ ] **Kill the app** (recents → swipe away), reopen → all projects are still there.
- [ ] Rotate to **landscape**: Home shows more columns; Settings shows list and details
      side by side.
- [ ] Settings → each row opens; reserved rows say "Coming in Stage N"; About lists licences.
- [ ] Open file → pick a RAW or TIFF → a project with a placeholder thumbnail.
- [ ] No storage permission is ever requested.

## Open questions (R12)

1. **Default sort**: Projects tab sorted by last modified, newest first? Default: yes.
2. **Device albums on Home** (needs `READ_MEDIA_IMAGES`)? Default: no; the photo picker
   needs no permission.
