# Stage 05 — Light

## Goal

The first real tools: Exposure, Contrast, Highlights, Shadows, Whites, Blacks and
Brightness, each with a GPU path and a CPU reference, plus the histogram, clipping
warnings and before / after, so the Light category is fully usable.

## Definition of Done

- [x] Each op has a Python reference golden at 3 settings within tolerance
      (`light_test`: C++ cpuRef vs `testdata/golden/light/<op>_{1,2,3}_f32.npy`, max 1e-3)
- [x] GPU vs cpuRef in the emulator (`EngineGpuTest`: one case per op, middle setting, on
      the HDR scene, max 3/255)
- [x] Histogram matches numpy within 1 count per bin on a test image
      (`EngineGpuTest.gpuHistogramMatchesNumpy` vs `histogram/scene_srgb_hist_f32.npy`)
- [x] Preview update ≤ 16 ms target, timing logged on the device via a debug overlay
      (More → Render stats in debug builds; logcat `LineOverEngine` every 30 updates;
      `CanvasFrameTimingTest.lightEditsUpdateThePreviewAndLogTheLatency` logs it on CI)
- [x] Screenshot tests of the panel (`LightPanelScreenshots` state sheets at compact and
      expanded widths incl. fontScale 2.0; editor shots of the panel, overlays, compare)
- [x] CI green; APK built

## What was built

| Module | Content |
| --- | --- |
| tools / testdata | `reference.py`: procedural HDR scene (128 × 96, sky to 4, sun 8, dark ground), the six ops, guided filter, float16-rounded sRGB histogram; 25 new golden files; `testdata/graph/light_v1.json` shared by Kotlin and C++ |
| `:core:engine-native` (portable) | `light.h/.cpp`: the ops (cpuRef + GLSL), `guidedCoefficients` (separable clipped box means in double), `sampleGuided`, luminance percentiles, `autoLight`; `OpRegion` + `Op::cpuRefRegion`; graph names `exposure`, `contrast`, `brightness`, `whites`, `blacks`, `highlights_shadows` |
| `:core:engine-native` (Android) | RG32F coefficient texture + `lo_guided()` prelude for every op pass; per-tile rect uniforms; half floats converted with our own round-to-nearest-even; histogram compute shader (fence, no stall, ≤ 30 Hz, only while shown); clipping and compare (hold / split / side by side) in the display pass; preview-update latency ring; C API + JNI |
| `:core:engine` | `Histogram`, `CompareMode`, `EngineSession.autoLight / setShowOriginal / setCompare / setClipping / setHistogramEnabled / histogram`, extended `EngineStats` |
| `:core:model` | `OpParams` Exposure, Contrast, Brightness, Whites, Blacks, HighlightsShadows; `LightParam`, `LightAdjustments` (fixed op order) |
| `:core:designsystem` | Histogram colour tokens |
| `:core:ui` | 10 tools `done`; `history.before_after` moved from Stage 8 to 5 |
| `:feature:editor` | `LightPanel`; histogram, clipping badge, Before label, split divider, side-by-side labels, swipe HUD, render-stats overlay; `CanvasView` hold-to-compare and swipe mode; ViewModel Light state and engine sync |

Tests added: ctest `light_test` (14); JVM `LightAdjustmentsTest` (6), `LightEditingTest`
(9), `CanvasGestureTest` (4), `LightPanelScreenshots` (2), editor screenshots +7 per width;
instrumented `EngineGpuTest` +7, `CanvasFrameTimingTest` +1.

## Tools delivered

`light.exposure`, `light.contrast`, `light.highlights`, `light.shadows`, `light.whites`,
`light.blacks`, `light.brightness`, `light.histogram`, `light.clipping_warnings`,
`history.before_after` (162 tools, 11 done).

## Decisions and deviations

1. **The maths** (same in Python, C++ and GLSL; Y = Rec.2020 luminance, l = log2(Y / 0.18)):
   - Exposure: RGB × 2^EV, EV ∈ [−5, 5].
   - Contrast c: l′ = l + 0.75·(c/100)·l / (1 + (l/2.5)²) — an S-curve around mid grey in log
     space, monotonic for every setting (slope at grey 0.25 … 1.75).
   - Brightness b: Y′ = Y^g on 0..1 with g = 2^(−b/100), continued with slope g above 1.
   - Whites / Blacks k: l′ = l + (k/100)·clamp(l / l_p, 0, 1)², l_p = the endpoint (1.0 or
     0.002) in stops: the endpoint moves ±1 stop, mid grey stays.
   - Highlights h / Shadows s: base = guided filter of L = log2 Y; rel = base − log2 0.18;
     masks smoothstep(0, 2.5, rel) and 1 − smoothstep(−5, 0, rel); Y′ = Y·2^(1.5·(h·m_h +
     s·m_s)/100): the base moves, the detail L − base is kept.
   All tone ops scale RGB by Y′/Y (chroma ratios preserved); pixels with Y ≤ 1e-6 are left.
2. **Fixed op order** H/S → Exposure → Whites → Blacks → Contrast → Brightness. H/S needs an
   image-wide base; computing it once on the preview's *source* is only exact while it runs
   first. Its coefficients are sampled at each pixel's image uv (the fast guided filter's
   upsampling), so tiles at 100 % and export use the same base as the preview.
3. **Before / after moved from Stage 8 to 5** (the brief asks for it now): hold the photo
   (250 ms, one finger, no movement) = original + "Before"; the Compare button cycles split
   (draggable divider) → side by side → off. The catalog and registry say Stage 5.
4. **Histogram paths**: GLES 3.1 compute shader (the POCO: GLES 3.2). Where a context is
   only GLES 3.0 (the CI's API 34 emulator) the preview is read back asynchronously as
   floats (pixel pack buffer + fence, buffer freed when the histogram is hidden) and binned
   by `lo::histogramSrgb`, the C++ reference of the shader, which ctest checks against numpy
   (0 counts off). `EngineGpuTest` logs which path ran.
5. **Half-float rounding**: textures now receive half floats converted by the engine (round
   to nearest even, like numpy) instead of letting the driver convert; it also halves the
   upload bandwidth.
6. **Clipping thresholds**: a channel that would display as 255 (linear ≥ 0.99621) is a
   clipped highlight; all channels that would display as 0 are crushed shadows. The token
   colours are converted to the display's primaries.
7. **Swipe mode**: vertical steps of 48 dp pick the parameter (wrapping, with a tick);
   horizontal movement over the full canvas width spans the full range.
8. **Panel**: on phones the low detent shows the selected slider (Auto, swipe-mode switch,
   "All sliders"); swiping the panel up shows all seven. Wide layouts always show all.
9. **Edits are not saved yet**: the graph lives in the editor's ViewModel until projects
   are saved (Stage 8). Non-destructive (R7): the original is never written.
10. **Guided-filter memory**: computing the coefficients of a 2048 × 1536 preview needs
    ~75 MB of transient doubles on the opening thread, freed right after.
11. Version 0.5.0 (7). Branch: session branch `ccr-a94fce54-a1j7s3`.

## Dependencies added (R10)

None.

## Golden images changed (R4)

No existing golden file changed. New: `input/scene_rec2020_f32.npy`, `light/*` (24),
`histogram/scene_srgb_hist_f32.npy`. The scene was tuned (5 % sky texture, one patch
nudged) so that no two pixels sit within 2e-4 bins of the same histogram bin edge
(`histogram_edge_crowding`), which keeps the "1 count per bin" check meaningful.

Screenshots changed, and why:

| Screenshot | Why |
| --- | --- |
| `compact` / `expanded` `tool_panel` | Light tools have real panels now; the placeholder shot uses a still-reserved tool (White balance) |
| `landscape/editor_landscape` | Shows the real Light panel instead of the Exposure placeholder |
| `compact` / `expanded` `canvas_overlays` | Histogram and clipping are real overlays now (guides is still a placeholder) |
| `compact` / `expanded` `menu_more` | Histogram and clipping are done: no "Stage 5" line under them |
| `compact` / `expanded` `coach_*` | Coach mark 2 now says "Hold the photo …"; expanded coach shots show the side list behind them |
| `expanded/category_light`, `expanded/category_favorites_pinned`, `expanded/preview_unavailable` | The side tool list no longer shows "Stage 5" for done tools |

## Troubleshooting log (R11)

| Symptom | Root cause | Fix |
| --- | --- | --- |
| Histogram golden self-check failed (repeated values on bin edges) | float16 rounding makes flat patches and some sky rows share values; one patch sat 0.001 bins from an edge | Per-edge crowding check; sky texture and one patch value adjusted |
| CI API 34: `no GLES 3.1 compute`, histogram tests failed | The API 34 emulator's GL context is GLES 3.0 | Float-readback fallback (decision 4) and a ctest of the CPU histogram |
| Hold-to-compare never fired in the JVM test | `View.postDelayed` waits for the view to be attached | Main-looper `Handler` |
| First vertical swipe step was lost | The move that locked the axis was not counted | Re-dispatch it once the axis is locked |

Plan B used: no.

## Proof

- CI run and APK artifact: see the Stage 5 report
- GPU vs reference, histogram vs numpy, preview-update latency: emulator reports (tags
  `LineOverEngineTest`, `LineOverPerf`)

## Phone checklist

POCO F7 Ultra, `lineover-<sha>.apk`:

- [ ] Light → Exposure: drag quickly left and right — the photo follows the finger with no lag.
- [ ] While dragging a slider, put a second finger anywhere: blown highlights turn pink-red,
      crushed shadows blue; lift it and they go.
- [ ] Hold one finger still on the photo: "Before" appears with the original; lift to return.
- [ ] Tap Compare (top bar): split with a draggable line; again: side by side; again: off.
- [ ] Highlights −100 / Shadows +100 on a backlit photo: sky recovers, shadows open; look
      for dark or bright rims along edges (the question below).
- [ ] Swipe the panel up: all seven sliders; double tap one to reset it; Auto on a dull photo.
- [ ] Hand icon (swipe mode): on the photo swipe up / down to pick, left / right to change.
- [ ] More → Histogram: it moves with the sliders. More → Clipping warnings stays on.
- [ ] Debug APK only: More → Render stats — note the "update … P95" value while dragging
      Exposure (target ≤ 16 ms).

## Open questions (R12)

1. **Highlights / Shadows**: does it look natural or haloed on your photos? A screenshot
   with Highlights −100 and Shadows +100 helps. Default: eps 0.01 as briefed; if haloed,
   raise eps or switch to the 2-scale pyramid (brief, IF WRONG).
2. (From Stage 4) **Sample files** for `testdata` (RAW/DNG, POCO Ultra HDR, panorama), if
   not private. Default: synthetic test files only.
