# Stage 06 — Color basics

## Goal

The Color category, part 1: White balance (with eyedropper), Vibrance, Saturation,
HSL / Color Mixer (with targeted adjustment), Invert, Posterize, Threshold.

## Definition of Done

- [x] Goldens vs Python (colour-science Bradford CAT, OKLab) within tolerance
      (`color_ops_test`: every op against `testdata/golden/colorops`, 3 settings each,
      Invert 1; max 1e-3) and GPU vs reference on the emulator (`EngineGpuTest`, 7 cases, 3/255)
- [x] HSL band-weight unit tests: weights sum to 1 (`hsl_band_weights_sum_to_one`: 36 001
      hues, |sum − 1| < 1e-6, peak 1 at each centre, at most two neighbours active; Python
      `color_self_check` the same to 1e-12)
- [x] Screenshot tests (`ColorPanelScreenshots` state sheets at compact / expanded incl.
      fontScale 2.0; editor shots with the eyedropper loupe and the targeted HUD)
- [x] CI green; APK built

## What was built

| Module | Content |
| --- | --- |
| tools / testdata | `reference.py`: white balance (colour-science CAT + Krystek locus), OKLCh vibrance / saturation / HSL, encoded invert / posterize / threshold, an OKLCh test chart (`input/colors_rec2020_f32.npy`); 27 new golden files; `testdata/graph/color_v1.json` shared with Kotlin |
| `:core:engine-native` (portable) | `color_ops.h/.cpp`: the 7 ops (cpuRef + GLSL), `whiteBalanceMatrix`, `solveNeutral`, `hslWeights`, OKLab, `targetedWeights`; graph JSON number arrays; mat3 / float-array uniforms in `GpuOpContext` |
| `:core:engine-native` (Android) | `samplePatch` (5 x 5 preview average through the ops before a given op), `sampleGrid` (loupe), region readback; C API + JNI |
| `:core:engine` | `EngineSession.sample / sampleGrid`, `NativeOp`, `ColorMath` (neutral solve, band weights) |
| `:core:model` | `OpParams` WhiteBalance, Vibrance, Saturation, HslMixer, Invert, Posterize, Threshold; `ColorAdjustments`, `HslBand`, `HslChannel`, `editOps` |
| `:core:ui` | 7 tools `done` (162 tools, 18 done) |
| `:feature:editor` | `ColorPanel` (temperature / tint gradient tracks, As shot, eyedropper, HSL channels with band dots, targeted toggle, invert / posterize / threshold), `EyedropperLoupe`, `TargetedHudOverlay`, `CanvasView` pick modes, ViewModel Color state with off-main-thread sampling |

Tests added: ctest `color_ops_test` (12); JVM `ColorAdjustmentsTest` (7), `ColorEditingTest`
(8), `CanvasGestureTest` +2, `ColorPanelScreenshots` (4 images), editor screenshots +2 per
width; instrumented `EngineGpuTest` +7.

## Decisions and deviations

1. **White balance model.** The scene white for (T, tint) is D65 moved by the Planckian
   locus offset of T from 6504 K (Krystek 1985, valid to ~8e-5 up to 15000 K and smooth to
   50000 K) and by tint × 0.05/150 duv along the locus normal (+ = greener scene, so the
   image turns magenta). Bradford adapts it to D65 in XYZ. So 6504 K / tint 0 is exactly
   no change, lower K cools the image, higher K warms it (Lightroom-like).
2. **Temperature slider** is linear in mireds (2000 K at the left, 50000 K at the right,
   default 6504 K at 72 %), so equal steps look equally warmer or cooler; shown in 50 K steps.
3. **"As shot"**: phone JPEGs / HEICs are already white-balanced and carry no scene white
   in EXIF, so As shot is the camera's own rendering (6504 K, tint 0 = no change). RAW
   AsShotNeutral arrives with RAW support (Stage 25).
4. **Eyedropper**: averages 5 × 5 pixels of the 2048 px preview, sampled before White balance,
   and solves (T, tint) so the patch becomes neutral (scan in mireds + ternary refinement;
   ctest: neutral to 0.2 %). It puts itself away after one pick; black is refused with a
   message. The loupe shows 9 × 9 original preview pixels 56 dp above the finger with the
   averaged 5 × 5 outlined.
5. **OKLCh everywhere** for hue work. Band centres are the OKLCh hues of saturated sRGB
   colours at the classic HSV band hues (0, 30, 60, 120, 180, 240, 270, 300 °):
   29.2, 52.8, 109.8, 142.5, 194.8, 264.1, 293.8, 328.4 °. Weights are raised cosines between
   neighbouring centres. Hue ±100 = ±30 °, saturation ±100 = chroma × (0 … 2), luminance
   ±100 = OKLab L × (1 ± 0.4).
6. **No hue jumps near grey** (brief, IF WRONG) is built in from the start: every hue-based
   effect fades in over OKLCh chroma 0 … 0.03, so greys are untouched (`grey_is_untouched_by_hue_tools`).
7. **Vibrance**: chroma × (1 + v (1 − s) (1 − 0.75 skin)), s = min(C / 0.3, 1). The brief's
   skin band "hue 15–45 °" is in classic HSV terms; in OKLCh that is 34.5–84.2 °, with 10 °
   soft edges; skin keeps 75 % protection.
8. **Invert / Posterize / Threshold** work on transfer-encoded values (sRGB curve extended by
   symmetry) so mid grey stays mid grey and posterize steps look even. Their slots are in
   the Color strip where the catalog reserved them since Stage 3 (R9: slots never move);
   they sit after the main tools ("Color > More" of the brief).
9. **Op order** (`editOps`): White balance → Light → Vibrance → Saturation → HSL → Invert →
   Posterize → Threshold. White balance runs before Highlights/Shadows, whose guided base is
   still computed on the unbalanced source luminance: after a strong WB change the
   highlight / shadow masks use the pre-balance brightness (a small, deliberate trade-off;
   the alternative, WB after the tone curves, would be colorimetrically wrong).
10. **Targeted adjustment**: one finger on the canvas in this mode samples the colour as the
    mixer sees it (ops before the mixer), and a vertical drag over the full canvas height
    moves the active channel (H, S or L) by 200, split over the bands by their weights.
11. **GPU tests of the step ops** (Posterize, Threshold) compare against goldens computed
    from the float16 values the GPU texture holds, on cases chosen so no pixel sits within
    2e-5 (encoded) of a step (`discontinuity_margin`); otherwise a float32 rounding anywhere
    could legitimately move a pixel a whole step.
12. Version 0.6.0 (8). Branch: session branch `ccr-a94fce54-a1j7s3`.

## Dependencies added (R10)

None.

## Golden images changed (R4)

No existing golden file changed. New: `input/colors_rec2020_f32.npy`, `colorops/*` (26).

Screenshots changed, and why:

| Screenshot | Why |
| --- | --- |
| `compact` / `expanded` `tool_panel` | The placeholder shot now uses Color Grading (still reserved); White balance has a real panel |
| `expanded` `category_color`, `category_favorites_pinned` | The side tool list no longer shows "Stage 6" for the done Color tools |

## Troubleshooting log (R11)

| Symptom | Root cause | Fix |
| --- | --- | --- |
| Graph JSON lost the mixer's bands | The native reader kept only number fields | Number arrays parsed and written back (after scalars, as Kotlin declares them) |
| CI emulator: HSL mixer GPU result far from the reference (mean 0.13) | The band uniforms, indexed dynamically inside a loop with `break`, were not applied by SwiftShader | Unrolled segment branches with constant indices (`hsl_shader_uses_constant_band_indices` guards it) |
| CI emulator: a few Posterize pixels off by one step | Pixels on a step edge: the GPU reads float16 input, the golden used float32 | GPU goldens of the step ops from float16-rounded input, on a case (chart, 12 levels) with every pixel ≥ 2e-5 from a step |
| A patch script applied only partly | Quoting error in the second half of the script | Re-applied the rest from separate files; verified each edit |

Plan B used: no.

## Proof

- CI run and APK artifact: see the Stage 6 report
- GPU vs reference: emulator reports (tag `LineOverEngineTest`)

## Phone checklist

POCO F7 Ultra, `lineover-<sha>.apk`:

- [ ] Color → White balance → pipette, then put a finger on a grey wall: the loupe shows the
      pixels above your finger; lift — the wall becomes neutral (the slider values change).
- [ ] Drag Temperature left / right: cooler / warmer, smoothly; Tint: green / magenta.
- [ ] HSL / Color Mixer → Saturation → Orange +100 on a portrait with blue sky: skin gets
      more saturated, the sky does not change.
- [ ] Target icon, then drag up on the sky: the blue band moves (the label shows which).
- [ ] Vibrance +100 on a portrait: muted colours gain more than skin; Saturation −100: grey.
- [ ] Invert, Posterize, Threshold: each switches on and off; the slider changes the result.
- [ ] Nothing in a grey area changes colour while you push the HSL sliders.

## Open questions (R12)

1. **Strength**: compare with another editor on the same photo if you have one — are White
   balance, Vibrance, Saturation or HSL too strong or too weak? Defaults: hue ±30 °,
   luminance ±40 %, tint ±0.05 duv at ±150.
2. (Stage 5) Highlights / Shadows halos? (Stage 4) Sample files for `testdata`?
