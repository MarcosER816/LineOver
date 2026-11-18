# Stage 08 — History and projects

## Goal

Make editing safe: undo / redo, a named history, snapshots, versions, the `.loproj` project
file with autosave and crash recovery, and a schema migration framework.

## Definition of Done

- [x] Property test, random op sequences: undo all == original graph, redo all == final
      (`EditHistoryTest.randomOpSequencesUndoToTheOriginalAndRedoToTheFinalGraph`: 300
      sequences of up to 80 random edits on two layers, random drags / taps / releases)
- [x] Round-trip save / load equality (`ProjectFileTest.saveThenLoadGivesTheSameProject`,
      `ProjectLibraryTest.aSavedEditLoadsBackAndUpdatesTheHomeThumbnail`, export → import in
      `anExportedProjectFileImportsAsANewProjectWithItsOriginalAndEdit`)
- [x] Migration test v1 → v1 no-op scaffold (`ProjectMigrationsTest.v1ToV1IsANoOp`, plus a
      chained 1 → 2 → 3 → 4 run, a missing step and a newer file refused)
- [x] Kill-during-save test, truncated temp ignored
      (`ProjectFileTest.killDuringSaveKeepsThePreviousFileAndIgnoresTheTruncatedTemp`,
      `aCrashBetweenTheRenamesRecoversTheCompleteTempOrTheBackup`, files cut at 6 lengths are
      refused, `ProjectLibraryTest.anInterruptedEditingSessionIsRecoveredOnTheNextLaunch`)
- [x] CI green; APK built

## What was built

| Module | Content |
| --- | --- |
| `:core:model` | `EditHistory` (immutable list of `HistoryEntry(graph, label, key)`, 500 max, open-step coalescing, undo / redo / jumpTo), `EditGraph.sharing` (structural sharing), `HistoryLabels` (names and coalescing keys from graph diffs), `ProjectDocument` (versions each with a history, snapshots), JSON codecs |
| `:core:io` | `projectfile/`: `ProjectFile` (ZIP format), `ProjectMigrations`, `AtomicFiles` (temp + fsync + `.bak` + rename, `recover`); `ProjectDocuments` (load / save / editing markers / `recoverInterrupted` / export / import of `.loproj`), edited Home thumbnails, `TapGestures` preference |
| `:core:engine-native` | `thumbnail.h/.cpp`: box-filtered ≤ 512 px copy of the preview, a graph on the CPU path, sRGB display RGBA8; `Engine::renderThumbnail`, C API, JNI; ctest `thumbnail_test` |
| `:core:engine` | `EngineSession.thumbnail(graph)` |
| `:feature:editor` | History in the ViewModel (`EditorHistory.kt` reducers), `EditorAutosave`, top-bar Undo / Redo, `HistoryPanel` (History list with preview + Restore, Snapshots, Versions grid with thumbnails, naming dialog), undo snackbars, `MultiFingerTap` in `CanvasView`, slider releases → `EndEdit`, save on `ON_STOP` and on close |
| `:feature:home` | Open `.loproj` (Open file), project menu → Save project file, "Recovered your edits" on launch |
| `:feature:settings` | Settings → Gestures: tap mapping (2 undo · 3 redo / swapped / off) and gesture help |
| `:core:ui` | 5 tools `done` (162 tools, 35 done) |

Tests added: model `EditHistoryTest` (7), `HistoryLabelsTest` (2); io `ProjectFileTest` (7),
`ProjectMigrationsTest` (3), `ProjectLibraryTest` +4; ctest `thumbnail_test` (2); editor
`EditorHistoryTest` (10), `MultiFingerTapTest` (2); home `HomeViewModelTest` +4;
instrumented `RealPhotoTest.thumbnailsRenderTheGraph`; screenshots of the History sheet
(history, preview, snapshots, versions).

## Decisions and deviations

1. **History = graphs.** `EditHistory` holds whole graphs (no pixels, rule DO NOT); each entry
   reuses the previous entry's unchanged `Layer` and op instances (`EditGraph.sharing`), so a
   step costs only what it changed. 500 entries max: the oldest is dropped and becomes the start.
2. **One drag, one step.** Every change is recorded at once; while the top step is *open* a
   change of the same control (same key, from `HistoryLabels`: `exposure.ev`) replaces it. The
   step closes on release (`LoSlider` / wheel `onValueChangeFinished` → `EndEdit`), when another
   control changes, on undo / redo / restore, when a panel closes and before the autosave.
   Dragging back to the start removes the step. One-off actions are labelled ("Auto light",
   "White balance: eyedropper", "Snapshot: …").
3. **Labels** come from the graph difference: one control → "Exposure +0.7", "Highlights -40",
   "Temperature 5600 K"; a removed op → its neutral value ("Exposure 0"); lists → the op name
   ("Curves"); several ops → "Exposure +1 + 1 more".
4. **Restore** moves the current step (later steps stay redoable until the next edit) and its
   snackbar can undo it; a snapshot's Restore is a new step. Tapping a row previews it on the
   canvas (the edit is unchanged) until Restore or Cancel.
5. **Versions are branches**: each has its own graph and history; switching saves the current
   history into the version and loads the other. A project keeps at least one version.
6. **Project file**: `manifest.json` + `graph.json` + `document.json` + `original` or a
   reference + `paint/` + `thumbnails/`. Autosaves *reference* the original (no copy every
   5 s); shared files (Save project file) *embed* it, stored uncompressed. The manifest is the
   last entry and holds SHA-256s, so a cut-off or altered file is refused. `paint/` is written
   and read as opaque zstd tiles; painting itself arrives in Stage 18.
7. **Autosave**: 5 s after the last change (`EditorAutosave`, the timer restarts on every
   change), immediately on `ON_STOP`, and on close (then the editing marker is removed and the
   engine closes). Saves run on the app scope and skip an unchanged document. A crash loses at
   most the last 5 s of edits made while the app stayed in the foreground.
8. **Crash recovery**: an `editing` marker exists while the editor is open. On the next launch
   Home finds leftover markers, `AtomicFiles.recover` puts back the newest intact file (main,
   else a complete temp, else the backup), deletes truncated temps and says "Recovered your
   edits of …". A project file that cannot be read (e.g. from a newer LineOver) is never
   overwritten: History says "Not saved".
9. **Migrations**: `ProjectMigrations` runs JSON-level steps from the file's `schemaVersion` up
   to `ProjectFile.SCHEMA_VERSION` (1 now, so no step yet) and refuses newer files.
10. **Taps**: `MultiFingerTap` counts a tap only when all fingers lift within 250 ms of the
    first touch and none moved more than 10 dp (the brief's IF WRONG rule, applied from the
    start), so pinch-zoom and pan never undo. Default: two fingers undo, three redo;
    Settings → Gestures can swap or switch them off. HyperOS's three-finger screenshot can
    take the three-finger tap: Gestures help says how to turn it off; the top bar Redo is
    always there.
11. **Thumbnails**: the engine renders a graph on a ≤ 512 px box-filtered copy of the preview
    with the CPU reference ops and the (gamut-mapped) sRGB display transform, on any thread.
    Used for the Versions grid, `thumbnails/` in the file and the Home thumbnail
    (`thumb-<time>.jpg`, since Home caches thumbnails by path).
12. **Size**: detekt's limits made the ViewModel split: history reducers are pure functions
    (`EditorHistory.kt`), saving is `EditorAutosave`; `ProjectLibrary` delegates its project
    file functions to `ProjectDocuments`.
13. Version 0.8.0 (10). Branch: session branch `ccr-a94fce54-a1j7s3`.

## Dependencies added (R10)

None (ZIP and SHA-256 come from the JDK; `kotlinx-serialization-json` was already used by
`:core:model` and is now also applied to `:core:io` through `lineover.kotlin.serialization`).

## Golden images changed (R4)

No golden file changed.

Screenshots changed, and why:

| Screenshot | Why |
| --- | --- |
| Every editor shot (compact, expanded, landscape, narrow) | Undo / Redo are disabled when there is nothing to undo / redo (only the top-bar band differs) |
| `*/sheet_history_{history,snapshots,versions}` | The History sheet's real content (sample: 5 steps with 1 undone, 2 snapshots, 2 versions) replaces the placeholders |
| `*/sheet_history_preview` | New: a row being previewed |
| `settings/*/settings` | Gestures row: new summary, no "Stage 28" |

## Troubleshooting log (R11)

| Symptom | Root cause | Fix |
| --- | --- | --- |
| "Layers" as the label of the first edit | An empty graph has no background layer yet | Labels treat a missing layer as empty |
| "Highlights / Shadows" instead of "Highlights -40" | Adding an op counted its untouched fields as changed | A missing op's fields are compared as neutral |
| detekt: `ProjectFile.read` complexity, library / ViewModel size | Features grown into single functions / classes | Split into helpers, `ProjectDocuments`, `EditorAutosave`, pure reducers (no suppressions) |

Plan B used: no.

## Proof

- CI run and APK artifact: see the Stage 8 report

## Phone checklist

POCO F7 Ultra, `lineover-<sha>.apk`:

- [ ] Open a photo, make 20 edits (sliders, curves, colour). History lists them by name.
- [ ] Tap the photo with two fingers five times: five steps go back ("Undo: …" toast each time).
- [ ] Tap with three fingers: one step forward. (If HyperOS takes the tap for a screenshot,
      see Settings → Gestures, or use Redo in the top bar.)
- [ ] Pinch-zoom and pan: no undo happens.
- [ ] History → tap an older row: the photo shows it; Restore; Undo on the snackbar.
- [ ] Snapshots → Save snapshot; edit more; tap the snapshot, Restore.
- [ ] Versions → New version; change it; the grid shows both with thumbnails; switch back.
- [ ] Force-stop LineOver (Settings → Apps → LineOver → Force stop), reopen it, open the
      project: the edits and the history are back. Home's thumbnail shows the edit.
- [ ] Home → project menu → Save project file → open that file with Open file: a new
      project with the same edit.

## Open questions (R12)

1. **Gesture mapping**: two fingers undo, three redo (default), or the other way round, or
   off? Settings → Gestures switches it either way. Default: two undo, three redo.
2. **History limit**: 500 steps per version. Higher? A step costs only the changed ops (a few
   hundred bytes), so 1000–2000 is affordable; the project file grows by about the same per
   step. Default: 500.
3. (Stage 6) White balance / HSL strength compared with another editor?
