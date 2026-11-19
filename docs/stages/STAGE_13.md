# Stage 13 — Layers

## Goal

Photoshop-style layers:
- Layer types: Raster, Adjustment (any point op as a layer), Group (pass-through or isolated),
  Smart Object (an embedded source with its own edit). Text and Shape are reserved for Stages
  23 and 24.
- Per layer: opacity, fill, visibility, lock and blend mode, with all 27 Photoshop blend modes.
- Import a photo as a layer; duplicate, merge down and flatten, all non-destructive until export.
- A Layers panel with drag to reorder, swipe to delete, a live blend preview and the opacity
  slider in the row.

## Definition of Done

- [x] **Every blend mode has a golden test against a Python implementation of the formulas.**
      `tools/golden/blend_reference.py` implements the 27 modes from the W3C Compositing spec
      (non-premultiplied, `SetLum` / `ClipColor` / `SetSat` as in its pseudocode, luminosity
      0.3 / 0.59 / 0.11) and the standard definitions of the rest (Linear Burn a + b − 1, Linear
      Dodge a + b, Vivid / Linear / Pin Light, Hard Mix, Subtract, Divide, Darker / Lighter
      Color, Dissolve).
      - ctest `blend_test` compares every mode in both blending spaces with the goldens
        (float, max 1e-3), on a tie-free 17 × 17 grid of backdrop × source values with
        partial alpha.
      - The edge cases hold exactly: dodge(0, 1) = 0, burn(1, 0) = 1, dodge(0.5, 1) = 1,
        burn(0.5, 0) = 0, Linear Burn 0.7 + 0.6 − 1 = 0.3, Linear Dodge clipped at 1.
      - Names round-trip for all 27 modes, and an unknown name is refused.
- [x] **Compositor tests for groups and isolation.** `blend_test` renders a scene against
      `blend_reference.composite_document`:
      - the scene has raster layers with rotated placements, a smart object with its own
        edit, adjustment layers, groups with children, a hidden layer, a text layer, fill and
        several blend modes;
      - it renders full size, at preview scale (half) and in an export band (a region equals
        that crop of the whole within 1e-6);
      - the group is rendered isolated and pass-through, and the two results differ (> 0.1);
      - it also renders in linear light;
      - missing layer images are skipped and reported, and hidden layers are ignored.

      On the JVM:
      - `LayersTest` covers the layer tree operations (add, move, remove, duplicate, merge
        down, flatten, group / ungroup, restore at the old place) and the placement maths.
      - `LayersEditingTest` covers the panel's actions in the ViewModel.

      `ExportRoundTripTest.layersCompositeIntoTheExport` checks on the emulator that a photo
      layer reaches the exported file, and that hidden or 0 % layers change nothing.
- [x] CI green; APK built.

## What was built

| Module | Content |
| --- | --- |
| `:core:engine-native` | `layers.h` / `layers.cpp`:<br>- the 27 blend modes and `compositePixel` (W3C source-over with blend, straight alpha, in the gamma or linear blending space)<br>- the Dissolve pattern<br>- the compositor (`compositeLayers` over any document region: preview, thumbnail, export tile)<br>- the process-wide layer image registry (preview and export sets)<br><br>`graph.h` / `graph.cpp`:<br>- layer fields, written only when not default, in Kotlin's order<br>- `blendSpace`<br>- `buildOps` builds only the background layer; `buildPointOps` builds adjustment layers and smart objects<br><br>`renderGraphImage` (thumbnail): background, geometry, layers, finishing.<br><br>Android:<br>- the canvas composite (CPU, ≤ 2048 px) drawn as a texture<br>- export tiles composite the layers before finishing<br>- JNI `nativeRegisterLayerImage` / `nativeUnregisterLayerImage` / `nativeReleaseFullLayerImages` / `nativeRenderComposite` |
| `:core:model` | `Layer` gains type, name, visible, locked, opacity, fill, blend, isolated, source, sourceSize, transform and children; `EditGraph.blendSpace`<br>`LayerType`, `BlendMode` (27, in menu groups), `BlendSpace`<br>`LayerMath`: add, update, move, remove, parentOf / insertAt, duplicate, mergeDown, flattenAll, group / ungroup, sources, allOps<br>`LayerPlacement` (centre, scale, angle ↔ transform)<br>`AdjustmentKind`<br>`HistoryLabels` names layer changes ("Photo 2: Opacity 50 %", "Photo 2: Multiply", "Hide …") |
| `:core:io` | `layers/LayerSources`: imported photos are copied into `files/layers` under a SHA-256 name; only such names reach the file system |
| `:core:engine` | `EngineSession.registerLayerImage` (preview ≤ 2048 px, export ≤ 4096 px), `releaseFullLayerImages`; `setGraph` renders the canvas composite when the graph has layers |
| `:feature:editor` | `LayersPanel`:<br>- add menu: raster, adjustment (9 kinds or "From current edit"), group, smart object; Text and Shape disabled with their stage<br>- rows: drag handle, eye, kind icon, name and caption, lock; the selected row carries its opacity slider<br>- blend list with a live preview<br>- fill<br>- group isolation<br>- adjustment slider<br>- placement and the smart object's own edit<br>- Duplicate, Merge down, Group / Ungroup, Flatten, Delete<br>- the blending space<br><br>`EditorLayersActions` (registers layer images before a graph names them); `EditorState.selectedLayerId` |
| `:feature:export` | `ExportRunner.render` registers the layer images at export size and releases them afterwards; `ExportEnvironment.layers` |
| `:core:designsystem` | 4 icons (visibility off, lock, lock open, drag handle) |
| `:core:ui` | 7 layer tools `done` (162 tools, 75 done) |
| `:app` | `AppContainer.layerSources`; 0.13.0 (versionCode 18) |

Tests added:
- ctest `blend_test` (6) with 66 golden files
- `LayersTest` (10)
- `LayerSourcesTest` (2)
- `LayersEditingTest` (8)
- Layers screenshots (`sheet_layers_stack`, 2 widths)
- instrumented `ExportRoundTripTest.layersCompositeIntoTheExport`

## Tools delivered

| id | Name | UI slot | Tests |
| --- | --- | --- | --- |
| `layers.raster_layer` | Raster layer | `panel/layers/add/01` | blend_test (scene), LayersEditingTest (import, fit, registration), ExportRoundTripTest |
| `layers.adjustment_layer` | Adjustment layer | `panel/layers/add/02` | blend_test (scene), LayersEditingTest (kinds, From current edit), LayersTest |
| `layers.group` | Group | `panel/layers/add/03` | blend_test (isolated vs pass-through), LayersTest (group / ungroup, merge, flatten) |
| `layers.smart_object` | Smart object | `panel/layers/add/04` | blend_test (own edit, rotated placement), LayersTest (placement) |
| `layers.opacity` | Opacity | `panel/layers/props/01` | blend_test, LayersEditingTest (one step per drag, locked), ExportRoundTripTest (0 %) |
| `layers.fill` | Fill | `panel/layers/props/02` | blend_test (scene with fill) |
| `layers.blend_modes` | Blend modes (27) | `panel/layers/props/03` | blend_test (27 × 2 spaces, edge cases), LayersEditingTest (live preview, commit) |

`TOOL_CATALOG.md` updated: yes.

## Decisions and deviations

1. **Where layers live.**
   - The photo (background layer, with its develop edit and geometry) defines the document.
   - Layers composite in output (document) space after the geometry, in document pixels.
     Cropping the photo therefore does not move the layers, as in Photoshop.
   - Post-crop vignette and grain are finishing, applied after the layers.
   - Layers composite in the order listed in the panel.
2. **Blending maths.**
   - W3C source-over with blend on non-premultiplied colour (nothing is premultiplied before
     blending).
   - The whole formula runs in the blending space. "Gamma" is the sRGB curve on the working
     primaries, odd-symmetric (the default, to match Photoshop). "Linear" uses the working
     values themselves.
   - Every mode except Normal and Dissolve clamps its inputs to 0..1, so values above 1 are
     kept only by Normal / Dissolve.
   - Effective alpha = layer alpha × opacity × fill.
   - Dissolve uses a fixed 256 × 256 pattern (LCG) at document pixel coordinates, so the
     preview, the export and the reference agree.
3. **Fill** scales the content's alpha exactly like opacity for now. There are no layer styles
   yet; when they arrive, fill will leave them alone and opacity will not.
4. **Groups.**
   - Isolated groups composite their children onto transparency and blend the result as one
     layer.
   - Pass-through groups (the default) apply their children to the backdrop. Opacity then
     fades between before and after in the blending space.
   - Merge down and flatten make pass-through groups ("Photo 2 + Saturation", "Flattened"):
     they look exactly like the separate layers, can be ungrouped again, and are combined
     only when exporting.
   - Nothing is ever merged into the photo, so merge down is off for the lowest layer.
5. **Raster layers are imported photos.**
   - A raster layer is an imported photo with a placement (size, angle, position), resampled
     once, bilinear, from the source.
   - Painted pixel tiles arrive with the brush stages. Until then a raster layer and a smart
     object differ only in that the smart object has its own edit (Exposure, Contrast,
     Saturation).
   - Layers hold the point ops only (the regional ops — Highlights / Shadows, Clarity,
     Texture, Dehaze, Sharpen, Noise reduction — are skipped; the export logs them). The same holds
     for adjustment layers: "From current edit" copies only the photo's point ops.
6. **Storage.**
   - Imported photos are copied into the app's own storage (`files/layers`, SHA-256 names, the
     same photo stored once). The graph names the copy, never the person's original.
   - A `.loproj` does not yet embed those images. A project moved to another device shows its
     layers as missing (skipped, the edit kept) until they are imported again.
   - Export uses layer images up to 4096 px long edge. A 50 MP layer in float would need
     800 MB; `sourceSize` maps any resolution into the document.
7. **Canvas.**
   - A layered document shows a CPU composite of the 2048 px preview, rendered on the render
     dispatcher after each edit, drawn as one texture over the canvas.
   - The sharp 1:1 tiles are not drawn over it, so zooming past 100 % shows the preview's
     resolution until the GPU compositor (planned with masks). Export is always full
     resolution.
   - "Hold for original" shows the photo without layers.
   - The histogram still reads the photo without layers.
8. **Reorder.**
   - Dragging a row's handle moves the layer among its siblings, with a haptic tick at each
     new place and a confirmation on drop.
   - Moving into or out of a group goes through Group / Ungroup.
9. **Delete.** Swiping a row left past 96 dp deletes it, with "Deleted …" and Undo; Undo puts the
   layer back at its old place (`LayerMath.insertAt`). Locked layers refuse deletes and edits
   (their eye and lock still work).
10. **Blend preview.**
    - While the blend list scrolls, the mode under the middle of the list is shown on the
      canvas, through the same compare graph that Stage 11's long-press uses: no History
      steps, and the edit itself is unchanged.
    - A tap applies a mode (one History step); closing the list goes back to the layer's own
      mode.

## Dependencies added (R10)

None. The four new icons are Material Icons paths (Apache-2.0, Google), like the existing
Material Symbols.

## Golden images changed (R4)

No existing golden file changed. New golden files are under `testdata/golden/blend/`:
- 27 modes × 2 blending spaces;
- their backdrop / source grids;
- the layer source image;
- the scene graphs and their renders (gamma, pass-through, linear, half).

Screenshots changed, and why:

| Screenshot | Why |
| --- | --- |
| `editor/*/sheet_layers` | The Layers panel is real: the photo row (selected, with its fixed 100 % opacity), blend mode, fill, operations, blending space |
| `editor/*/menu_add_layer` | The add menu has captions, and Text / Shape show their stages (disabled) |
| `editor/*/sheet_layers_stack` | New: a photo layer (selected, opacity in the row), an isolated group with an adjustment and a hidden, locked smart object |

## Troubleshooting log (R11)

| Symptom | Root cause | Fix |
| --- | --- | --- |
| Hue / Color modes off against the reference in saturated colours | `ClipColor` recomputed the max after clipping the min; the spec uses the original `l`, `n`, `x` for both steps | Follow the spec pseudocode (the brief's IF WRONG step) |
| JVM stack overflow in `LayerMath.group` | `mapTree` applied the wrapper and then descended into the new group, wrapping the layer again | Map the children first, then the layer |

Plan B used: no.

## Proof

- CI run and APK artifact: see the Stage 13 report

## Phone checklist

POCO F7 Ultra, `lineover-<sha>.apk`:

- [ ] Open a photo → Layers (top bar) → Add layer → Raster layer → pick a second photo. It
      appears fitted over the first, and the row says "Photo 2 · Normal · 100 %".
- [ ] Blend mode → scroll the list slowly: the canvas changes to each mode as it passes the
      middle (a light tick each time). Tap Multiply: the list closes and the row says Multiply.
      Cycle through Screen, Overlay, Difference, Color and Luminosity the same way.
- [ ] Drag the Opacity slider in the row to 50 %: the photo shows through by half. Undo takes
      the whole drag back in one step.
- [ ] Add an Adjustment layer → Saturation: everything below turns grey. Drag its handle under
      Photo 2 (a tick at each place): now only the photo below is grey.
- [ ] Swipe a row left: it is deleted with "Deleted … · Undo"; Undo brings it back in place.
- [ ] Group, then switch Isolated on and off with a blend mode on the group: the result changes.
- [ ] Export: the exported JPEG shows the layers exactly as the editor does.

## Open questions (R12)

1. **Do you want linear-light blending as the default instead of gamma?** Now gamma-encoded,
   which matches Photoshop; each project can switch with Layers → Blending → Linear light.
   Default if unanswered: keep gamma.
