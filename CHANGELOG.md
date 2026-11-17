# Changelog

All notable changes, newest first. One section per stage.

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
