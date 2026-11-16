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

## Edit graph

Defined in `:core:model`, serialised with kotlinx.serialization.

- `EditGraph(schemaVersion, layers)` — layers bottom to top.
- `Layer(id, ops)` — an **immutable list of serializable `Op`s per layer**, applied in
  order to the layer source.
- `Op` — a sealed interface; each op has a stable `@SerialName` that is never renamed or
  reused, so old `.loproj` files keep loading. Unknown fields are ignored on read.

Every edit produces a new graph value. The **undo stack stores graph snapshots, never
pixels**. The single exception is painting (brush, healing, clone …): a stroke is stored
as a **paint delta of compressed tiles** that the graph references.

A `.loproj` project file holds the graph JSON, a reference to the original, and the paint
deltas. Exports go to `Pictures/LineOver`.

## Engine

Native **C++17, OpenGL ES 3.2**, in `:core:engine-native`; Kotlin reaches it through
`LoEngine` in `:core:engine`.

| Aspect | Decision |
| --- | --- |
| Working space | Linear Rec.2020, D65, straight alpha; values above 1.0 allowed (HDR headroom) |
| Pixel format | RGBA16F |
| Tiles | 512 × 512, cached; only dirty tiles are re-rendered |
| Preview | A proxy whose long edge is 2048 px drives interactive editing |
| Full resolution | Rendered in the background per tile; used for export and 1:1 zoom |
| Threads | A dedicated render thread owns the GL context; the UI thread never touches pixels |
| CPU path | Every op also has a CPU implementation, the reference for GPU tests |
| Deprecated APIs | None. RenderScript is forbidden (R8) |

`core/engine-native/src/main/cpp`:

```
include/lineover/   public C++ API (portable, no Android headers)
src/                portable engine core            -> lo_engine (static)
android/            JNI bridge, EGL / GLES backend  -> liblineover_engine.so
testing/            GoldenAssert, PNG / NPY readers (host only)
tests/              ctest executables (host only)
```

The same `CMakeLists.txt` builds the Android shared library (through Gradle) and, on
Linux, the host static library plus the ctest tests.

## AI models

`ModelSpec` (`:core:ml`) describes a model: id, name, URL, size, licence, SHA-256. A tool
that needs a model shows its size and licence, downloads it on first use, verifies the
SHA-256 (`Sha256`) before loading, and Settings can delete it. The runtime is LiteRT.

## Testing

| Layer | Tool | Where |
| --- | --- | --- |
| Reference results | Python (NumPy, OpenCV, colour-science) | `tools/golden` → `testdata/golden` |
| Engine, CPU, host | ctest + `GoldenAssert` (C++) | `core/engine-native/src/main/cpp/tests` |
| Engine on device (JNI, GL) | Instrumented tests + `GoldenAssert` (Kotlin) | `core/engine/src/androidTest` |
| Kotlin logic | JUnit 4 | `src/test` of each module |
| UI | Roborazzi screenshots (Robolectric native graphics) | `src/test`, images in `src/test/screenshots` |
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
