# Changelog

All notable changes, newest first. One section per stage.

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
