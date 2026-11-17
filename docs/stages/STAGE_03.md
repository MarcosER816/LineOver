# Stage 03 — Editor shell

## Goal

The complete editor layout, with a visible, reachable slot for every tool in
`TOOL_CATALOG.md`, a GL canvas with zoom / pan / rotate gestures, the side sheets, the
export sheet layout and tool search, before any engine exists. **This is the last pure-UI
stage: the layout is frozen from here on (R9).**

## Definition of Done

- [x] Every tool of the catalog has a slot reachable in ≤ 2 taps. Proven per tool by
      `Compact/ExpandedEditorReachabilityTest` (each tool: a fresh editor, the taps a finger
      makes are counted, the slot must exist), `ExportReachabilityTest` (one tap: Export),
      `HomeReachabilityTest` (Home → More, Open file) and `SettingsReachabilityTest`
      (Settings → Gestures). A mutation check (slot tag removed) makes it fail.
- [x] A unit test asserts ToolRegistry == catalog (`ToolCatalogTest`: same ids, names,
      categories, slots, stages, statuses, order, and the headline count)
- [x] Screenshot tests: the strip of each of the 18 categories at compact and expanded
      widths, Favorites with pinned tools, every side sheet (Layers, History ×3 tabs, Masks,
      Info), the add-layer / add-mask / More menus, selection options bar, canvas overlays,
      tool search (empty, "heal", "erase object"), the 3 coach marks, the unsupported-format
      state, a landscape phone, and the export sheet (compact and expanded)
- [x] Gesture unit test for the matrix math (`CanvasTransformTest`, `TwoFingerTrackerTest`,
      `FlingDecayTest`: focal-point zoom, 0.1×–32× clamp, pan limit, rotation snap + haptic
      once, fit of a rotated photo, double tap, inverse mapping, GL matrix vs mapping,
      frame-rate-independent fling)
- [x] Frame-timing instrumentation test (`CanvasFrameTimingTest`): pans the real GL canvas
      for 2 s and logs the P95 frame interval and GL draw time (tag `LineOverPerf`);
      SwiftShader = correctness only
- [x] Gestures cause no recomposition (`CanvasRecompositionTest`: the real editor around
      the real `CanvasView`, a pinch + pan + rotate, recompositions counted: 0)
- [x] CI green; APK built (links under Proof)

## What was built

| Module | Content |
| --- | --- |
| `:core:ui` | `tools/ToolRegistry` (162 tools: id, name, icon, category, place, slot, stage, panel type, synonyms, done), `ToolSearch` (names, synonyms, word prefixes, spelling variants), `ToolCategoryUi` (labels, icons, category-bar entries) |
| `:core:designsystem` | 89 more Material Symbols; `LoToolButton(onLongClick)`, `LoPanel(maxHeight)`, `LoCategoryBar(itemModifier)` and its first positioning (see R4) |
| `:core:io` | `ProjectLibrary.decodePreview` + `PreviewDecoder` (ImageDecoder straight to ≤ 2048 px, software, sRGB, with the original's size); `AppPreferences` (last category, coach marks, favourites, canvas surround) in SharedPreferences, plus an in-memory twin for tests |
| `:feature:editor` | `EditorViewModel` (MVI), `EditorScreen` (compact and wide layouts), `EditorChrome` (top bar, tool strip, category bar, category rail, selection options bar, More menu), `EditorPanels` (tool placeholder, Layers, History, Masks, Info, category list), `ToolSearchScreen`, `CoachMarks`; `canvas/` `CanvasView` (GLSurfaceView), `CanvasRenderer` (GLES 3.0 quad, frame stats), `CanvasTransform`, `TwoFingerTracker`, `FlingDecay` |
| `:feature:export` | Export sheet layout: format chips (7), quality, size, colour space, metadata, watermark, Save project, Export |
| `:feature:home` | Home → More: HDR merge, Panorama, Focus stacking, Batch, Sync settings (reserved slots, each says its stage) |
| `:feature:settings` | Canvas surround works (dark / mid grey, saved, used by the canvas); rows carry test tags |
| `:app` | `AppContainer.preferences`; editor and settings routes receive them |
| CI | `screenshot-gallery` artifact: every committed screenshot |

Tests added: `ToolCatalogTest` (4), `ToolSearchTest` (7), `CanvasTransformTest` (14),
`TwoFingerTrackerTest` (3), `FlingDecayTest` (2), `EditorViewModelTest` (11),
`Compact/ExpandedEditorReachabilityTest` (4), `CanvasRecompositionTest`,
`ExportReachabilityTest`, `HomeReachabilityTest`, `SettingsReachabilityTest` (2),
`AppPreferencesTest` (2), `ProjectLibraryTest` +2 (preview decode), 78 new screenshots;
instrumented `CanvasFrameTimingTest`. Unit + screenshot total: 223.

## Tools delivered

| id | Name | UI slot | Tests |
| --- | --- | --- | --- |
| `workflow.tool_search` | Tool search | `topbar/tool-search` (More → Tool search) | `ToolSearchTest`, `EditorViewModelTest.aToolFromSearchSwitchesToItsCategory`, reachability, screenshots |

Every other tool now has its visible placeholder slot ("Arrives in Stage N") and stays
`reserved`. Favourites pinning (long-press a tool) works now; `favorites.favorites`
(customising: reorder, Stage 28) stays reserved.

`TOOL_CATALOG.md` updated: yes (tool search done, headline 162 tools, 1 done). It is checked
against `ToolRegistry` by `ToolCatalogTest`, so neither can change alone.

## Decisions and deviations

1. **ToolRegistry lives in `:core:ui`**, not `:feature:editor`: Home needs it too (merge,
   batch, sync slots) and features never depend on each other. `:core:model` cannot hold
   it (icons are Android resources). The catalog is *checked* against it, not generated, so
   the catalog's prose stays hand-written.
2. **Layout.** Phone: top bar 56 · canvas · selection options (Select only) · tool strip 56 ·
   category bar 64. The tool panel **overlays the lower part of the canvas** and is capped
   at 40 % of the canvas height (`LoPanel(maxHeight)`), so opening a panel never resizes
   the GL surface (a resize per animation frame would cost frames). Sheets open at their
   tall detent, tool panels at the low one.
3. **Wide layout** (rail + 320 dp right column) when the width class is Expanded (≥ 840 dp:
   tablets, unfolded foldables) or Medium in landscape (landscape phones). The rail is
   88 dp. With no panel open the right column lists the category's tools.
4. **Placement of the sheets.** Layers and History open from the top bar; the **Masks sheet
   is the Masks category's panel** (the strip holds the 10 mask types, the sheet the mask
   list, Add mask and add / subtract / intersect). The selection options bar shows in the
   Select category.
5. **Slots outside the editor.** Home's top bar gets a More menu for HDR merge, Panorama,
   Focus stacking, Batch and Sync settings (catalog slots `home/merge/*`, `home/batch`,
   `home/sync-settings`); Batch is also in the editor's More menu as the brief asked.
   `settings/tutorials` is the existing Gestures row. RAW and .loproj come in through Open
   file. No catalog slot moved.
6. **Undo, Redo and Compare** open their placeholder panels (Stage 8); nothing pretends to
   undo. The coach mark says hold-to-compare arrives in Stage 8.
7. **Canvas.** `CanvasView` is a `GLSurfaceView` (GLES 3.0 through the Java bindings, so
   `:core:engine` stays the only JNI module); render-on-demand; `Surface.setFrameRate(120,
   FRAME_RATE_COMPATIBILITY_DEFAULT)` on surface creation. The UI thread writes the 3×3
   matrix under a lock; the GL thread copies it. No allocation per event or per frame
   (preallocated arrays, `Choreographer` frame callback for fling and the double-tap
   animation). Stage 4 replaces the renderer with the engine; view and gestures stay.
8. **Zoom is in original pixels**: 100 % = one pixel of the original (the preview is
   upscaled until Stage 4's full-resolution tiles). Range 0.1×–32× (a panorama whose fit is
   below 0.1× may zoom out to fit). Pan is limited so the middle of the view always shows
   the photo. Rotation starts after 10° of twist (a pinch never tilts the photo) and snaps
   within ±5° of 0/90/180/270° with one `CLOCK_TICK`. Double tap: fit ↔ 100 % around the
   tap, animated with `MotionTokens.Standard` (a jump under reduced motion). Fling after a
   two-finger pan, exponential decay. **One finger is left to the tools** of later stages.
9. **Preview**: ImageDecoder straight to ≤ 2048 px, software bitmap, sRGB, mipmapped
   texture. Formats that cannot be decoded yet (TIFF, RAW) show an explanation instead of a
   photo.
10. **Placeholders say "Arrives in Stage N"** in the editor (the brief's wording); Settings
    keeps `LoPlaceholderTool`'s "Coming in Stage N".
11. **Canvas surround** (Settings row reserved for Stage 3) works: dark `#121212` / mid
    grey `#777777`, used as the GL clear colour.
12. Compose UI tests use the `junit4.v2` rules (the v1 rules are deprecated in Compose 1.11).
13. Version 0.3.0 (5). Branch: session branch `ccr-a94fce54-a1j7s3`, as in earlier stages.

## Dependencies added (R10)

No new library. Already in the catalog and the app, now also used by modules:
`androidx.activity:activity-compose` 1.13.0 (BackHandler in `:feature:editor`, Apache-2.0),
Compose `ui-test-junit4` / `ui-test-manifest` (tests only, Apache-2.0).
89 Material Symbols Rounded icons (Apache-2.0), converted to vector drawables like Stage 1's.

## Golden images changed (R4)

No golden files. Screenshots changed on purpose:

- `core/designsystem/.../lo_category_bar.png` (compact, expanded): the bar used to check
  whether the selected category was visible *before its first layout*, saw nothing and
  scrolled even when the category was on screen (the "active" row started at Light,
  hiding Favorites–Auto). It now waits for the first layout, jumps only if the category is
  really off-screen (a restored category such as AI), and animates later changes.
- `feature/home/.../home_*.png` (all 10): the More button in Home's top bar (decision 5).
- `feature/settings/.../settings.png` (compact, expanded): the Canvas surround row no
  longer says "Stage 3"; it works.
- `feature/export/.../export_sheet.png`: the full export sheet layout replaces the two
  placeholder rows; `expanded/export_sheet.png` is new.
- `feature/editor`: the Stage 2 placeholders (`editor_placeholder`, landscape) are
  replaced by the 77 shell screenshots.

## Troubleshooting log (R11)

| Symptom | Root cause | Fix |
| --- | --- | --- |
| Menu screenshots written to `src/test/screenshots/src/test/screenshots/…` | `captureScreenRoboImage` paths are relative to the Roborazzi output directory | Relative paths like the other captures |
| A restored category (AI) off-screen in the category bar and rail | Visibility was checked before the first layout | Wait for the first layout (`snapshotFlow`), jump the first time, animate afterwards; screenshots captured through the compose rule so effects settle |
| Layers sheet opened at 160 dp on phones | The panel state kept the tool panel's detent | One panel state per mode (sheet tall, tool low) |
| Emulator API 34 `NavigationTest`: export sheet never opened | Export is disabled until the project has loaded; on a device that happens asynchronously, so the tap landed on a disabled button | The test waits until Export is enabled (no product change: a disabled Export before the project exists is correct) |
| `NavigationRobolectricTest`: no scroll parent for the export caption | The caption fits on screen; there was nothing to scroll | Assert it exists |
| detekt: magic numbers in ToolRegistry | Stage numbers in a data table | `@file:Suppress("MagicNumber")` with the reason; matrix indices likewise |

Plan B used: no.

## Proof

- CI run and APK artifact: see the Stage 3 report (PR and `main` run links)
- Screenshot gallery: the `screenshot-gallery` artifact of the CI run
- Emulator (API 34, SwiftShader) `CanvasFrameTimingTest`: 123 frames in a 2 s two-finger
  pan, P95 frame interval 17.8 ms, P95 GL draw 0.08 ms — correctness only; the phone
  measures performance

## Phone checklist

POCO F7 Ultra, `lineover-<sha>.apk`:

- [ ] Open a project from a **50 MP photo**: the photo appears within a second or two, fitted.
- [ ] First open: 3 coach marks (pinch, hold to compare, swipe categories). Finish or Skip;
      reopen the project: they do not come back. More → Help shows them again.
- [ ] Pinch, two-finger pan and twist: smooth, following the fingers. With Developer
      options → **Show refresh rate** (if HyperOS has it) the counter stays at 120.
- [ ] Twist near straight: the photo snaps to 0/90/180/270° with one tick.
- [ ] Double tap: 100 % around the tap; again: fit. Lift two fingers while panning: it glides.
- [ ] Swipe the categories: every one of the 18 opens its strip; tap any tool → "Arrives in
      Stage N" panel. Close the app in Retouch; reopen: Retouch is selected.
- [ ] Long-press a tool → "Added … to Favorites"; it appears under Favorites.
- [ ] Layers, History (3 tabs), Masks (category), More (all items) open; histogram /
      clipping / guides badges toggle on the canvas.
- [ ] More → Tool search: type "heal" (Spot healing, Healing brush) and "erase object"
      (AI remove); tap a result → its panel.
- [ ] Export (top right) → the export sheet layout.
- [ ] Rotate to **landscape**: categories move to a left rail and panels to a right column.
- [ ] Settings → Canvas surround → Mid grey: the area around the photo turns grey.
- [ ] Home → More: HDR merge, Panorama, Focus stacking, Batch, Sync settings, each with its stage.

## Open questions (R12)

Answered by Mark ("1. Yes 2. Yes 3. Yes"):

1. **Is anything in the layout uncomfortable for your thumb?** "Yes", with no detail
   yet. Recorded as an open point: the layout is frozen (R9) in its slots, but sizes,
   spacing and which edge a bar sits on can still be tuned before Stage 4 once Mark says
   which part. Nothing changed until then.
2. **Should Layers default to closed?** **Yes**: closed, opened from the top bar (as built).
3. **Is the right-hand panel column preferred in landscape?** **Yes**: rail on the left,
   320 dp column on the right, also on tablets and unfolded foldables (as built).
