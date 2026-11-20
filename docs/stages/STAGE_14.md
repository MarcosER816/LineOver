# Stage 14 — Layer masks

## Goal

Layer masks:
- default white or black;
- density, feather (Gaussian), invert, disable (long-press the mask thumbnail), mask-only view;
- painted with a simple round brush (the full brush engine is Stage 18) or filled with a gradient.

Clipping masks: a layer clips to the layer below, and clipped layers can chain.

The vector mask slot stays reserved for Stage 24.

UX:
- tap the mask thumbnail to target the mask (accent outline);
- a red overlay toggle;
- a "paint white / black" swap button.

## Definition of Done

- [x] **Compositing goldens with masks and clipping.** `tools/golden/blend_reference.py` adds
      masks (rasterise, invert, feather, density, R16F rounding) and clipping to the Python
      compositor. ctest `mask_test` checks the C++ compositor against the scene
      `mask/scene_graph.json` within 1e-3:
      - at full size, at preview half size and in linear light;
      - masks on a raster layer: soft and hard strokes, feather 2.5, density 80 %;
      - masks on an adjustment layer: an inverted, feathered gradient;
      - masks on an isolated group (a black mask with a white stroke) and a pass-through group
        (a gradient);
      - a disabled mask;
      - clipping: a chain of two clipped layers (one an adjustment) on a masked base, a layer
        clipped to an adjustment layer, and a layer clipped to a hidden base.

      While building the scene, removing any one of these masks or clips was confirmed to
      change the result. The two exceptions, as expected, are the disabled mask and the hidden
      layer, which change nothing.
- [x] **Feather vs OpenCV GaussianBlur.** The goldens `mask/feather_{0.8,2.5,6.0}_f32.npy`
      are produced by `cv2.GaussianBlur(raw, (0, 0), sigma)` itself:
      - `masks::gaussianBlur` matches them within 1e-3;
      - the rendered (R16F) mask matches within half precision.
- [x] **No seams between tiles** (the brief's IF WRONG, built in). A heavily feathered,
      inverted mask rendered as four uneven regions equals the whole render bit for bit. The
      compositor scene rendered as four export tiles equals the whole render within 1e-6.
- [x] CI green; APK built.

## What was built

| Module | Content |
| --- | --- |
| `:core:engine-native` | `masks.h` / `masks.cpp`:<br>- rasteriser: round brush strokes as distance to a polyline with a hardness falloff, linear gradients, at buffer pixel centres<br>- OpenCV's Gaussian kernel (`ksize = round(8σ + 1) \| 1`) and separable blur<br>- R16F tiles (`MaskTiles`, 256², round-to-nearest-even half floats)<br>- `render`: rasterises a region plus the blur's reach, mirrored (`BORDER_REFLECT_101`) at the document edges, then inverts, feathers and applies density<br><br>`layers.cpp`:<br>- masks on raster, smart object, adjustment and group layers (a pass-through group fades per pixel)<br>- clipping bases and chains<br>- a cache of rendered masks<br>- `showMask` (grey or red overlay)<br><br>`graph.cpp`: `clip` and `mask` (`LayerMask`, `MaskPaint`), written only when not default<br>`Region` has the document size (export tiles)<br>Android: `nativeSetMaskView` (canvas only) |
| `:core:model` | `LayerMask`, `MaskPaint` (`Stroke`, `Gradient`; JSON `kind`)<br>`Layer.clip` / `Layer.mask`<br>`LayerMath.canClip` / `clippingBase`<br>`HistoryLabels` for mask and clip changes ("Sky: Paint mask", "Sky: Mask feather 40 px", "Clip Colour") |
| `:core:engine` | `EngineSession.setMaskView` |
| `:feature:editor` | `MaskSection`:<br>- Layer mask (Add, Hide all, Edit, Delete with Undo)<br>- Clip to the layer below<br>- the vector mask chip, still reserved<br><br>`MaskControls`: Brush / Gradient, swatch and swap, size / hardness / opacity, density, feather, Red overlay, Mask only, Invert, Fill white / black, Disable / Enable<br><br>`MaskThumbnail`: drawn from the paint; tap targets the mask, long-press disables it<br>A ↳ marker on clipped rows<br>`CanvasView`: `PickMode.Paint` (one finger paints while the mask is targeted)<br>`EditorLayersActions`: mask, clip, brush and painting actions, and the engine mask view |
| `:core:ui` | 2 layer tools `done` (162 tools, 77 done) |
| `:app` | 0.14.0 (versionCode 19) |

Tests added:
- ctest `mask_test` (7) with 10 golden files and the shared fixture `testdata/graph/edit_graph_masks.json`
- `LayersTest` (+2)
- `LayersEditingTest` (+4)
- the screenshot `sheet_layers_mask` (2 widths)
- `ExportRoundTripTest.layersCompositeIntoTheExport` (+ masks)

## Tools delivered

| id | Name | UI slot | Tests |
| --- | --- | --- | --- |
| `layers.layer_mask` | Layer mask | `panel/layers/mask/01` | mask_test (raster, feather vs OpenCV, seams, scene), LayersEditingTest (paint, gradient, settings, delete / undo), ExportRoundTripTest |
| `layers.clipping_mask` | Clipping mask | `panel/layers/mask/02` | mask_test (scene: chain, adjustment base, hidden base), LayersTest, LayersEditingTest |

`TOOL_CATALOG.md` updated: yes.

## Decisions and deviations

1. **A mask is its paint, rendered into R16F tiles.**
   - The graph holds the mask's base, invert, density, feather and its paint operations
     (strokes as polylines, gradients), in document pixels.
   - The engine renders any region of it into R16F (half-float) tiles, never 8 bit.
   - This keeps masks undoable, non-destructive and exact at preview, thumbnail and export
     resolution, without a separate pixel store or migration.
   - Rendered masks are cached (16), so moving a slider that is not the mask's does not
     re-render it.
   - "From selection" arrives with Stage 15.
2. **Brush.**
   - Coverage is the distance to the stroke's polyline, so there is no dab spacing and no
     build-up within a stroke. Each stroke blends towards its value by coverage × opacity.
   - The falloff is a smoothstep from `hardness` to the edge, with at least one buffer pixel
     of soft edge. A hard brush is therefore a continuous profile, so rendering never depends
     on rounding ties.
   - Points closer than a tenth of the radius are dropped while painting.
   - No pressure. The full brush engine is Stage 18.
3. **Painting on the canvas.**
   - One finger paints while the mask is targeted and the Layers panel is open; two fingers
     still move the photo.
   - Nothing is painted until the finger moves past the touch slop, or lifts (a dab), so a
     pinch never leaves a stray dab.
   - A stroke is one History step.
   - Each update re-renders the preview's mask on the CPU (the latest graph wins). The Stage
     18 brush engine will paint tiles incrementally.
4. **Gradient** replaces the mask with a linear ramp from the painting colour to the other,
   like Photoshop's gradient on a mask. Radial gradients are Stage 17.
5. **Feather** is the Gaussian's sigma in document pixels, with OpenCV's kernel size and
   `BORDER_REFLECT_101` at the document's edges. The preview scales sigma to its resolution.
   **Density:** `shown = 1 − density × (1 − mask)`. The order is invert, then feather, then
   density.
6. **Clipping.**
   - A clipped layer shows where its base covers: the base's own alpha × its mask × its
     opacity. Fill is excluded, as in Photoshop: a base at 0 % fill still clips.
   - Adjustment layers and pass-through groups cover everywhere; an isolated group covers
     where its result does.
   - A hidden base hides its clipped layers.
   - The photo is never a base: a layer directly above it cannot be clipped.
7. **Mask views** draw on the canvas composite while the mask is targeted:
   - Red overlay: Photoshop's 50 % red (rubylith) where the mask hides.
   - Mask only: the mask in grey.

   They never reach thumbnails or exports.
8. **Thumbnails** of masks are drawn in the UI from the paint (gradients, strokes, invert,
   disabled cross), without feather or hardness.
9. **The photo has no mask** (as Photoshop's Background layer). Masks belong to the layers
   above it.
10. **The phone checklist's "colour layer"** is an adjustment layer (for example Temperature
    or Saturation) or a photo layer. Solid colour fill layers are not part of LineOver yet.

## Dependencies added (R10)

None.

## Golden images changed (R4)

No existing golden file changed.

New golden files are under `testdata/golden/mask/`:
- the rasterised test mask (full size and half);
- its feather by OpenCV at three sigmas;
- the compositor scene with masks and clipping: graph JSON (gamma and linear) and renders
  (full size, half, linear).

New fixture: `testdata/graph/edit_graph_masks.json` (C++ and Kotlin round-trip it byte for
byte).

Screenshots changed, and why:

| Screenshot | Why |
| --- | --- |
| `editor/*/sheet_layers`, `menu_add_layer`, `sheet_layers_stack` | The Masks row of chips is replaced by the live controls: Layer mask (Add / Hide all), Clip to the layer below, and the reserved Vector mask chip |
| `editor/*/sheet_layers_mask` | New: a masked adjustment layer, its mask targeted (accent outline, brush controls, red overlay on), and a clipped layer (↳) above it |

## Troubleshooting log (R11)

| Symptom | Root cause | Fix |
| --- | --- | --- |
| `mask_test` mask view unchanged | The generated scene layers had no ids, so the lookup found the photo | `generate.py` names the scene's layers (`m0`, `m1`, …) |
| ViewModel test: the mask view never reached the engine | Targeting and view changes used `setState`, which skips the canvas sync | They go through `reduce` (the graph is unchanged, so there is no History step) |

Plan B used: no.

## Proof

- CI run and APK artifact: see the Stage 14 report

## Phone checklist

POCO F7 Ultra, `lineover-<sha>.apk`:

- [ ] Mask out the sky of an adjustment layer:
  1. Open a landscape photo → Layers → Add layer → Adjustment layer → Exposure (+1: everything
     brightens).
  2. Layer mask → Add. The mask thumbnail appears with an accent outline.
  3. Choose Gradient and drag from the top of the sky down to the horizon (black → white).
     The sky goes back to normal; the land stays brighter.
  4. Switch on Red overlay: the sky is tinted red. Then Mask only: the mask in grey.
  5. Brush: paint black over any bright spot left in the sky; tap the swap button and paint
     white to bring it back. Feather 40: the edge softens.
  6. Long-press the thumbnail: a red cross, and the mask is off. Long-press again: back on.
- [ ] Clip a colour layer to a photo layer:
  1. Add layer → Raster layer → pick a second photo.
  2. Add an Adjustment layer (Temperature, or Saturation) above it.
  3. Switch on "Clip to the layer below": the colour now changes only the second photo (↳ in
     its row), not the first.
- [ ] Export: the JPEG shows the masks and clipping as the editor does.

## Open questions (R12)

1. **Is the overlay colour readable on your photos?** Now Photoshop's 50 % red. Default if
   unanswered: keep red at 50 %. Another colour, or an opacity setting, can be added in the
   Masks panel.
