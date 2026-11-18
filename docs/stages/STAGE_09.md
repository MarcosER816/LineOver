# Stage 09 — Crop and optics

## Goal

The Crop and Optics categories: crop with aspect ratios and overlays, straighten (with auto),
rotate, flip, perspective crop, Upright, the manual transform, lens distortion, chromatic
aberration and lens vignetting — all as ONE geometry op applied first, resampled once.

## Definition of Done

- [x] Homography unit tests vs OpenCV `getPerspectiveTransform`
      (`GeometryMathTest.homographiesMatchOpenCvGetPerspectiveTransform`: 24 fixture quads from
      `tools/golden`, every element within 1e-5 relative, corners mapped within 1e-6)
- [x] Goldens vs OpenCV `warpPerspective` / `remap`: CPU `geometry_test`
      (`warp_matches_opencv_for_every_case`, 1e-3) and GPU `EngineGpuTest` (rotation,
      perspective, flip + rotate + crop, lens distortion, chromatic aberration, vignetting;
      3/255, the canvas's own display pass)
- [x] Screenshot tests (Crop panel, dragging with the thirds grid, straighten ruler,
      perspective corners, Upright, Guided lines, distortion, chromatic aberration, lens
      vignetting; compact and expanded)
- [x] CI green; APK built

## What was built

| Module | Content |
| --- | --- |
| `:core:engine-native` | `geometry.h/.cpp`: `Geometry` (matrix, output size, k1 / k2, per-channel CA, vignetting), `mapToSource`, `sourceToOutput` (Newton undistort), `outputRectOf`, `footprint`, `outputScaleAt`, bicubic `warp` (OpenCV Keys a = -0.75, exact float positions, border 0), the lens vignetting op, `warpRgba8` (gain map). `lines.h/.cpp`: LSD-style segments, Canny + Hough horizon, chromatic aberration estimate. Thumbnails apply the geometry. Android: the display program's geometry variant (`kGeometrySource`), a warp pass (histogram), geometry tiles (footprint -> source tiles -> drawn over their output rect), output-tile export, analysis + gain-map C API and JNI |
| `:core:model` | `OpParams.Geometry`, `GeometryAdjustments` (panel values), `GeometryMath` (composition, camera rotation K·R·K⁻¹), `CropMath` (aspect, auto-constrain), `Mat3` + `Homography` (DLT), `Upright` (vanishing points, ±25° limit, Level fallback), `GeometryLabels` (History names) |
| `:core:engine` | `EngineSession.outputSize`, `lineSegments`, `horizon`, `chromaticAberration`, `gainmapFor(graph)` (`GeometryWarp`), full-resolution render in output tiles |
| `:feature:editor` | `CropPanel`, `OpticsPanel`, `StraightenRuler` (haptic at 0), `CropGestures` in `CanvasView` (48 dp handles, move, double tap reset, perspective corners, guide lines), `CropOverlayLayer` (in the reserved overlay slot), `EditorGeometry` reducers, `EditorGeometryActions` (analyses off the main thread) |
| `:core:designsystem` | `cropScrim`, `cropLine`, `cropGrid` tokens |
| `:core:ui` | 10 tools `done` (162 tools, 45 done) |

Tests added: ctest `geometry_test` (7), `lines_test` (3), `thumbnail_test` updated; model
`GeometryMathTest` (15), `HistoryLabelsTest` +1; editor `GeometryEditingTest` (9),
`CropGesturesTest` (6), 9 screenshot states × 2 widths; instrumented `EngineGpuTest` +6,
`RealPhotoTest` +2 (a quarter turn renders the turned photo and its gain map; a straightened
4:5 crop renders at its size with no transparent wedge).

## Tools delivered

| id | Name | UI slot | Tests |
| --- | --- | --- | --- |
| `crop.crop` | Crop | `cat/Crop/01` | GeometryEditingTest, CropGesturesTest, screenshots |
| `crop.aspect_ratios` | Aspect ratios | `cat/Crop/02` | GeometryMathTest, GeometryEditingTest |
| `crop.straighten` | Straighten | `cat/Crop/03` | GeometryMathTest (constrain), lines_test (horizon), screenshots |
| `crop.rotate` | Rotate | `cat/Crop/04` | GeometryMathTest, RealPhotoTest |
| `crop.flip` | Flip | `cat/Crop/05` | GeometryMathTest, EngineGpuTest |
| `crop.perspective_crop` | Perspective crop | `cat/Crop/06` | GeometryMathTest, EngineGpuTest (perspective), screenshots |
| `optics.upright` | Upright | `cat/Optics/01` | GeometryMathTest (Full, Vertical, Level, Guided, limit), GeometryEditingTest, lines_test |
| `optics.lens_distortion` | Lens distortion | `cat/Optics/02` | geometry_test, EngineGpuTest (lens) |
| `optics.chromatic_aberration` | Chromatic aberration | `cat/Optics/03` | geometry_test, lines_test, EngineGpuTest |
| `optics.lens_vignetting` | Lens vignetting | `cat/Optics/04` | geometry_test, EngineGpuTest |

`TOOL_CATALOG.md` updated: yes.

## Decisions and deviations

1. **One matrix, one resample** (brief DO NOT). Kotlin composes every setting; the engine
   samples each output pixel once. Lens distortion and chromatic aberration are part of the
   same sampling map (applied to the matrix's source position), not a second pass.
2. **Ops in source space, geometry at sampling.** The colour ops keep running on the source
   preview and source tiles; the display pass maps screen -> output -> source. So the
   canvas never holds a warped copy (only the histogram reads one), and a geometry change
   re-renders nothing but the display pass.
3. **Bicubic, not Lanczos**: OpenCV's bicubic (Keys a = -0.75) so CPU and GPU match the
   OpenCV goldens; OpenCV 5 uses exact float positions for float images (no 1/32
   quantisation), checked against it.
4. **On-screen tiles** clamp their bicubic taps at their own edge (their neighbours are
   other draws): at most a sub-pixel difference along tile seams while zoomed in. Export
   renders output tiles from their full source footprint, so it has no seams.
5. **Upright**: LSD-style segments (Grompone von Gioi et al.) without the a-contrario NFA
   test (segments are kept by density and length); vanishing points by least squares; a
   26 mm-equivalent focal length (0.75 × long edge). Auto = Vertical plus the horizontal
   correction when it is within 15°; every angle is limited to ±25° and Upright falls back to
   Level beyond (brief IF WRONG rule, applied from the start; the message says so). Guided:
   the canvas shows the photo without geometry while lines are drawn.
6. **Crop UX**: while a crop tool is open the canvas shows the whole frame and the crop is
   drawn over it; the canvas image size is the output size otherwise. The composition grid
   shows only while a handle or the ruler is dragged. Constrain shrinks the crop at
   composition time, keeping its centre and ratio. Picking 4:5 gives a portrait crop (the
   label's orientation); 3:2, 16:9, Original and Custom follow the photo.
7. **Compare / hold** show the original colours through the same geometry, so the split
   and side-by-side halves stay aligned.
8. **Gain map**: `EngineSession.gainmapFor(graph)` warps it (single-channel ALPHA_8 or RGB)
   at its own resolution with the same matrix; Stage 12 writes it into the exported JPEG.
9. **lensfun** profiles are not included (LGPL-3 data, about 10 MB; see the question below).
10. Version 0.9.0 (11). Branch: session branch `ccr-a94fce54-a1j7s3`.

## Dependencies added (R10)

None. (OpenCV is used only by the Python reference generator in `tools/golden`, as before.)

## Golden images changed (R4)

No existing golden file changed. New: `testdata/golden/geometry/` (5 warp cases with their
graphs and GPU display references, 3 vignetting settings + GPU reference, 24 homography
fixtures).

Screenshots changed, and why:

| Screenshot | Why |
| --- | --- |
| `editor/expanded/category_crop`, `category_optics` | The ten tools say "Ready" instead of "Arrives in Stage 9" |
| `editor/*/crop_*`, `optics_*` | New: the Crop and Optics panels and the crop overlay |

## Troubleshooting log (R11)

| Symptom | Root cause | Fix |
| --- | --- | --- |
| Warp golden off by up to 0.13 | Lens / CA parameters in float, and 1/32 position quantisation (OpenCV 5 does not quantise float images: 0.019 vs 1.9e-7) | Doubles; exact positions |
| A tile rendered from its footprint ≠ the full warp | The buffer offset changed float rounding | Positions in full-image coordinates, the integer offset subtracted after `floor` |
| Segments split at every pixel stair | Raw gradients | LSD's Gaussian pre-smoothing, collinear merging; anti-aliased test bars |
| Chromatic aberration estimate refused | The edge sample included near-zero gradients | Keep points ≥ 0.2 × the strongest edge |
| 4:5 came out landscape (5:4) | The ratio followed the landscape frame | A picked portrait label is portrait |
| detekt (magic numbers, object size) | Matrix maths in one object | `Mat3`, `CropMath`, `GeometryLabels` split out; index arithmetic suppressed with reasons |

Plan B used: no.

## Proof

- CI run and APK artifact: see the Stage 9 report

## Phone checklist

POCO F7 Ultra, `lineover-<sha>.apk`:

- [ ] Open a photo of a tall building shot looking up (tilted verticals). Optics → Upright →
      Auto: the verticals become vertical. (If it says "levelled instead", the tilt was
      over 25°: try Guided and draw two lines along the building's edges.)
- [ ] Crop → Crop → 4:5: a portrait frame; drag a corner, move the frame, pinch to zoom; the
      thirds grid shows only while dragging; double tap the frame to reset.
- [ ] Close the panel: the photo is cropped. Check the edges: no black wedges (Constrain crop
      is on). Turn Constrain off in Straighten after dragging the ruler: wedges appear.
- [ ] Straighten: drag the ruler; a tick at 0; Auto on a photo with a horizon.
- [ ] Rotate right twice, Flip horizontal: the photo turns and mirrors; Undo steps back.
- [ ] Perspective crop on a photo of a page or a painting: drag the four corners onto it.
- [ ] Lens distortion, chromatic aberration (Auto), lens vignetting move the photo as named.
- [ ] Zoom in to 100 % after a crop: sharp tiles load; no gaps.

## Open questions (R12)

1. **lensfun lens profiles** (Stage 9 ASK): adds LGPL-3 data, about 10 MB, used as a dynamic
   download (D3 style) or not at all? Default: not included; Distortion / Chromatic
   aberration / Lens vignetting are manual (CA has Auto).
2. (Stage 8) Gesture mapping (two fingers undo, three redo) and history limit (500)?
3. (Stage 6) White balance / HSL strength compared with another editor?
