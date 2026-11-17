# Changelog

All notable changes, newest first. One section per stage.

## Stage 3 — Editor shell (0.3.0)

### Added

- The full editor layout, now final: top bar (Back, Undo, Redo, Compare, History, Layers,
  More, Export), the photo canvas, a tool strip and the 18 categories. Every one of the 162
  planned tools has its place and says which stage brings it.
- Photo canvas: pinch to zoom (10 % to 3200 %), two fingers to move and turn the photo (it
  snaps straight with a tick), double tap for fit / 100 %, a glide when you let go. It asks
  the screen for 120 Hz.
- Layers, History (history, snapshots, versions) and Masks sheets; the selection options bar;
  the export sheet with all its sections.
- More menu: copy / paste settings, batch, histogram, clipping warnings, guides, info,
  tool search, favourites, help.
- Tool search over every tool, including what you want to do ("heal", "erase object").
- Long-press any tool to pin it to Favorites.
- First-open tips (pinch, compare, categories), shown once; More → Help shows them again.
- The last category you used is selected when you come back.
- Tablets, unfolded foldables and landscape: categories in a rail on the left, panels in a
  column on the right.
- Settings → Canvas surround: dark or mid grey around the photo.
- On narrow screens (large display size, split screen) Back, More and Export always stay
  visible; the other top-bar buttons scroll.
- Home → More: HDR merge, panorama, focus stacking, batch and sync settings, with their stages.

## Stage 2 follow-up — Device albums (0.2.1)

### Added

- Home → **Albums** tab: your phone's photo albums (Camera, Screenshots, …), newest first,
  with cover and photo count. Tap an album to see its photos; tap a photo to open it in
  the editor as a new project.
- The photo permission is asked only when you tap **Allow access** on the Albums tab.
  Android's "Select photos" (partial access) works: a banner shows that only the chosen
  photos are visible, with **Change** to pick more. Import and Open file still need no
  permission.

### Changed

- Home's bottom bar on phones: the Recent / Projects / Albums switch has its own row above
  Open file and Import.

### Fixed

- The Stage 1 heading in this changelog was lost in the Stage 2 commit; restored.

## Stage 2 — App shell, home and settings (0.2.0)

### Added

- Navigation 3: Home → Editor → Export sheet, Settings; predictive back with its preview
  animation; transitions turn off under reduced motion.
- Home: Recent and Projects tabs, a large Import button (system photo picker, up to 50
  photos, no storage permission), Open file (RAW, TIFF, .loproj via the document picker),
  project grid with 512 px thumbnails, long press for rename / duplicate / delete with Undo.
- Projects are stored in a Room database and survive restarts; originals are never changed.
- Settings: reserved rows labelled with the stage that implements them; AI models slot
  (download manager); About with the open-source licences; Debug → component gallery
  (debug builds).
- Editor and Export placeholders.
- SplashScreen; adaptive layouts for phone, foldable, tablet and landscape.

### Changed

- The component gallery moved from the floating debug button to Settings → Debug.

## Stage 1 — Design system (0.1.0)

### Added

- `LineOverTheme` completed: Material 3 dark colour scheme on the tokens, Roboto Flex type,
  shapes 4/8/12/16 dp, `MotionTokens`, live reduced-motion detection.
- Components: `LoSlider` (fine mode, tap steps, double-tap reset, haptic detent, gradient
  tracks, second-finger signal), `LoToolButton`, `LoCategoryBar`, `LoPanel`,
  `LoIconButton`, `LoChip`, `LoSegmented`, `LoSwitchRow`, `LoColorWheel`,
  `LoCurveEditorPlaceholder`, `LoToast` with undo, `LoEmptyState`, `LoPlaceholderTool`.
- Haptics (`CLOCK_TICK`, `CONFIRM`) with a vibrator fallback.
- 33 Material Symbols Rounded icons as vector drawables.
- Debug builds: a debug menu (bug icon, top right) opening the component gallery.

### Changed

- `LoMotion` is now `MotionTokens`.
- CI downloads: `lineover-<sha>.apk` (the signed app) and `lineover-<sha>-debug.apk` are
  separate, unzipped artifacts instead of one zip holding both.

 — Scaffolding (0.0.1)

### Added

- Project skeleton: 14 Gradle modules, version catalog, `lineover.*` convention plugins.
- `LineOverTheme` and the design tokens (colour, spacing, type with Roboto Flex, motion).
- Minimal app: shows "LineOver" on the `#0E0E10` background.
- Engine skeleton: C++17 core with the identity op, JNI bridge (`LoEngine`), GL smoke
  diagnostic; host build with ctest.
- Edit graph model (`EditGraph`, `Layer`, `Op`) with JSON serialisation.
- Golden-image infrastructure: Python reference generator, `GoldenAssert` for C++ and
  Kotlin with diff heatmaps.
- Roborazzi screenshot testing; first screenshot locks the design tokens.
- CI: lint, unit, native, build (debug + release APK), emulator (API 34 and 36).
  Release workflow for `v*` tags.
- Docs: AGENTS, ROADMAP, ARCHITECTURE, DESIGN_SYSTEM, TOOL_CATALOG (162 tools reserved).
