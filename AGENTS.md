# AGENTS.md — LineOver

LineOver is a personal, native Android image editor (Photoshop + Lightroom class) built
stage by stage. Owner: Mark (expert in C and Python; works from a phone, has no Android
Studio). Test device: POCO F7 Ultra — Snapdragon 8 Elite, Adreno 830, 1440×3200 120 Hz
AMOLED, HyperOS on Android 16 (API 36). Everything runs on the device; no paid cloud APIs.

Because Mark cannot build locally, **CI is the build machine**: every change is proven by
a green CI run and an APK artifact he installs on the phone.

## Permanent rules

**R0 — Final decisions. Never ask about these again.**

- **D1** App name `LineOver`. Package `dev.mark.lineover`. Theme `LineOverTheme`.
  Component prefix `Lo` (`LoSlider`, `LoPanel`, …). Project file extension `.loproj`.
  Export folder `Pictures/LineOver`. APK artifacts `lineover-<sha>`.
  (Since Stage 1: `lineover-<sha>.apk` = signed release, the one Mark installs; the debug
  build is a separate `lineover-<sha>-debug.apk`. Both are uploaded unzipped.)
- **D2** `minSdk 34`, `compileSdk 36`, `targetSdk 36`. The test phone runs Android 16.
- **D3** AI models are never bundled in the APK. Each model downloads on first use of its
  tool, with size and licence shown, a SHA-256 check, and a delete option in Settings.
- **D4** Tablet, foldable and landscape layouts are in scope from Stage 2 onward.

**R1** Never stop until the stage's Definition of Done is fully met.

**R2** Read `AGENTS.md`, `docs/ROADMAP.md`, `docs/ARCHITECTURE.md`, `docs/DESIGN_SYSTEM.md`,
`docs/TOOL_CATALOG.md` and the previous `docs/stages/STAGE_XX.md` notes before coding.

**R3** Branch `stage-XX-name`, conventional commits, open a PR, merge to `main` only when
CI is green; never force-push `main`.

**R4** Never weaken, skip or delete tests to make them pass; never change golden images
without explaining why in the stage notes.

**R5** No TODO stubs or fake implementations reported as done.

**R6** Never process full-resolution pixels on the main thread; the UI thread budget is
8.3 ms per frame.

**R7** Editing stays non-destructive; the original file is never modified.

**R8** Never use RenderScript or other deprecated APIs; never commit secrets or keystores.

**R9** Don't move reserved UI slots or restructure the shell; only fill slots.

**R10** Every new dependency is justified in the stage notes with its licence (permissive
preferred; LGPL only as a dynamic library; flag GPL / non-commercial and refuse them).

**R11** Troubleshooting ladder: read the full log, fix the root cause, check version
compatibility, isolate in a minimal test; after 3 failed attempts switch to the documented
plan B; after plan B fails, revert to the last green commit and ask Mark with a precise
report (what failed, logs, options).

**R12** Questions: ask before starting only if blocking; otherwise use the stated default
and list the questions at the end.

**R13** Stage handoff: update ROADMAP, TOOL_CATALOG, `STAGE_XX.md`, CHANGELOG; report the
CI run link, APK artifact link, screenshot diffs and the phone checklist.

## Where things are

| Path | What |
| --- | --- |
| `docs/ROADMAP.md` | Stages 0–30, goals and status |
| `docs/ARCHITECTURE.md` | Modules, state model, edit graph, engine, testing |
| `docs/DESIGN_SYSTEM.md` | Tokens: colour, spacing, type, motion; fixed category order |
| `docs/TOOL_CATALOG.md` | Every tool: id, category, reserved UI slot, stage, status |
| `docs/stages/STAGE_XX.md` | Template for stage notes; `STAGE_00.md`, … are the real notes |
| `CHANGELOG.md` | User-visible changes per stage |
| `gradle/libs.versions.toml` | The only place versions are pinned |
| `build-logic/convention` | `lineover.*` convention plugins used by every module |
| `tools/golden`, `testdata/golden` | Python reference implementations and their golden files |
| `testdata/photos` | Mark's real photos (POCO F7 Ultra, Ultra HDR, Display P3), used by `photo_test` and `RealPhotoTest` |

The module list in `settings.gradle.kts` is **fixed forever**; never add, remove or rename
a module.

## Commands

CI (`.github/workflows/ci.yml`) runs exactly these; run the relevant ones before pushing.

```sh
./gradlew detekt ktlintCheck          # lint job (./gradlew ktlintFormat fixes style)
./gradlew :app:lintDebug              # lint job, Android lint for the whole app
./gradlew testDebugUnitTest verifyRoborazziDebug     # unit job
./gradlew recordRoborazziDebug        # re-record screenshots into src/test/screenshots
./gradlew assembleDebug assembleRelease              # build job
./gradlew connectedDebugAndroidTest   # emulator job (needs a device or emulator)

# native job: host build of the C++ engine + ctest + golden freshness
cmake -S core/engine-native/src/main/cpp -B build/native-host -G Ninja
cmake --build build/native-host && ctest --test-dir build/native-host --output-on-failure
python3 -m pip install -r tools/golden/requirements.txt
python3 tools/golden/generate.py --check
```

## Build facts that are easy to get wrong

- **Built-in Kotlin.** AGP 9 compiles Kotlin itself. Never apply
  `org.jetbrains.kotlin.android`. The Kotlin version is chosen by the
  `kotlin-gradle-plugin` dependency of `build-logic`.
- **compileSdk 36 caps AndroidX.** Compose 1.12+, `androidx.core` 1.19+ and lifecycle
  2.11+ require compileSdk 37 and must not be adopted while D2 stands. Check a library's
  `minCompileSdk` (in its `aar-metadata.properties`) before bumping it.
- Also capped by compileSdk 36: `material3-adaptive` 1.3 (needs 37; WindowSizeClass comes
  from `androidx.window` instead) and lifecycle 2.11 (stay on 2.10).
- **Room + KSP** work with AGP 9 built-in Kotlin (KSP 2.3.x). Apply `lineover.android.room`;
  never edit a committed schema in `*/schemas/` and never use a destructive migration.
- **material3** stays on the stable line (1.4.x); move to 1.5.0 only once it is stable.
- **ABIs:** release is `arm64-v8a` only; debug also carries `x86_64` for the CI emulator.
- **Module plugins:** a module applies only `lineover.*` plugins. Third-party Gradle
  plugins are added to `build-logic` and wrapped in a convention plugin.
- **JNI callbacks and R8:** a Kotlin method that native code finds by name (`GetMethodID`)
  needs a keep rule in that module's `consumer-rules.pro`; `tools/ci/check_jni_keep.py`
  checks the release build's R8 seeds in CI. The emulator runs the unminified debug build and
  cannot catch this.
- **Signing:** release is signed from the `KEYSTORE_B64` / `KEYSTORE_PASS` / `KEY_ALIAS` /
  `KEY_PASS` secrets and falls back to the debug key when they are absent or invalid (CI
  then shows a warning naming the bad secret; `release.yml` fails instead). The debug build
  has the application id `dev.mark.lineover.debug` so both can be installed side by side.
- **NDK in CI** is installed by `tools/ci/install-native-toolchain.sh` (verified, retried)
  before Gradle runs. Do not rely on AGP's own NDK download: it is not retried and a
  truncated archive fails the build.
- **Emulator = SwiftShader.** Instrumented tests prove correctness only. Performance is
  measured on the phone (`:benchmark`), never in CI.

## Image engine (Stage 4)

- A new op implements `lo::Op` (`gpu()` + `cpuRef()`), gets a `@SerialName`d `OpParams` in
  `:core:model`, a Python reference in `tools/golden/reference.py`, a ctest against it and a
  case in `EngineGpuTest` (GPU vs reference within 3/255).
- The graph JSON is shared: change Kotlin and `graph.cpp` together; both round-trip
  `testdata/graph/edit_graph_v1.json`.
- zstd is vendored under `core/engine-native/src/main/cpp/third_party/zstd` (BSD); never
  run scripts from downloaded archives to regenerate it — copy the sources.
- Background ops run in the fixed order of `editOps()` (`:core:model`): Calibration, White
  balance, Light (`LightAdjustments.toOps()`: Highlights / Shadows first, it reads the
  preview's guided-filter coefficients via `OpRegion` / `lo_guided()`), tone (Levels, Curves),
  colour basics, grading, then Invert / Posterize / Threshold. Curves are monotone
  Fritsch-Carlson LUTs (C++, Kotlin and Python share the algorithm); GLSL indexes uniform
  arrays with constants only. Never
  tone-map in 8 bit: all ops run on RGBA16F / float. Colour work (HSL, vibrance,
  saturation) is done in OKLCh, never in sRGB gamma; nothing clips before the display, and
  the display gamut-maps (OKLab lightness and hue kept, chroma bisected; `color::gamutMap`,
  the GPU display pass, `reference.gamut_map` and the UI's `DisplayGamut` change together).
- Full-resolution bitmaps never exist on the Java heap: previews are ≤ 2048 px, tiles are
  512 px regions recycled after conversion.

## Crop and optics (Stage 9)

- Geometry is ONE op (`OpParams.Geometry`, first in `editOps()`): Kotlin composes every
  crop / rotation / Upright / manual / perspective-crop setting into one matrix
  (`GeometryMath.compose`); pixels are resampled once (bicubic, `geometry.h`). Never add a
  second resampling step; add a new transform to the composition instead.
- `geometry.cpp` (`mapToSource`, `warp`), the display shader (`kGeometrySource`) and
  `reference.geometry_maps` / `geometry_warp` change together; goldens are checked against
  OpenCV `warpPerspective` / `remap` (`EngineGpuTest` within 3/255).
- The colour ops stay in source space; only sampling goes through the geometry. The canvas
  image size is the output size (`EditorState.canvasSize`); while a crop tool is open the
  canvas shows the uncropped frame (`canvasGraph`).
- Upright angles are limited to ±25° and fall back to Level (`Upright.LIMIT`).
- Lens model (shared with Lensfun, so profiles convert exactly): `s(r) = 1 + k1 r² + k2 r⁴ +
  p1 r + p2 r² + p3 r³ + p4 r⁴`, channel scale `1 + ca + tv + tc rd + tb rd²` on the distorted
  radius, vignetting `/ (1 + k1 r² + k2 r⁴ + k3 r⁶)`. `geometry.cpp`, the shader,
  `reference.py`, `GeometryAdjustments.radialScale` and `CropMath.undistort` change together;
  `LensProfilesTest` checks the conversion against `tools/golden/lensfun_reference.py`.
- The Lensfun database (CC BY-SA 3.0) ships unmodified in `core/io/src/main/assets/lensfun`
  (Mark's decision, 0.11.2: the on-device download failed), with `NOTICE.txt` and the
  attribution in Settings. `LensProfileStore` checks each file against `LensfunManifest`
  (SHA-256) when it first reads them; `LensProfileStoreTest` checks the assets match the
  manifest. Change the commit, the hashes and the asset files together.

## Detail and effects (Stage 10)

- `detail.cpp` (C++ and its GLSL), `tools/golden/detail_reference.py` and
  `dehaze_reference.py` change together; ctest `detail_test` and `EngineGpuTest` check them.
- Radii are in full-resolution pixels × `OpRegion::scale` / `u_scale`; never size a kernel in
  buffer pixels. `preview_matches_the_downsampled_export` (PSNR > 35 dB) guards it.
- An op that reads neighbours declares `apron()`; tiles and exports pad by the list's sum.
  A multi-pass op uses `GpuOpContext::addPass`, never its own GL code.
- Post-crop Vignette and Grain are output-space finishing, not source ops: they live in
  `Finishing` / `kFinishingGlsl` (display, warp, export, thumbnails). Never add them to
  `buildOps`.
- Clarity / Texture / Dehaze fields are computed once per preview (`computeDetailFields`);
  never per slider move.

## Presets and Auto (Stage 11)

- `lut.cpp` (C++ and GLSL) and `tools/golden/lut_reference.py` change together; ctest
  `lut_test` holds the tetrahedral LUT within 1/255 of Python, `EngineGpuTest` the GPU path.
- A LUT's table never goes in the graph JSON: register it (`EngineSession.registerLut`) before
  a graph names its id; `EditorLooksActions` does this for the editor.
- Presets are partial graphs blended by `PresetMath`; a new blendable op gets a neutral entry
  there. Every op belongs to one `SettingsGroup` (`SettingsGroupTest` checks it).
- Built-in presets and profiles are LineOver's own. Never bundle third-party presets or LUTs
  without a licence that allows it (and note it under R10).

## Export (Stage 12)

- `output.cpp` (C++), `tools/golden/export_reference.py` and its goldens change together;
  `output_test` holds the ICC profiles byte-exact and the steps within 1e-3 / one code, and
  `check_export_files.py` re-reads the PNG / TIFF files with OpenCV and LittleCMS in CI.
- Precision: the export stays float (mapped `ExportImage`) until the encoder; never round to
  8 or 16 bits before `convertRow8/16`.
- A new format gets an `ExportFormat` (with its bit depths and colour spaces), an encoder in
  `ExportEncoders`, a case in `ExportRoundTripTest` (decode and compare, ICC, EXIF) and runtime
  detection in `ExportCapabilities` when it depends on a device codec (hide it when missing).
- Metadata goes through `ExportMetadata` only; location is removed unless the person keeps it.

## History and projects (Stage 8)

- Every edit goes through `EditorViewModel.reduce`, which records it in `EditHistory`; never
  set `EditorState.graph` without recording it (or reading it back with `showingGraph`).
- A new continuous control reports its release with `EditorIntent.EndEdit`
  (`onValueChangeFinished`), so a drag stays one step.
- `.loproj`: change `ProjectFile.SCHEMA_VERSION` only with a `ProjectMigration` and a test;
  never edit an old migration. Writes go through `AtomicFiles`; never write a project file in
  place.

## Editor shell (frozen after Stage 3)

- Every tool is defined once in `core/ui/.../tools/ToolRegistry.kt`; `ToolCatalogTest`
  fails when it and `docs/TOOL_CATALOG.md` disagree. Change both together.
- A stage fills a tool's slot (replaces its placeholder panel); it never moves a slot.
  The `*ReachabilityTest`s keep every slot within two taps.
- Canvas gestures stay in `CanvasView.onTouchEvent`; `CanvasRecompositionTest` fails if a
  gesture recomposes.

## Testing conventions

- **Golden tests:** add the reference to `tools/golden/reference.py`, regenerate, and
  compare with `GoldenAssert` (C++ and Kotlin). Tolerances: 8-bit max |diff| ≤ 2/255 and
  mean ≤ 0.5/255; float ops max 1e-3; GPU vs CPU reference max 3/255. A failure writes a
  diff heatmap PNG.
- **Screenshot tests:** Roborazzi, `@GraphicsMode(NATIVE)`, `@Config(sdk = [35])`, wrapped
  in `LineOverTheme`, recorded images committed under `src/test/screenshots`. Robolectric's
  SDK 36 runtime needs Java 21 and CI runs JDK 17, so screenshot tests use SDK 35. Verify
  on JDK 17 before pushing. Components are captured as state sheets (default, active,
  disabled, fontScale 2.0) at compact and expanded widths (`ComponentScreenshots`).
- **Debug-only code** goes in `src/debug`; `:app` gets a no-op stand-in from
  `src/nonDebug` for every other build type, so release builds never contain it.
- **Engine code** gets a host ctest and, when it touches GL, an instrumented test.
