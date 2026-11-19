# Architecture

## Principles

1. **Non-destructive.** The original file is never modified (R7). An edit is data — an
   *edit graph* — that the engine applies to the source image.
2. **The UI thread only draws UI.** Pixels are processed on the engine's threads and the
   GPU. The UI thread budget is 8.3 ms per frame (120 Hz) (R6).
3. **One source of truth per screen.** MVI-style state, one ViewModel per screen.
4. **Everything on the device.** No cloud processing; AI models are downloaded on first
   use, never bundled (D3).

## Modules (fixed forever)

```
:app                    Activity, navigation host, dependency wiring
:feature:home           Home: projects, import, merge, batch
:feature:editor         Editor shell, canvas, tools, panels
:feature:export         Export sheet and export jobs
:feature:settings       Settings, model management
:core:designsystem      LineOverTheme, tokens, Lo* components
:core:ui                Shared UI infrastructure (MviViewModel, …)
:core:model             Edit graph and ops (kotlinx.serialization), tool categories
:core:engine            Kotlin API of the engine + JNI declarations (LoEngine)
:core:engine-native     C++17 engine (CMake): Android .so, host library, ctest tests
:core:io                Files: import, .loproj, export destinations
:core:ml                Model download / verification / runtime (LiteRT)
:benchmark              Macrobenchmarks (phone only)
:baselineprofile        Baseline profile generator
```

Dependency rules:

- `:app` → features → core. A feature never depends on another feature.
- `:core:model` depends on nothing but kotlinx.serialization. It has no Android UI types.
- `:core:engine` is the only module that talks JNI; `:core:engine-native` has no Kotlin.
- `:core:designsystem` depends on Compose only; `:core:ui` builds on it.
- Modules apply only `lineover.*` convention plugins (`build-logic/convention`).

## State: MVI, one ViewModel per screen

`MviViewModel<State, Intent, Effect>` (`:core:ui`):

- **State** — one immutable data class that fully describes the screen; exposed as a
  `StateFlow` and rendered by a stateless composable.
- **Intent** — everything the user or system can ask for; the only way in (`onIntent`).
- **Effect** — one-shot events (navigation, snackbar), delivered once and never replayed.

While a slider or a gesture is being dragged, the value goes straight to the engine
preview and is never animated; the committed value becomes a new edit graph on release.

## Navigation and app wiring

- **Navigation 3** (`:app`, `navigation/`): type-safe `@Serializable` routes (`HomeRoute`,
  `EditorRoute(projectId)`, `ExportRoute(projectId)`, `SettingsRoute`,
  `SettingsSectionRoute(id)`, `GalleryRoute` in debug builds). The back stack is a saveable
  list; `NavDisplay` handles Back and the predictive back gesture (preview animation;
  `enableOnBackInvokedCallback` is on). Each entry has its own `ViewModelStore`. The export
  sheet is a `DialogSceneStrategy` entry drawn as a bottom sheet over the editor.
- **No DI framework**: `LineOverApp.container` (`AppContainer`) is created lazily on first
  use; `Application.onCreate` does nothing, for a fast cold start. Feature routes receive
  what they need as parameters and create their ViewModel with `viewModel { … }`.
- **SplashScreen API**: `Theme.LineOver.Starting` (icon on `bg`) until the first frame.
- **Edge-to-edge**: every screen pads itself with `WindowInsets.safeDrawing` (or parts of
  it); bars that sit over scrolling content are opaque.
- **Adaptive** (D4): `rememberLoWindowInfo()` (`:core:ui`) gives the Material width class
  (600 / 840 dp breakpoints from `androidx.window`). Grids use adaptive cells; Settings
  shows list and detail side by side on expanded windows.

## Projects (`:core:io`)

- `ProjectLibrary` is the only API screens use: import (photo picker or SAF), rename,
  duplicate, delete / restore / purge, mark opened.
- **Room** database `projects.db`, table `projects` (id, name, source_uri, thumb_path,
  modified, opened). Schemas are exported to `core/io/schemas` and committed; every
  version bump needs a migration and a test in `ProjectDatabaseMigrationTest` (never a
  destructive fallback).
- **Sources**: a persistable read permission is taken for picker and SAF URIs; if that is
  refused, the original is copied to `files/projects/<id>/original`. Originals are only
  ever read (R7). Persisted permissions are released when the last project using them is
  purged (Android limits them per app).
- **Thumbnails**: `ImageDecoder.setTargetSize` decodes straight to ≤ 512 px (never a
  full-size bitmap), JPEG to `files/projects/<id>/thumb.jpg`, on `Dispatchers.IO`.
- **Delete with undo**: the row is removed at once and the files stay for 5 s; Undo
  re-inserts the row, otherwise the files are purged on the app scope.
- **Project files (Stage 8)**: `projectfile/` writes and reads `.loproj` ZIPs: `manifest.json`
  (format, `schemaVersion`, app version, name, times, `SourceRef`, SHA-256 of every entry;
  written last, so a cut-off file has none), `graph.json` (the active version's graph),
  `document.json` (`ProjectDocument`: versions with their histories, snapshots), `original`
  (shared files only, stored) or a reference, `paint/` (zstd tiles from Stage 18) and
  `thumbnails/`. `ProjectMigrations` upgrades older schemas step by step before decoding and
  refuses newer ones; schema 1 needs no step yet.
- Each project autosaves to `files/projects/<id>/project.loproj` through `AtomicFiles`
  (temp file, fsync, old file → `.bak`, rename). The editor writes an `editing` marker while
  open; `recoverInterrupted()` (Home, on launch) finds markers left by a crash or a kill and
  puts the newest intact file back (main, else a complete temp, else the backup). A file that
  cannot be read (a newer format) is never overwritten. The edited thumbnail is saved as
  `thumb-<time>.jpg` (Home caches by path).

- **Device albums** (`gallery/DeviceGallery`): `MediaStoreGallery` queries
  `MediaStore.Images` (id, bucket, name, date) off the main thread and re-emits on a
  `ContentObserver`; `GalleryGrouping` turns rows into albums (newest first, blank bucket →
  "Other"). Without `READ_MEDIA_IMAGES` / `READ_MEDIA_VISUAL_USER_SELECTED` it returns an
  empty list; Home only collects it when access is granted. Thumbnails come from
  `ContentResolver.loadThumbnail`. A photo opened from an album is imported through
  `ProjectLibrary` (copy fallback, original untouched).

## Editor shell (`:feature:editor`) — layout FROZEN after Stage 3

**The editor layout is frozen (R9).** Later stages only fill the slots listed in
`TOOL_CATALOG.md`; they never move a slot or restructure the shell.

- **One source of truth for tools:** `ToolRegistry` (`:core:ui`, `tools/`) lists all 162
  tools (id, name, icon, category, place, slot, stage, panel type, synonyms, done).
  `ToolCatalogTest` checks it against `TOOL_CATALOG.md`; the strips, menus, sheets, tool
  search and Home → More all read it. Reachability tests prove every slot is ≤ 2 taps away.
- **Phone** (compact, medium portrait): top bar 56 dp · canvas · selection options (Select)
  · tool strip 56 dp · category bar 64 dp. The panel slot (a tool's panel or a sheet)
  overlays the bottom of the canvas, ≤ 40 % of the canvas height, so the GL surface never
  resizes while a panel moves.
- **Wide** (expanded width, or medium in landscape): category rail 88 dp · (top bar, canvas,
  tool strip) · 320 dp right column for panels and sheets (the category's tool list when
  nothing is open).
- **Panel slot** (`EditorPanel`): Tool(id), Layers, History, Info; the Masks category's
  panel is the Masks sheet. Menus: More, Add layer, Add mask. Tool search is a full-screen
  layer of the editor. `EditorViewModel` (MVI) keeps all of it; `AppPreferences` stores the
  last category, favourites, coach marks and the canvas surround.
- **Canvas** (`canvas/`): `CanvasView` is a `GLSurfaceView` rendering on demand with
  `CanvasRenderer` (one textured quad, GLES 3.0) and asking for 120 Hz
  (`Surface.setFrameRate`). Gestures are handled in `onTouchEvent`, never in Compose:
  `TwoFingerTracker` → `CanvasTransform` (similarity transform in original-image pixels;
  0.1×–32×, pan limit, right-angle snap) → a 3×3 matrix handed to the GL thread under a
  lock. Fling (`FlingDecay`) and the double-tap animation run on a `Choreographer` callback.
  Nothing allocates per event or per frame. The preview is decoded at ≤ 2048 px
  (`ProjectLibrary.decodePreview`). Stage 4 replaces the renderer with the engine; the
  view, gestures and transform stay. A Compose overlay above the surface is reserved for
  on-canvas handles (hidden until a tool needs it).

## Edit graph

Defined in `:core:model`, serialised with kotlinx.serialization.

- `EditGraph(schemaVersion, layers)` — layers bottom to top.
- `Layer(id, ops)` — an **immutable list of serializable `Op`s per layer**, applied in
  order to the layer source.
- `Op` — a sealed interface; each op has a stable `@SerialName` that is never renamed or
  reused, so old `.loproj` files keep loading. Unknown fields are ignored on read.

Every edit produces a new graph value. The **undo stack stores graph snapshots, never
pixels** (`EditHistory`, Stage 8): at most 500 entries, each sharing unchanged layers and op
instances with the one before (`EditGraph.sharing`). A drag of one control is one entry: the
open top entry absorbs changes with the same key (`HistoryLabels`: "exposure.ev") until the
control is released (`EditorIntent.EndEdit`), another control changes, or the autosave fires.
`HistoryLabels` also names entries ("Exposure +0.7"). Versions each own an `EditHistory`;
snapshots are named graphs; together they are the `ProjectDocument`. Undo / redo, restore and
version switches read the sliders back from the graph (`fromOps`), so the graph is the only
truth. Thumbnails of graphs come from the engine's CPU path on a ≤ 512 px copy of the preview
(`renderThumbnail`, any thread). The single exception is painting (brush, healing, clone …): a stroke is stored
as a **paint delta of compressed tiles** that the graph references.

A `.loproj` project file holds the graph JSON, a reference to the original, and the paint
deltas. Exports go to `Pictures/LineOver`.

## Engine (Stage 4)

Native **C++17, OpenGL ES 3**, in `:core:engine-native`; Kotlin reaches it through
`NativeEngine` / `EngineSession` in `:core:engine`. One engine per open image.

| Aspect | Decision |
| --- | --- |
| Working space | Linear Rec.2020, D65, straight alpha; values above 1.0 allowed (HDR headroom) |
| Sources | `SourceReader` (`:core:io`): ImageDecoder header + EXIF (androidx.exifinterface). 8-bit sRGB, 8-bit Display P3, or RGBA_F16 LINEAR_EXTENDED_SRGB for other wide-gamut / HDR sources. Ultra HDR: `bitmap.gainmap` is kept |
| Preview | ImageDecoder straight to ≤ 2048 px (SOFTWARE allocator; HARDWARE only for UI thumbnails). Shown instantly |
| Tiles | 512 × 512, mip level L = BitmapRegionDecoder inSampleSize 2^L, oriented natively (8 EXIF cases). Only the tiles in view, at the level matching the zoom, nearest the centre first |
| CPU tile store | Float RGBA mirrors under a budget (LRU); evicted tiles spill to `cacheDir/engine/<id>` as zstd-compressed half floats and reload on demand |
| GPU | Dedicated render thread with its own EGL context. RGBA16F textures (preview + tiles, GPU LRU under a budget); `SRGB8_ALPHA8` fallback when RGBA16F is not renderable (emulators) |
| Ops | `lo::Op`: `gpu()` (GLSL body + uniforms, one shader pass each) and `cpuRef()` (plain C, float, NEON-free: the golden reference); `cpuRefRegion()` for ops that read image-wide data (the tile's rect in normalised image coordinates + guided coefficients). Identity, ExposureTest and the Light ops (Stage 5) |
| Display | Rec.2020 → Display P3 (EGL `display_p3_passthrough`, or `display_p3` with GL encoding) or sRGB; premultiplied blend over the surround. The editor window asks for `COLOR_MODE_WIDE_COLOR_GAMUT` on P3 displays |
| Edit graph | Kotlin `EditGraphJson` → native `parseGraph` (same JSON, round-trip checked on both sides with `testdata/graph/edit_graph_v1.json`) |
| Scheduling | `RenderScheduler`: latest graph wins (conflated), preview renders on every change on `Dispatchers.Default` + the render thread; full resolution (`EngineSession.renderFullResolution`, CPU path) only on export |
| Progressive display | Preview first, sharp tiles drawn over it as they arrive; the editor shows a subtle spinner only if they take > 300 ms |
| Memory | Defaults: 192 MB CPU tiles + 192 MB GPU tiles. 50 MP stays far below 1.2 GB native + GPU (`EngineOpenTest` logs it, tag `LineOverMemory`) |
| Deprecated APIs | None. RenderScript is forbidden (R8) |

### Light tools (Stage 5)

- Formulas live in `light.h` / `light.cpp`, `tools/golden/reference.py` and the GLSL bodies,
  always changed together. All work on Rec.2020 luminance Y and scale RGB by Y′/Y (chroma
  kept); nothing is clipped. Kotlin builds them in a **fixed order** from
  `LightAdjustments` (`:core:model`): Highlights/Shadows → Exposure → Whites → Blacks →
  Contrast → Brightness; a slider at 0 adds no op.
- Highlights / Shadows: self-guided filter of L = log2 Y (radius 1.5 % of the long edge,
  eps 0.01) computed **once per image on the preview** (`setPreview`, background thread);
  coefficients (A, B) go to an RG32F texture. Every pass samples them bilinearly at its
  pixel's image uv (`lo_guided()` in GLSL = `sampleGuided()` in C++), which is the fast
  guided filter's upsampling: sharp tiles and export get the same edge-aware base.
  Running first, its input is always the source the coefficients describe.
- Histogram: GLES 3.1 compute shader on the edited preview, 256 bins × R, G, B (sRGB display
  values) + L; dispatched at most every 33 ms while shown, read back after a fence (the
  render thread never waits). GLES 3.0 contexts read the preview back asynchronously (pixel pack buffer) and bin it with `lo::histogramSrgb`, the shader's C++ reference.
- Clipping, compare (original / split / side by side) are display-pass options; the
  original is the source texture, no extra pass.
- Preview update latency (setGraph → frame presented) is measured on the render thread:
  `EngineStats.p95UpdateUs`, logcat every 30 updates, debug overlay More → Render stats.
- Auto: 0.5 % / 99.5 % luminance percentiles of the preview → Exposure (high to 1.0), then
  Blacks (low to 0.002).

### Color tools (Stage 6)

- `color_ops.h/.cpp`, `reference.py` and the GLSL bodies change together. Full order of the
  background ops (`editOps` in `:core:model`): **White balance** → Light (Highlights/Shadows
  … Brightness) → Vibrance → Saturation → HSL → Invert → Posterize → Threshold.
- White balance: a 3 x 3 working-space matrix, Bradford CAT from the (T, tint) white to D65.
  The white is D65 plus the Planckian-locus offset (Krystek 1985) of T from 6504 K, plus
  tint x 0.05/150 duv along the locus normal; 6504 K / tint 0 is exact identity.
  Highlights/Shadows' coefficients still come from the source preview: after a white
  balance change the base is computed on the unbalanced luminance (small, documented).
- Vibrance, Saturation, HSL in OKLCh (Rec.2020 → XYZ → OKLab, sign-preserving cube root);
  HSL bands are raised cosines between the OKLCh hues of the classic HSV band centres
  (a partition of unity) and every hue effect fades in from grey over chroma 0..0.03.
- Invert / Posterize / Threshold on transfer-encoded values (sRGB curve extended by symmetry).
- Nothing clips: out-of-gamut and HDR values pass through until the display transform.
- The display transform gamut-maps: a colour outside the display's gamut keeps its OKLab
  lightness (clamped to 0..1, so HDR highlights go to white) and hue, and its chroma is
  bisected (16 steps) along the OKLab line to the grey RGB = L³ until it fits (tolerance
  5e-4). Same steps in `color::gamutMap` (CPU), the GPU display pass, `reference.gamut_map`
  and `DisplayGamut` (UI swatches). The histogram and the clipping warnings still read the
  unmapped values: they report the image data, not what the screen can show.
- Graph JSON: number arrays (the mixer's bands) are op fields written after the scalars.
- Eyedropper / targeted adjustment: `Engine::samplePatch` averages 5 x 5 preview pixels at an
  image point and runs the ops that come before the tool (on the render thread, called off
  the main thread); `colorops::solveNeutral` and `targetedWeights` turn it into (T, tint) or
  band weights. The loupe reads 9 x 9 original preview pixels.

### Curves and grading (Stage 7)

- `grade_ops.h/.cpp`, `reference.py` and the GLSL change together. Full background op order
  (`editOps`): Calibration → White balance → Light → Levels → Curves → Parametric curve →
  Vibrance → Saturation → HSL → Color Balance → Selective Color → Channel Mixer → Color
  Grading → Photo Filter → Black & White → Gradient Map → Invert → Posterize → Threshold.
- Curves: Fritsch-Carlson monotone cubics (C++ `grade::curveLut`, Kotlin `MonotoneCurve` for
  drawing, Python `spline_eval`, all the same sequential tangent limiting) sampled into
  4096-entry LUTs; ops hand LUTs to the backend with `GpuOpContext::setLut` (RGBA32F texture
  at unit 2, cached by content hash) and read them with `lo_lut1` / `lo_lut3` (linear between
  entries, slope 1 outside 0..1). The parametric curve is 9 points of the same spline.
- Tone ops work on transfer-encoded values; Color Grading is CDL there (shadows = offset,
  midtones = power, highlights = slope, global = offset) with tone weights from encoded
  luminance set by Balance / Blending. The highlights tint (slope 0.2 per 100 % saturation,
  matched to the shadows' offset) fades out over encoded luminance 0.9 → 1.05 so that white
  and HDR highlights stay neutral. Wheel hues are OKLCh hues; the UI shows HSV hues and
  converts with `HueMap` (piecewise linear through the band centres).
- GLSL indexes uniform arrays with constants only (generated unrolled code), after
  SwiftShader mishandled dynamic indexing in Stage 6.

### Crop and optics (Stage 9)

- **One geometry op, one resample.** Crop, straighten, quarter turns, flips, Upright, the
  manual transform and perspective crop are composed in Kotlin (`GeometryMath.compose`,
  `CropMath`, `Mat3` / `Homography` DLT) into ONE 3 x 3 matrix: output normalised (u, v) ->
  source pixels (edges at 0 and width). The op (`OpParams.Geometry`, JSON `geometry`) also
  carries Brown-Conrady k1 / k2 (r normalised by the half diagonal), a radial scale per
  channel for chromatic aberration, lens vignetting and the panel values (`settings`, which
  the native parser ignores). It is first in `editOps()`.
- **Ops stay in source space.** The colour ops run on the source preview and source tiles as
  before (the lens vignetting correction is a source-space op placed first); the geometry is
  applied where pixels are sampled: the display pass maps every screen pixel output ->
  source and samples once with OpenCV's bicubic (Keys a = -0.75, exact float positions,
  border 0); `geometry.h` `warp()` is the same on the CPU (thumbnails, export, gain map).
- **Tiles under a geometry**: the visible output rect's source footprint picks the source
  tiles (level from the local output scale); each tile is drawn over the output rect its
  source rect maps to (`outputRectOf`) and discards fragments outside its own rect.
  Export renders 512 px *output* tiles (`renderOutputTile`: ops on the footprint, then the
  warp; large footprints are split). The histogram reads the preview warped to the output.
- **Analysis** (`lines.h`, on a <= 1024 px copy of the preview): LSD-style line segments for
  Upright (vanishing points by least squares, camera rotation K·R·K⁻¹, limited to ±25° with a
  fallback to Level), Canny + Hough for the horizon (auto straighten), and a radial
  edge-alignment search for chromatic aberration.
- **Editor**: while a crop tool is open the canvas draws the whole frame (`canvasGraph`) and
  the overlay draws the crop; the canvas image size is the output size. Crop gestures live in
  `CanvasView` (`CropGestures`); `CropOverlayLayer` only draws, reading the transform in the
  draw phase.
- Ultra HDR: `EngineSession.gainmapFor(graph)` warps the gain map with the same geometry.
- **Lens profiles** (0.9.1): `:core:io` `LensProfileStore` downloads the Lensfun database on
  request (pinned files, SHA-256, atomic install); `:core:model` `LensDatabase` (its own small
  XML reader), `LensMatcher` (EXIF camera / lens -> database entry) and `LensProfiles`
  (exact conversion to the engine's lens model, which follows Lensfun's: odd distortion
  terms, TCA on the distorted radius, "pa" vignetting). The profile is part of the geometry
  op (`lensDistortion`, `lensTcaRed` / `lensTcaBlue`, `vignetteProfile`).

### Detail and effects (Stage 10)

- **Ops** (`detail.h/.cpp`; formulas `tools/golden/detail_reference.py`, Dehaze on
  `dehaze_reference.py`, He et al. 2009 with guided-filter refinement): Noise reduction
  (a-trous B3 wavelet soft threshold on Y', 4 levels; chroma bilateral in Y'CbCr), Clarity and
  Texture (log-luminance detail against the guided-filter base, radius 2 % / 0.3 %), Dehaze
  (dark channel prior; negative adds haze) run right after the geometry's vignetting, on the
  image as decoded; Sharpen (unsharp mask on encoded luminance, Sobel masking) runs last in
  source space. Post-crop Vignette and Grain are **output-space finishing**
  (`Finishing`, `kFinishingGlsl`): the display and warp programs apply them after sampling,
  the CPU export after the warp, thumbnails after their warp.
- **Scale.** `OpRegion::scale` / `u_scale` = buffer pixels per full-resolution pixel; every
  radius is in full-resolution pixels and multiplied by it, so the preview matches the
  downsampled export (ctest `preview_matches_the_downsampled_export`: PSNR 40.9 dB).
- **Fields once per preview** (`computeDetailFields`, in `Engine::setPreview` beside the
  Highlights / Shadows coefficients): Clarity / Texture guided coefficients and the haze model
  (airlight, refined dark channel coefficients). They do not depend on the sliders, so a
  slider move only reruns the passes (the brief's "compute base layers once" rule).
- **The original.** Clarity, Texture and Dehaze read the image as it entered the first of them
  (`Op::readsOriginal`; CPU `OpRegion::original`, GPU `lo_original()`).
- **Multi-pass ops.** `GpuOpContext::addPass` (Sharpen: 3 passes, Noise reduction: up to 6):
  each pass reads `u_source` (the op's input), `u_prev`, `u_pass0` and `u_original` with
  `lo_at` / `lo_bilinear`; `runOps` picks scratch slots nothing still reads.
- **Tile aprons.** `Op::apron()` (full-resolution pixels, summed over the list): a sharp tile's
  texture carries its neighbours' pixels that far (clipped to the image; a missing neighbour
  is clamped and the tile rebuilt when it arrives) and draws only its own rect (`texRect` /
  `ownRect`). Exports grow the footprint by the apron and go through the identity geometry
  when there is no geometry but spatial or finishing ops.
- **Editor**: `DetailAdjustments` / `DetailParam` (`:core:model`); `DetailPanel`; a second
  finger on Sharpen's Masking puts `showMask` in the canvas graph only; the 1:1 chip zooms the
  canvas to 100 % at the middle (`CanvasTransform.oneToOneTarget`).

### Presets and Auto (Stage 11)

- **3D LUTs** (`lut.h/.cpp`; reference `tools/golden/lut_reference.py`): the op works on
  sRGB-encoded Rec.709 values (what .cube files are made for), tetrahedral interpolation (the
  six tetrahedra, the same comparisons in C++, GLSL and numpy), outside the domain a slope-1
  continuation in Rec.709 luma (HDR highlights pass, out-of-gamut chroma is mapped), Amount
  mixes in encoded space. On the GPU the table is an RGBA32F 3D texture (unit 8, `texelFetch`,
  cached by content hash). GLES 3.0 guarantees 256^3 3D textures, so the 2D-atlas fallback of
  the brief was not needed.
- **LUT data never travels in the graph**: Kotlin parses the .cube (`CubeParser`), stores the
  file under its SHA-256 (`LooksLibrary`, `files/looks/luts`) and registers the table with the
  engine (`lo_register_lut`, process-wide) before a graph names it (`EditorLooksActions`);
  the graph's `lut` op carries the id and a name. Graph JSON gained string fields.
- **Profiles** (Neutral, Vivid, Mono, Flat) are 33^3 LUTs the engine makes from OKLab
  formulas (`profileLut`); the `profile` op runs after white balance, the `lut` op after the
  grading.
- **Presets** are partial edit graphs (`Preset`): applying one replaces the ops it names.
  `PresetMath` blends from the photo's own values (or neutral) towards the preset by Amount
  (0–200 %); wheel hues, split points and LUT ids are the preset's. A preset and its Amount
  are one History step (`recordEdit` stepKey). 20 built-ins (`BuiltInPresets`) are LineOver's
  own combinations; user presets, favourites and copied settings live in `LooksLibrary`.
- **Copy / paste**: `SettingsGroup` puts every op in one group; paste replaces the checked
  groups (crop and optics are rebuilt for the target photo's size).
- **Auto** (`auto_adjust.cpp`) on the analysis proxy: gray-world white balance (mireds x tint
  grid + refinement on OKLab chroma), percentile exposure / contrast / whites / blacks, mild
  vibrance fading out on colourful photos.
- **Strips** render thumbnails from a 256 px proxy of the preview on the render dispatcher
  (`smallThumbnail`); holding one sets `compareGraph`, which the canvas shows instead of the
  edit until release.

`core/engine-native/src/main/cpp`:

```
include/lineover/   public C++ API (portable): color, ops, light, color_ops, grade_ops, graph, tiling, tile_store, geometry, lines, thumbnail, detail, lut, auto_adjust
src/                portable engine core                         -> lo_engine (static)
third_party/zstd    zstd 1.5.7 sources (BSD), spilled tiles      -> lo_zstd (static)
android/            render thread, EGL, GL pipeline, C API (lo_api.h), JNI -> liblineover_engine.so
testing/            GoldenAssert, PNG / NPY readers (host only)
tests/              ctest executables (host only)
```

The JNI bridge only marshals Java values to the C API (`android/lo_api.h`). The same
`CMakeLists.txt` builds the Android shared library (through Gradle) and, on Linux, the
host static library plus the ctest tests.

## AI models

`ModelSpec` (`:core:ml`) describes a model: id, name, URL, size, licence, SHA-256. A tool
that needs a model shows its size and licence, downloads it on first use, verifies the
SHA-256 (`Sha256`) before loading, and Settings can delete it. The runtime is LiteRT.

## Testing

| Layer | Tool | Where |
| --- | --- | --- |
| Reference results | Python (NumPy, OpenCV, colour-science) | `tools/golden` → `testdata/golden` |
| Real photos | Mark's JPEGs in `testdata/photos`, decoded by OpenCV as Display P3; 32 × 32 block means | `photo_test` (ctest), `RealPhotoTest` (device: open, full-resolution decode + edits, GPU) |
| Engine, CPU, host | ctest + `GoldenAssert` (C++) | `core/engine-native/src/main/cpp/tests` |
| Engine on device (JNI, GL) | Instrumented tests + `GoldenAssert` (Kotlin) | `core/engine/src/androidTest` |
| Kotlin logic | JUnit 4 | `src/test` of each module |
| UI | Roborazzi screenshots (Robolectric native graphics) | `src/test`, images in `src/test/screenshots` |
| Slot reachability, recomposition | Compose UI tests on Robolectric | `*ReachabilityTest`, `CanvasRecompositionTest` |
| Canvas frame timing | Instrumented (correctness on SwiftShader, timing logged) | `feature/editor/src/androidTest` |
| Engine on device: GPU vs reference, 50 MP memory, Ultra HDR, full res | Instrumented | `core/engine/src/androidTest` |
| Performance | Macrobenchmark on the phone | `:benchmark` |

`GoldenAssert` tolerances: 8-bit max |diff| ≤ 2/255 and mean ≤ 0.5/255; float ops max
1e-3; GPU vs CPU reference max 3/255. A failed comparison writes a diff heatmap PNG that
CI uploads. Kotlin `GoldenAssert` is a test fixture of `:core:engine`
(`testImplementation(testFixtures(project(":core:engine")))`).

## CI

`.github/workflows/ci.yml`: `lint` (detekt, ktlint, Android lint) · `unit`
(`testDebugUnitTest`, `verifyRoborazziDebug`) · `native` (CMake host build, ctest, golden
freshness) · `build` (debug + release APK, artifact `lineover-<sha>`) · `emulator`
(API 34 and 36, SwiftShader, `connectedDebugAndroidTest`).
`release.yml`: a tag `v*` publishes a GitHub Release with the signed APK.
