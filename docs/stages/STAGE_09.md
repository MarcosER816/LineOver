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
9. **Lens profiles** (follow-up, 0.9.1, Mark: "Add them"): see the section below.
10. Version 0.9.0 (11), lens profiles 0.9.1 (12). Branch: session branch `ccr-a94fce54-a1j7s3`.

## Lens profiles (follow-up, 0.9.1)

Mark's answer to the Stage 9 question was to add the Lensfun lens profiles.

- **Licences, checked (R10):** the Lensfun *database* is CC BY-SA 3.0 (not LGPL; the
  LGPL-3 part is the Lensfun library, which LineOver does not use). LineOver parses the XML
  and applies the models with its own code. The data is never bundled: Settings → AI models
  → Lens profiles (Lensfun), or the Download chip in the panel, fetches the 56 `data/db`
  files of pinned commit `37238fc` from GitHub (5.0 MB). Each file is checked against its
  SHA-256 in `LensfunManifest` and the install is atomic; Delete removes it. The
  attribution is shown in Settings and in the panel. `testdata/lensfun/fixture.xml` copies
  four lenses for the tests, with the attribution in its header.
- **The engine's lens model is now Lensfun's:** distortion `s(r) = 1 + k1 r² + k2 r⁴ + p1 r
  + p2 r² + p3 r³ + p4 r⁴`. Each channel's scale is `1 + ca + tv + tc rd + tb rd²` on the
  distorted radius, as Lensfun applies its TCA. Vignetting also divides by
  `1 + k1 r² + k2 r⁴ + k3 r⁶` (the "pa" model) in source space. ptlens / poly3 / poly5
  distortion and linear / poly3 TCA therefore convert *exactly* (`LensProfiles`): the
  rescaling from Hugin units uses the crop factors and Lensfun's d-normalisation, as in
  lensfun's `rescale_polynomial_coefficients`. A first version fitted ptlens to k1 / k2 and
  was 7–11 px off on a 24 MP zoom, so the engine was extended instead (still one resample).
- **Verified against Lensfun's own arithmetic:** `tools/golden/lensfun_reference.py`
  mirrors lensfun's `modifier.cpp` / `mod-coord.cpp` / `mod-subpix.cpp` / `mod-color.cpp`
  (NormScale, rescaling, undistortion, anti-TCA, devignetting). For 7 cases (ptlens, poly3
  and poly5 distortion, linear and poly3 TCA, pa vignetting, a full-frame lens on an APS-C
  body, an interpolated focal length), LineOver's mapping matches within 1e-12 px and the
  gains within 1e-15 (`LensProfilesTest`). The extended lens model has a new warp golden
  `geometry/lens_profile` vs OpenCV `remap` (ctest, and `EngineGpuTest` at 3/255), and
  `geometry/vignetting_profile` (ctest, GPU).
- **Matching:** camera by EXIF make / model (crop factor, mount); lens by how much of the
  database name the EXIF lens name covers, among lenses for the mount and focal length.
  A compact's built-in lens matches by its mount. Without a camera entry, the crop factor
  comes from the 35 mm-equivalent focal length. The panel lists up to 8 candidates to pick
  from. Phone cameras (the POCO F7 Ultra) are not in the database, and their JPEGs are
  already corrected in camera.
- **Deviations from Lensfun:** between calibrated focal lengths LineOver interpolates
  linearly, where Lensfun uses a spline. Vignetting is bilinear in focal length and log
  aperture at the nearest calibrated distance, where Lensfun uses an inverse-distance
  weighting. A lens's optical-centre offset (rare in the database) is ignored.
- `INTERNET` permission added, used only for downloads the user taps.

## Dependencies added (R10)

None (code). Data: the Lensfun lens database, CC BY-SA 3.0, downloaded on request, never
bundled (see Lens profiles). OpenCV is used only by the Python reference generator in
`tools/golden`, as before.

## Golden images changed (R4)

No existing golden file changed. New: `testdata/golden/geometry/` (5 warp cases with their
graphs and GPU display references, 3 vignetting settings + GPU reference, 24 homography
fixtures); with the lens profiles: `geometry/lens_profile*` (6th warp case),
`geometry/vignetting_profile*` and `lensfun/corrections.json` (Lensfun's own corrections
for the fixture lenses).

Screenshots changed, and why:

| Screenshot | Why |
| --- | --- |
| `editor/expanded/category_crop`, `category_optics` | The ten tools say "Ready" instead of "Arrives in Stage 9" |
| `editor/*/crop_*`, `optics_*` | New: the Crop and Optics panels and the crop overlay (`crop_straighten` re-recorded after the edge margin: the constrained crop is 2 px smaller) |
| `editor/expanded/optics_distortion` | Lens profiles: the panel's Lens profile section (Download) |
| `editor/*/optics_distortion_profile` | New: a matched profile with its candidates and amounts |
| `settings/*/settings` | AI models: the Lens profiles (Lensfun) row and its summary text |

## Troubleshooting log (R11)

| Symptom | Root cause | Fix |
| --- | --- | --- |
| Warp golden off by up to 0.13 | Lens / CA parameters in float, and 1/32 position quantisation (OpenCV 5 does not quantise float images: 0.019 vs 1.9e-7) | Doubles; exact positions |
| A tile rendered from its footprint ≠ the full warp | The buffer offset changed float rounding | Positions in full-image coordinates, the integer offset subtracted after `floor` |
| Segments split at every pixel stair | Raw gradients | LSD's Gaussian pre-smoothing, collinear merging; anti-aliased test bars |
| Chromatic aberration estimate refused | The edge sample included near-zero gradients | Keep points ≥ 0.2 × the strongest edge |
| CI emulator: a straightened 4:5 crop's edge blocks had alpha 1.0012 (`RealPhotoTest`) | Constrain let the crop touch the photo's edge; the bicubic taps of the edge pixels read the 0 border (an overshoot / faint dark edge in export) | `CropMath.edgeMargin`: the crop keeps 2 px (+ the CA offset) clear of the edge unless every pixel samples a pixel centre exactly (crop, quarter turns, flips) |
| Constrain with strong barrel correction | The lens model folds back past a radius; the corners had no undistorted position and Newton diverged | `undistort` solves on the monotone part and clamps at the fold radius (`strongBarrelCorrectionStopsAtTheFold`) |
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
- [ ] Lens profiles: Settings → AI models → Lens profiles → Download (5.0 MB). Open a photo
      from a camera with an interchangeable lens (e.g. a Sony / Canon / Nikon JPEG), Optics →
      Lens distortion → Use lens profile: its lens is named, straight lines near the edges
      straighten, the corners brighten. A phone photo shows "No lens profile found".

## Open questions (R12)

Answered by Mark: (1) lens profiles — added (above); (2) two fingers undo, three redo, and
500 history steps — kept; (3) White balance / HSL strength — fine as it is.

None open.
