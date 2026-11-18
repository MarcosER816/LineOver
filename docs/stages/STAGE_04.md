# Stage 04 — Image engine (engine foundation, file loading, edit graph)

## Goal

The engine every tool plugs into: decoding originals of any size without OOM, a tiled
GPU pipeline on its own render thread in linear Rec.2020, ops with a GPU path and a CPU
reference, the edit graph from Kotlin to native, and progressive display on the canvas.
No tool sliders yet.

## Definition of Done

- [x] A 50 MP JPEG and an Ultra HDR JPEG open without OOM (`EngineOpenTest` on the
      emulator: 8160 × 6120 at 100 % in five places; a 4000 × 3000 Ultra HDR JPEG keeps
      its gain map)
- [x] Host ctest covers the tile store (`tile_store_test`), colour conversions
      (`color_test`: sRGB ↔ linear ↔ P3 ↔ Rec.2020 against colour-science references,
      max |error| ≤ 1e-4) and serialization round-trip (`graph_test`, byte for byte with the
      fixture Kotlin's `EditGraphJsonTest` also round-trips)
- [x] Emulator: GPU identity == CPU reference within 3/255 (`EngineGpuTest`, also
      ExposureTest(−1))
- [x] Memory stays under 1.2 GB native + GPU for 50 MP, logged (tag `LineOverMemory`, see Proof)
- [x] CI green; APK built

## What was built

| Module | Content |
| --- | --- |
| `:core:engine-native` (portable) | `color` (matrices from colour-science, sRGB / P3 transfer extended by symmetry, half floats, display transforms), `ops` (`Op` with `gpu()` + `cpuRef()`; Identity, ExposureTest; `OpList`), `graph` (JSON reader / writer of the Kotlin format, Kotlin float formatting, `buildOps`), `tiling` (pyramid, level for scale, visible tiles centre-first, 8 EXIF orientations, region → tile conversion with un-premultiplication), `tile_store` (LRU, zstd half-float spill, thread-safe) |
| `:core:engine-native` (Android) | `Engine`: render thread with its own EGL context (pbuffer when no window), preview + tile RGBA16F textures with a GPU LRU, op shader passes (ping-pong), display program (Rec.2020 → P3 or sRGB, premultiplied), tile request queue for the decoder threads, loading callback, frame timing, CPU full-res tile render, GPU self-test; thin C API `lo_api.h`; JNI bridge |
| `:core:io` | `SourceReader` (header, EXIF orientation, format choice, ≤ 2048 px preview with gain map), `TileDecoder` (BitmapRegionDecoder, inSampleSize 2^L), `TileGeometry` |
| `:core:engine` | `NativeEngine` (JNI), `EngineSession` (open, decoder threads, memory report, full-res render), `RenderScheduler`, `EngineStats` |
| `:core:model` | `OpParams` (was `Op`) with `ExposureTest`; `EditGraph.withBackgroundOps` |
| `:feature:editor` | `CanvasView` is now a SurfaceView driven by the engine (gestures unchanged, 6 floats per event); `EditorCanvas` / `EngineCanvasFactory`; ViewModel opens the engine, feeds the scheduler, shows the spinner after 300 ms; Info shows size, colour space, Ultra HDR; wide-colour-gamut window |
| `:app` | `AppContainer.canvases` |
| tools / testdata | Python references for colour and exposure (`color/*`, `exposure_test/*`), graph fixture |

Tests added: ctest `color_test` (10), `ops_test` (4), `graph_test` (5), `tiling_test` (9),
`tile_store_test` (5); JVM `RenderSchedulerTest` (4), `EditGraphJsonTest` (5),
`EditorViewModelTest` +3, `CanvasTransformTest` +1, `AppPreferencesTest`; instrumented
`EngineGpuTest` (2), `EngineOpenTest` (3), `CanvasFrameTimingTest` (rewritten).

## Tools delivered

None (no tool sliders in this stage). `TOOL_CATALOG.md` unchanged. Identity and
ExposureTest are test ops proving the pipeline, not catalog tools.

## Decisions and deviations

1. **Formats.** 8-bit sRGB and 8-bit Display P3 sources stay 8-bit (half the memory);
   other wide-gamut / HDR sources decode to RGBA_F16 LINEAR_EXTENDED_SRGB. The POCO's P3
   JPEGs therefore keep their full gamut without F16 cost.
2. **Mip pyramid = decoder sampling.** Level L tiles come from BitmapRegionDecoder with
   inSampleSize 2^L (fast, exact JPEG DCT scaling) instead of downsampling level 0; the
   2048 px preview is the coarsest level. Only tiles in view are decoded.
3. **Premultiplied previews.** ImageDecoder cannot scale straight alpha, so previews are
   decoded premultiplied and un-premultiplied natively (Android's alpha flag tells which);
   region tiles are decoded straight. Opaque photos are unaffected.
4. **Display colour space.** EGL `EXT_gl_colorspace_display_p3_passthrough` → the shader
   encodes P3; else `EXT_gl_colorspace_display_p3` → GL encodes, the shader writes linear
   P3; else sRGB. The editor window asks for `COLOR_MODE_WIDE_COLOR_GAMUT` on P3 displays.
5. **Fallback** (brief, IF WRONG): when RGBA16F is not colour-renderable, tiles and op
   targets use `SRGB8_ALPHA8` (working values clipped to 0..1). Only for emulators; the GPU
   test still checks 3/255 (its inputs stay in 0..1), and the stats report `halfFloat`.
6. **Budgets**: 192 MB CPU float tiles + 192 MB GPU textures per image; the 50 MP test uses
   64 MB CPU to force spills.
7. **Layers.** The graph's layers are applied bottom to top as one op list until layer
   compositing arrives (Stage 13).
8. **Tile seams.** Tiles are drawn as separate quads with clamped edges; a 1-px seam can
   show at extreme zoom until tiles get a 1-px border (Stage 29, performance hardening).
9. **Matrix inverse pairs.** colour-science's sRGB matrix uses the published rounded IEC
   values, so sRGB ↔ Rec.2020 are inverse only to ~3e-5; `color_test` checks the pairs
   against the stage's 1e-4 bound (the comparisons with colour-science itself are exact to
   float precision).
10. **No full-res Bitmaps on the Java heap.** Previews ≤ 2048 px, tiles are 512 px regions
    recycled right after native conversion; Bitmap pixels live in native memory.
11. Version 0.4.0 (6). Branch: session branch `ccr-a94fce54-a1j7s3`.

## Dependencies added (R10)

| Dependency | Version | Licence | Why |
| --- | --- | --- | --- |
| androidx.exifinterface | 1.4.2 (minCompileSdk 34) | Apache-2.0 | EXIF orientation for tiles (BitmapRegionDecoder ignores it) |
| zstd (vendored C sources) | 1.5.7 | BSD-3-Clause (dual BSD / GPL-2.0; used under BSD) | Compressing spilled tiles |

Android NDK libraries `android`, `jnigraphics` (system, linked).

## Golden images changed (R4)

None changed. New golden files: `color/*` (12), `exposure_test/*` (2).

## Troubleshooting log (R11)

| Symptom | Root cause | Fix |
| --- | --- | --- |
| Single-file zstd could not be generated | Running the archive's `combine.py` was refused (code from a downloaded archive) | Vendor the plain `lib/` sources and compile them with CMake |
| `matrices_are_inverse_pairs` failed at 1e-5 | colour-science's sRGB matrix is the rounded IEC one (decision 9) | Bound 1e-4, documented |
| `Cannot scale unpremultiplied pixels!` | ImageDecoder restriction | Decision 3 |
| Robolectric navigation test: `UnsatisfiedLinkError` | The JVM has no `liblineover_engine.so` | The factory treats a missing engine like an undecodable file (no canvas) |
| RenderScheduler tests saw nothing applied | `advanceUntilIdle` does not drive `backgroundScope` | A test scope on the test scheduler |

Plan B used: no.

## Proof

- CI run and APK artifact: see the Stage 4 report
- Memory log (emulator, `EngineOpenTest`, tag `LineOverMemory`): see the Stage 4 report
- GPU vs reference and frame timing: emulator reports (tags `LineOverEngineTest`, `LineOverPerf`)

## Phone checklist

POCO F7 Ultra, `lineover-<sha>.apk`:

- [ ] Open the biggest photo you have: a sharp-enough preview appears at once.
- [ ] Zoom to 100 % (double tap): the screen sharpens tile by tile from the middle; a small
      spinner appears at the top right only if it takes more than a moment.
- [ ] Pan around at 100 %: new areas sharpen; nothing stutters or crashes.
- [ ] Open an Ultra HDR photo from the POCO camera: it opens; More → Info says
      "Ultra HDR gain map kept".
- [ ] Compare a colourful photo with the Gallery app side by side: no colour shift (reds and
      greens of P3 photos look the same, not duller).
- [ ] More → Info shows the size, colour space (e.g. Display P3) and encoding.
- [ ] A photo taken in portrait opens upright (EXIF orientation).

## Open questions (R12)

1. **Sample files**: can you add 2–3 files that matter to you to `testdata` (a RAW/DNG, an
   Ultra HDR from the POCO, a panorama-size JPEG), if they are not private? Default: the
   tests generate synthetic files; nothing personal is committed.
