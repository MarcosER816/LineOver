# Stage 07 — Curves and grading

## Goal

Light → Curves (point, parametric) and Levels; Color part 2: Color Grading (and Split
toning), Color Balance, Selective Color, Channel Mixer, Black & White, Photo Filter,
Gradient Map, Calibration.

## Definition of Done

- [x] Spline monotonicity property tests on random points: ctest
      `spline_is_monotone_for_random_points` (2000 random monotone point sets, incl. flats:
      4096-entry LUT never decreases), `parametric_curve_is_monotone_for_random_settings`
      (500); Kotlin `splineIsMonotoneForRandomPoints` (1000 sets, interpolates every point)
      and `randomEditsInMonotoneModeNeverInvert` (300 × 20 random add / move / delete);
      Python `grade_self_check` (300 sets)
- [x] Goldens for each op: `grade_ops_test` checks every op at 3 settings against
      `testdata/golden/grade` (max 1e-3); `EngineGpuTest` +11 GPU vs reference (3/255)
- [x] Screenshot tests: `GradePanelScreenshots` (all 12 panels, compact / expanded, fontScale
      2.0), `LoCurveEditor` and `LoGradeWheel` component sheets, editor shots with the curves
      and Color Grading panels
- [x] CI green; APK built

## What was built

| Module | Content |
| --- | --- |
| tools / testdata | `reference.py`: Fritsch-Carlson spline + 4096 LUT, curves, parametric, levels, CDL color grading, color balance, selective color, channel mixer, black & white, photo filter, gradient map, calibration; 44 new golden files; `testdata/graph/grade_v1.json` shared with Kotlin |
| `:core:engine-native` | `grade_ops.h/.cpp` (11 ops, cpuRef + GLSL, `curveLut`, `parametricPoints`, `calibrationMatrix`, `channelPercentiles`); `op_util.h` (shared GLSL snippets); `GpuOpContext::setLut` + RGBA32F LUT textures cached by content; `lo_lut1` / `lo_lut3`; Auto Levels percentiles at preview time |
| `:core:engine` | `autoLevels()` |
| `:core:model` | 11 `OpParams`, `GradeAdjustments` (curve editing rules, levels, wheels, …), `MonotoneCurve`, `HueMap`, `editOps` with the full fixed order |
| `:core:designsystem` | `LoCurveEditor`, `LoGradeWheel` (+ math objects, tests, gallery) |
| `:core:ui` | 12 tools `done` (162 tools, 30 done) |
| `:feature:editor` | `GradePanel` (12 panels), `GradientMapPanel`; ViewModel `grade`, `EditGrade`, `AutoLevels`; histogram on while a curves / levels panel is open; Stage 7 panels open at the high detent on phones |

Tests added: ctest `grade_ops_test` (16); JVM `GradeAdjustmentsTest` (9), `HueMapTest`,
`LoCurveEditorMathTest`, `LoGradeWheelMathTest`, `GradeEditingTest` (5), screenshots;
instrumented `EngineGpuTest` +11.

## Decisions and deviations

1. **Curves domain.** Curves, Levels, Color Balance, Selective Color and Color Grading work on
   transfer-encoded values (sRGB curve extended by symmetry, as Stage 6's Invert), i.e. on
   what the eye sees; the 0..1 LUT extends with slope 1 outside 0..1, so HDR highlights and
   out-of-gamut values pass (nothing clips). Channel order: R / G / B curves, then RGB,
   then Luma (on encoded luminance, RGB scaled by the linear luminance ratio).
2. **Monotone by default.** In the editor a point stays between its neighbours in x and, unless
   "Free curve" is on, in y as well, so Fritsch-Carlson guarantees no inversion (brief, DO
   NOT). Ends keep x = 0 / 1. Switching free mode off lifts any lower point to its left
   neighbour. Points closer than 0.01 in x are refused; up to 16 points.
3. **Parametric curve** = 9 points of the same spline: region centres move up to 45 % of their
   region (so it stays monotone), region edges are the 3 split points (min gap 0.05).
4. **Levels**: per-channel rows then the master row: out = ob + (ow − ob) · ((e − ib)/(iw − ib))^(1/γ),
   sign-symmetric power. Auto = per-channel 0.1 / 99.9 % encoded percentiles of the preview
   (black point ≤ 0.5, white point ≥ 0.5).
5. **Color Grading as CDL in encoded space**: slope = 1 + highlights, power = 1 / (1 + midtones),
   offset = shadows (weighted) + global; tone weights (1−t)², 2t(1−t), t² of smoothstep over
   encoded luminance, centred at 0.5 − 0.25·Balance with half-width 0.15 + 0.35·Blending.
   Wheel tints are OKLCh-hue directions. **Split toning** edits the same shadow / highlight
   wheels (hue, amount) and Balance — no separate op.
6. **Wheel hues**: the wheels and hue sliders show the familiar HSV hue; the engine takes OKLCh
   hue. `HueMap` converts piecewise linearly through the 8 band centres, so orange on the
   wheel is orange in the photo.
7. **Color Balance preserve luminosity adds the lost linear luminance back as grey** instead of
   scaling: a ratio exploded (×600) where the shift took a dark pixel's luminance near zero
   (found by the golden test).
8. **Selective Color** (Photoshop-like): family amounts from the encoded channels (reds =
   R − max(G, B), …, whites, neutrals, blacks); inks = 1 − encoded value; relative mode scales
   the change by the ink already there.
9. **Black & White**: luminance moved ±1 stop per colour band (OKLCh, raised cosines between
   6 band centres, faded in by chroma), optional tint. **Photo Filter**: multiply by an OKLCh
   colour (max channel 1) at a density, optionally keeping luminance. **Gradient Map**: stops
   in OKLab, interpolated there into a 4096 LUT, looked up by encoded luminance. **Channel
   Mixer**: linear 3×3 + constant (percent), monochrome = the first row for all outputs.
   **Calibration**: each working-space primary turned (±30 °) and its chroma scaled in OKLCh,
   columns rescaled so white stays white ("camera space" = the working space: JPEG / HEIC
   have no camera space; RAW arrives in Stage 25).
10. **Phones**: the Stage 7 panels open at the panel's high detent (still capped at 40 % of the
    canvas, R9); the curve editor is full width and the panel scrolls to the rest.
11. **LUT textures** are uploaded once per distinct table (content hash, 8 cached); GLSL indexes
    uniform arrays with constants only (generated unrolled), after Stage 6's SwiftShader issue.
12. Version 0.7.0 (9). Branch: session branch `ccr-a94fce54-a1j7s3`.

## Dependencies added (R10)

None.

## Golden images changed (R4)

No existing golden file changed. New: `grade/*` (44).

Screenshots changed, and why:

| Screenshot | Why |
| --- | --- |
| `compact` / `expanded` `tool_panel` | The placeholder shot now uses Texture (Effects, still reserved): Color Grading has a real panel |
| `expanded` `category_light`, `category_color`, `coach_*`, `compare_*`, `canvas_overlays`, `light_overlays`, `menu_more`, `preview_unavailable`, `render_stats` | The side tool list of the Light category no longer shows "Stage 7" for Curves and Levels |
| `components/expanded/component_gallery` | The gallery lists `LoCurveEditor` and `LoGradeWheel` |

## Troubleshooting log (R11)

| Symptom | Root cause | Fix |
| --- | --- | --- |
| `color_balance_3` off by 0.0076 at one pixel | Preserve-luminosity ratio ill-conditioned (a dark pixel's luminance shifted near zero: ×608) | Additive grey correction (decision 7) |
| Curve editor hidden below the fold on phones | Tool panels open at the low detent | Stage 7 panels open at the high detent |

Plan B used: no.

## Proof

- CI run and APK artifact: see the Stage 7 report
- GPU vs reference: emulator reports (tag `LineOverEngineTest`)

## Phone checklist

POCO F7 Ultra, `lineover-<sha>.apk`:

- [ ] Light → Curves (point): tap the curve at a quarter and at three quarters, drag the first
      down and the second up: an S-curve, contrast rises. The magnifier and "In · Out" show
      while dragging; the histogram is behind the curve.
- [ ] Drag a point off the side of the graph: it turns red, release — it is gone.
- [ ] Try to drag a point below its left neighbour: it stops (no inversion); switch on Free
      curve and it can.
- [ ] Color → Color Grading: Shadows wheel towards teal, Highlights wheel towards orange; the
      outer ring changes brightness; double tap the centre resets.
- [ ] Levels → Auto on a flat photo; Photo Filter → Warming 85; Black & White on, move Blues;
      Gradient Map → Teal – orange.

## Addendum: tests on real photos

Mark added three of his own photos (POCO F7 Ultra, 3072 × 4096 Ultra HDR JPEGs, ICC
colourants exactly Display P3, no GPS). They moved from `testdata/` to `testdata/photos/`
(androidTest assets of `:core:engine`).

- `reference.photo_rec2020` decodes them with OpenCV as Display P3 and converts them to the
  working space; `generate.py` writes 14 files to `testdata/golden/photos` (2.7 MB): each
  photo as 32 × 32 block means (96 × 128), the phone-checklist edits run at **full
  resolution** and then block-averaged, and each edit on the 96 × 128 photo (working + sRGB
  display). OpenCV and Pillow decode the files identically (0 difference).
- Cases (the checklists of Stages 5–7): S-curve on `drop`, teal shadows / orange highlights
  on `bird`, Highlights −80 / Shadows +60 on `bird`, orange saturation +60 on `marigold`.
- `photo_test` (ctest, 5): each edit built from the app's graph JSON, cpuRef vs Python, 1e-3.
- `RealPhotoTest` (device): the photos open as Display P3 with their real gain maps and a
  2048 px preview; the full-resolution path (region decoder → P3 → working space → CPU ops,
  48 tiles of 512 px) matches Python's decode and the full-resolution edits as block means
  (8-bit tolerance: 2/255 max, 0.5/255 mean); the GPU matches the reference on the four
  edits (3/255).

No existing golden file changed.

Seen on the photos (questions below): the orange highlights wheel at 50 tints the bright
sky strongly peach while teal in the shadows is faint; orange saturation +60 drifts the
marigolds towards red on an sRGB display because the display transform clips each channel
(out-of-gamut colours are kept until the display, then clipped per channel).

## Open questions (R12)

1. **Curve editor size**: is it usable one-handed in the panel, or should it open as a bigger
   modal (e.g. full-height sheet)? Default: in the panel (high detent, scrolls).
2. (Stage 6) White balance / HSL strength vs another editor? (Stage 5) Highlights / Shadows
   halos? (Stage 4) Sample files: answered, see the addendum above.
3. **Color Grading strength** (real photos): the highlights wheel tints near-white (sky)
   strongly. Should it be gentler on near-white, like other editors? Default: unchanged.
4. **Display gamut mapping** (real photos): saturated oranges clip per channel at the
   display and shift towards red. Should the display transform map out-of-gamut colours
   (reduce chroma at constant hue in OKLCh) instead of clipping? Default: unchanged.
