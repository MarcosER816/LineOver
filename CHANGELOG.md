# Changelog

All notable changes, newest first. One section per stage.

## Stage 10 — Effects and detail (0.10.0)

### Added

- Detail → Sharpen: Amount, Radius (0.5–3 px), Detail and Masking. Hold a second finger on
  the Masking slider to see the mask in grey (white = sharpened, black = protected).
- Detail → Noise reduction: Luminance (with Detail and Contrast) and Color.
- Effects → Clarity (midtone local contrast), Texture (fine detail), Dehaze (removes haze;
  negative values add it).
- Effects → Vignette (after the crop: Amount, Midpoint, Roundness, Feather, Highlights) and
  Grain (Amount, Size, Roughness; stronger in the midtones).
- A 1:1 button on Sharpen, Noise reduction and Texture zooms to 100 % to judge them.
- The canvas, the zoomed-in tiles, thumbnails and the full-resolution export all show the same
  result: radii follow the resolution.

## Stage 9 follow-up — Lens profiles (0.9.1)

### Added

- Lens profiles: Settings → AI models → Lens profiles (Lensfun, 5.0 MB, CC BY-SA 3.0)
  downloads the Lensfun database on request (checked file by file; Delete removes it).
  Optics → Lens distortion → Use lens profile corrects distortion, chromatic aberration and
  vignetting for thousands of camera lenses, matched from the photo's EXIF or picked from a
  list, with amounts for distortion and vignetting. Phone cameras are not in the database.

## Stage 9 — Crop and optics (0.9.0)

### Added

- Crop: drag the 48 dp handles or move the frame; Free, Original, 1:1, 4:5, 3:2, 16:9 or a
  custom ratio (portrait ⇄ landscape); rule of thirds, golden ratio or grid shown while you
  drag; double tap the frame to reset; pinch to zoom as usual.
- Straighten: a ruler (±45°) that ticks when it reaches 0, and Auto (finds the horizon).
- Rotate 90° left / right and Flip horizontal / vertical.
- Constrain crop (on by default): the crop shrinks so no empty corners ever show.
- Perspective crop: drag four corners onto a tilted rectangle (a page, a painting).
- Upright: Auto, Level, Vertical, Full, or Guided (draw 2–4 lines on edges that should be
  straight); plus Vertical, Horizontal, Rotate, Aspect, Scale and offsets by hand.
- Lens distortion (barrel / pincushion), chromatic aberration (with Auto), lens vignetting.
- Everything is one transform, applied once, so the photo is resampled a single time
  (bicubic); full-resolution export and the Ultra HDR gain map follow it.

## Stage 8 — History and projects (0.8.0)

### Added

- Undo and redo in the top bar, and on the photo: tap with two fingers to undo, three to redo
  (Settings → Gestures can swap them or turn them off). A slider drag is one step.
- History (top bar → History): every step with its name ("Exposure +0.7"); tap one to preview
  it on the photo, Restore to go back to it (the later steps stay redoable until you edit).
- Snapshots: save the current look under a name and return to it later.
- Versions: try another look of the same photo; each version keeps its own history and the
  versions show side by side with thumbnails.
- Your edits are saved: 5 seconds after you stop and when you leave the app, written so that a
  crash or a forced stop never leaves a broken project. If LineOver was closed during an
  edit, the next start says the edits were recovered.
- Project files: Home → a project's menu → Save project file writes a `.loproj` (the edit,
  its history, snapshots, versions and the original photo); Open file opens one as a new
  project.
- Home thumbnails show the edited photo.

### Changed

- Settings → Gestures explains the canvas gestures and the undo / redo taps.

## Stage 7 — Curves and grading (0.7.0)

### Changed (after testing on real photos)

- Curves on phones: the panel is shorter so it fits with the photo visible above it. The
  channels (RGB · R · G · B · L) sit beside Close, the graph is wider than tall, the in / out
  values sit inside the graph and Free curve / Reset are chips under it.

- Color Grading: the Highlights wheel is gentler (as strong as the Shadows wheel) and leaves
  white and very bright areas neutral, so a warm highlight tint no longer turns a white sky
  peach.
- Colours the screen can't show are now brought into range keeping their hue and brightness
  (less saturated) instead of being cut per channel, which shifted strong oranges towards
  red.

### Added

- Curves (Light → Curves (point)): RGB, Red, Green, Blue and Luma curves. Tap the graph to
  add a point, drag to move it, drag it off the graph to delete it. A magnifier and the
  in / out values show while you drag, and the photo's histogram sits behind the curve. The
  curve can't fold back on itself unless you switch on "Free curve".
- Parametric curve (Highlights, Lights, Darks, Shadows with movable split points) and Levels
  (per channel black / gamma / white / output range, with Auto).
- Color Grading: Shadows, Midtones, Highlights and Global wheels (drag for hue and amount,
  the outer ring for brightness; double tap resets), Blending and Balance. Split toning is
  its two-wheel shortcut.
- Color Balance, Selective Color, Channel Mixer (with monochrome), Black & White (6 colour
  sliders and a tint), Photo Filter (Warming / Cooling / Sepia … presets), Gradient Map
  (presets and editable stops) and Calibration (red / green / blue primaries).

## Stage 6 — Color basics (0.6.0)

### Added

- White balance: Temperature (2000–50000 K, blue → amber track) and Tint (green → magenta),
  done as a proper chromatic adaptation (Bradford) rather than channel gains; "As shot"
  returns to the camera's own balance.
- White-balance eyedropper: tap the pipette, put your finger on something that should be
  grey; a magnifier above your finger shows the pixels, and lifting makes that spot neutral.
- Vibrance (boosts muted colours more and spares skin tones) and Saturation.
- HSL / Color Mixer: Hue, Saturation and Luminance for 8 colours (red, orange, yellow,
  green, aqua, blue, purple, magenta), each with its coloured dot. Computed in OKLCh so
  neighbouring colours blend smoothly and greys never get a colour cast.
- Targeted adjustment (the target icon in the mixer): drag up or down on a colour in the
  photo to change the band under your finger.
- Invert, Posterize (2–32 levels) and Threshold, in the Color strip.

## Stage 5 — Light (0.5.0)

### Added

- The first real tools, in Light: Exposure (±5 EV), Contrast, Highlights, Shadows, Whites,
  Blacks and Brightness. Everything happens in 16-bit floating point and never clips your
  HDR highlights; the original file is never changed.
- Highlights and Shadows use an edge-aware base, so skies recover and shadows lift without
  dark or bright rims around edges.
- Light panel: the selected slider at the low height, all seven when you swipe the panel up
  (or tap "All sliders"); double tap a slider to reset it; Auto for a quick stretch.
- Swipe mode (the hand icon in the Light panel): on the photo, swipe up / down to pick the
  slider and left / right to change it, with its value shown in the middle.
- Histogram (More → Histogram): live R, G, B and luminance of your edit.
- Clipping warnings: More → Clipping warnings, or put a second finger down while dragging a
  slider. Blown highlights show pink-red, crushed shadows blue.
- Before / after: hold the photo to see the original ("Before"); the Compare button at the
  top switches between a draggable split and side by side.

### Notes

- Edits live while the editor is open; saving them in the project arrives with History and
  projects (Stage 8).

## Stage 4 — Image engine (0.4.0)

### Added

- The image engine every tool will plug into: your photo is now drawn by LineOver's own
  engine (OpenGL on a separate thread, 16-bit floating point, linear Rec.2020 colour).
- Huge photos open without running out of memory: a low-resolution version appears at
  once, and when you zoom in the sharp parts load piece by piece (a small spinner shows
  only if that takes longer than 0.3 s).
- Colours stay true: wide-gamut (Display P3) photos are shown on the POCO's P3 screen
  without clipping; EXIF rotation is respected.
- Ultra HDR photos keep their HDR information (gain map) for HDR display and export later.
- More → Info shows the photo's size, colour space and whether it is Ultra HDR.

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
