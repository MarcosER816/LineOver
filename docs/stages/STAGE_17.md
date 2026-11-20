# Stage 17 — Local masks

## Goal

The Masks category: local adjustments through masks.

**Mask components:** Linear gradient, Radial (feather, invert), Brush (size, feather, flow,
auto-mask with an edge-aware guided filter), Color range, Luminance range, Depth range (Depth
Anything V2 Small, downloaded on first use, or the depth map stored in the photo), and the AI
masks Subject, Sky, Background and People (Stage 16 models).

**Mask groups:** components combine with add / subtract / intersect.

**Each mask** has the full local adjustment set: exposure, contrast, highlights, shadows,
whites, blacks, temp, tint, saturation, texture, clarity, dehaze, sharpness, noise, colour tint.

**UX:** mask pins on the canvas (tap to select), the overlay toggle, Duplicate and invert, the
amount slider for the whole mask.

**Do not:** duplicate engine code; reuse the Stage 5-10 ops with a mask input.

## Definition of Done

- [x] **Mask-math tests.** `local_mask_test` (ctest):
  - the combine rules and identities (add / intersect commute, add 0 and intersect 1 keep M,
    subtract 1 clears);
  - invert twice is the identity; a group of one component is the component;
  - linear ∩ sky is exactly min(linear, sky);
  - Duplicate and invert covers the image exactly once (m + m' = 1 within two R16F roundings),
    feathered, for image-reading components too;
  - a masked adjustment is the lerp of the op by mask × amount (gamma blending, < 10⁻⁴);
  - an unmasked local adjustment of Highlights / Shadows, Clarity, Texture, Dehaze, Sharpening
    and Noise equals the background's own ops on the same pixels (< 10⁻⁵): the ops are reused;
  - tiles meet without seams; export tiles with `layerApron` equal the whole document (the
    same test with no apron fails at 0.25);
  - the component JSON round-trips (C++ and Kotlin, `edit_graph_local_masks.json`).
- [x] **Goldens for each mask generator.** `tools/golden/local_mask_reference.py` →
  `testdata/golden/localmask/`: linear, radial, inverted radial, brush, brush with auto-mask,
  colour range, luminance range, depth range, model raster, and a group (full and half scale).
  `local_mask_test` matches all of them within 10⁻³.
- [ ] **CI green.** See the Proof section (filled in at merge).
- [ ] **APK built.** See the Proof section.

## What was built

**Engine (C++)**
- `graph.h` `MaskComponent` and `LayerMask::components`, parsed and serialised (Kotlin's field
  order; defaults left out). `MaskPaint::color` for the auto-mask.
- `masks.cpp`: the components (`evaluateComponent`), `combine`, `rasterizeComponents`. Linear
  reuses the Stage 14 gradient, the brush the Stage 14 stroke, rasters the Stage 15 / 16
  raster and joint-bilateral code, the auto-mask the Stage 16 guided matte.
- `layers.cpp`: adjustment layers run `buildLayerOps` through `cpuRefRegion` with document
  fields (`LayerFields`, cached by the background graph); masks that read the image get the
  background and are not cached; `layerApron`; `showMask` takes the background.
- `android/engine.cpp`: export tiles are rendered with the layer apron and cropped;
  `upsampleMask` (a model mask → the grid by JBU + its colour guide); `photoLabAt` (OKLab × 100
  of the photo under the layers). JNI + `EngineSession`.

**`:core:ml` / `:core:io`**
- `ModelCatalog.DEPTH` (zip entry), `ModelStore` unpacks and verifies archive entries.
- `DepthEstimator` (LiteRT, NPU → GPU → CPU, map normalised near = 1).
- People: the sky model's ADE20K class 13 (`SmartModels.people`). Background: the subject
  inverted.
- `EmbeddedDepth`: the Dynamic Depth item of a JPEG's XMP container.

**`:core:model`**: `MaskComponent`, `CombineMode`, `AiMaskKind`, `LocalAdjustments` /
`LocalParam`, History labels for components.

**Editor**: `EditorLocalMaskActions`, `LocalMaskPanels.kt` (Masks sheet, mask panel, pins
layer), gesture routing, the overlay through the engine's mask view.

## Tools delivered

| Tool | Slot | Tests |
| --- | --- | --- |
| `masks.linear_gradient_mask` | `cat/Masks/01` | local_mask_test (linear), LocalMasksEditingTest (sky) |
| `masks.radial_gradient_mask` | `cat/Masks/02` | local_mask_test (radial, inverted), LocalMasksEditingTest (pins) |
| `masks.brush_mask` | `cat/Masks/03` | local_mask_test (brush, auto-mask), LocalMasksEditingTest |
| `masks.color_range_mask` | `cat/Masks/04` | local_mask_test (colour range), LocalMasksEditingTest |
| `masks.luminance_range_mask` | `cat/Masks/05` | local_mask_test (luminance range) |
| `masks.depth_range_mask` | `cat/Masks/06` | local_mask_test (depth range), ModelStoreTest (zip), EmbeddedDepthTest |
| `masks.subject_mask` | `cat/Masks/07` | local_mask_test (raster), LocalMasksEditingTest (subject) |
| `masks.sky_mask` | `cat/Masks/08` | LocalMasksEditingTest (linear ∩ sky) |
| `masks.background_mask` | `cat/Masks/09` | LocalMasksEditingTest |
| `masks.people_mask` | `cat/Masks/10` | LocalMasksEditingTest |

## Decisions and deviations

1. **A local mask is an adjustment layer.** Its mask's `components` replace base and paint;
   invert, feather and density still apply; its opacity is the Amount. This reuses the Stage
   13 compositor and Stage 14 masks, so local masks also show in the Layers panel.
2. **Combine rules** are the selection rules of Stage 15 (max, min(M, 1 − A), min). The first
   component always adds (it starts from black); removing the first makes the next one add.
3. **Temp and tint are relative.** Temp t moves white balance to 6504 K × 2^(t / 100), tint is
   added; colour tint is a Photo Filter (hue, density, luminosity kept).
4. **Fields for local Highlights / Shadows / Clarity / Texture / Dehaze** come from the
   document's background (after geometry), not from the unedited source, because layers live
   in document space. They are computed once per background graph and size, not per slider.
5. **Stored colours.** A colour range keeps the picked colours, and a brush's auto-mask the
   colour under each stroke's first point, so export tiles agree (nothing is sampled at render
   time outside the tile). The auto-mask: similarity 1 − ΔE/20 (OKLab × 100) to the best
   stroke colour, smoothed by the colour guided filter (radius ¼ of the brush, eps 10⁻³).
6. **AI masks** are upsampled by the engine to the editing grid against the image (the Stage
   16 JBU) and saved with that grid's colour guide; exports JBU them against the full photo.
7. **Depth range from the photo** is used when the JPEG has a Dynamic Depth item and the photo
   is not cropped or rotated (the map covers the uncropped frame); otherwise the model runs.
   Mark's test photos carry only an Ultra HDR gain map (checked by `EmbeddedDepthTest`), so on
   them the model is used.
8. **Pins** sit at the first component with a place (a gradient's centre, a brush's first
   point); masks made only of ranges and models stack near the top-left. Pins are picked by
   CanvasView's one-finger gesture (rule: gestures stay in CanvasView), within 3.5 % of the
   long edge.
9. **People** uses the sky model's ADE20K "person" class (13) rather than a new model: no extra
   download.

## Dependencies added (R10)

No new libraries. Downloaded model (not in the APK):

| Model | Size | Licence | SHA-256 |
| --- | --- | --- | --- |
| Depth Anything V2 Small, LiteRT float (Qualcomm AI Hub v0.67.0 zip) | 91 862 770 B zip, 98 917 860 B model | Weights Apache-2.0 (upstream README: "Depth-Anything-V2-Small model is under the Apache-2.0 license"; Hugging Face card apache-2.0). The AI Hub conversion's card says MIT. | zip `d3d91001…48cd1d32`, model `5cdbc034…e1786ef03d` |

The licence was checked before use (brief item 8): both licences are permissive, so the model
ships as a download and Depth range is not limited to photos with a depth map. Only the Small
model is Apache-2.0 (Base / Large are CC BY-NC 4.0 and are not used).

## Golden images changed (R4)

- New: 14 files under `testdata/golden/localmask/` (9 generators, the group full / half, the
  two test rasters, the graph). No existing golden changed.

Screenshots changed, and why:

| Screenshot | Why |
| --- | --- |
| `editor/*/sheet_masks` | The Masks sheet is live: new subtitle, the join buttons enabled once a mask is selected |
| `editor/*/menu_add_mask` | The Add menu lists the ten tools (model tools say where they come from) instead of "Stage 17" |
| `editor/*/category_masks` | The ten Masks tools are done (no "reserved" look) |
| `editor/*/sheet_masks_list` | New: two masks and their pins |
| `editor/*/panel_local_mask` | New: a mask's panel (components, overlay, amount, adjustments) |

## Troubleshooting log (R11)

| Problem | Cause | Fix |
| --- | --- | --- |
| A new mask on an unedited photo became the "photo" | The graph had no background layer yet | Masks are added with `LayerMath.add`, which creates it |
| Reachability: model tools had no slot after two taps | Their panel opened only after the model ran | The panel opens at once and says what is happening |

Plan B used: no.

## Proof

- CI run and APK artifact: see the Stage 17 report.

## Phone checklist

- [ ] **Brighten the subject only:** a portrait → Masks → Subject mask (the first time, ML
      Kit installs its model). A pin appears; set Exposure +0.7. Only the person brightens.
      Tap the eye: the red overlay covers everything but the person.
- [ ] **Darken the sky:** a landscape → Masks → Linear gradient mask; drag from the top edge to
      the horizon. Then tap **Intersect** and choose **Sky mask**. Set Exposure −1: only the
      sky darkens, most at the top, and buildings or trees reaching into the gradient stay as
      they were. Export at full size and check the sky edge.
- [ ] Radial: drag its centre, then drag elsewhere to size it; Feather 0 and 100.
- [ ] Brush with Auto mask on: paint over a wall next to the sky; the paint stops at the edge.
      Erase part of it.
- [ ] Color range: tap a red flower, then Range; Luminance range: From 70, To 100.
- [ ] Depth range: the first time, the 91.9 MB sheet (Apache-2.0); then To 100, From 60 keeps
      the foreground.
- [ ] **Duplicate and invert** a subject mask, set Exposure −1 on the copy: the background
      darkens, the subject is untouched.
- [ ] Amount 50 halves a mask's effect. Tap between two pins to switch masks.
- [ ] Settings → AI models: the depth model's row, its size and licence, delete.

## Open questions (R12)

1. **Is the pin UI clear?** (`panel_local_mask`, `sheet_masks_list` screenshots.) Default used:
   a white ring per mask, the selected one filled in accent; masks without a place stack at the
   top-left.
2. **The depth model's converted file is labelled MIT by Qualcomm AI Hub** while the weights
   are Apache-2.0. Both are permissive; the Settings row says Apache-2.0. OK?
3. (Still open from Stage 16) the subject IoU phone run, the sheet's clarity, Wi-Fi only by
   default, the hand masks; (from Stage 14) the red overlay's readability.
