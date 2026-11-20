# Stage 15 — Selections I

## Goal

The Select category, part 1.

**The selection.** A soft mask (R16F) plus a vector outline for the marching ants (marching
squares, animated dash shader).

**Tools:**
- Rectangle and Ellipse marquee (fixed ratio option);
- Lasso;
- Polygonal lasso;
- Magic wand: scanline flood fill in native code, tolerance in OKLab ΔE, contiguous, sample
  all layers;
- Color range: fuzziness in OKLab, add / subtract samples.

**Modes:** new, add, subtract, intersect.

**Ops:** invert, grow / shrink (distance transform), feather, smooth, border.

**Outputs:** selection → mask, → new layer, → fill (fill arrives in Stage 18).

**UX:**
- the options bar above the tool strip;
- tapping outside the selection deselects, with Undo;
- a loupe while lassoing;
- a live preview while moving the wand's tolerance slider.

## Definition of Done

- [x] **Flood fill vs OpenCV floodFill: identical.** ctest `selection_test` runs
      `selection::floodFillRange` on a test image (regions, a one-pixel bridge, a diagonal chain).
      It compares the result with `cv2.floodFill` (`FLOODFILL_FIXED_RANGE | FLOODFILL_MASK_ONLY`)
      in five cases (different seeds, ranges, 4- and 8-connectivity): every pixel equal (0
      differences). The test also checks that 4- and 8-connectivity really differ on that image.
      The magic wand uses the same scanline fill on its ΔE test.
- [x] **Distance-transform grow / shrink tests.** `selection::distanceTransform` (exact
      Felzenszwalb–Huttenlocher) matches `cv2.distanceTransform(DIST_L2, DIST_MASK_PRECISE)` on
      a blob with a hole and a spur, inside and outside, within 1e-3:
      - grow 2.5 / 6 px, shrink 1.5 / 4 px and border 3 px match the Python reference built on
        OpenCV's transform;
      - growing never removes a pixel and shrinking never adds one.
- [x] **Selection recipes vs the reference.** Two recipes on the scene match
      `tools/golden/selection_reference.py` at full size and at preview (half) scale, as R16F
      values within 1e-3:
      - rectangle, ellipse, lasso, an aliased polygon;
      - a contiguous wand, color range (add + subtract);
      - all four modes;
      - grow, feather, a non-contiguous wand, invert, shrink, border, smooth.

      The goldens assert a margin > 1e-3 from every tie (wand ΔE vs tolerance, 0.5 before
      grow / border), so compilers cannot round them differently.
- [x] CI green; APK built.

## What was built

| Module | Content |
| --- | --- |
| `:core:engine-native` | `selection.h` / `.cpp`:<br>- recipe evaluation on a grid (the composite at ≤ 2048 px), R16F result<br>- anti-aliased shapes (4 × 4 samples; polygons scanline, even-odd)<br>- modes<br>- wand (OKLab ΔE, scanline flood fill), color range<br>- exact EDT, grow / shrink / border / smooth / feather (OpenCV kernel)<br>- marching-squares outline<br><br>`graph.h` / `.cpp`: `SelectionOp`, `parseSelection`; `MaskPaint` "raster"<br>`masks.cpp`: saved selections as mask rasters (registry, bilinear)<br>Android: `Engine::setSelection` (render dispatcher), `selectionAt`, `selectionRaster`, outline upload and the ants dash shader (`GpuPipeline::drawAnts`), JNI |
| `:core:model` | `Selection`, `SelectionOp` (rect, ellipse, lasso, polygon, wand, color_range, all, invert, grow, feather, smooth, border; JSON `kind`), `SelectionMode`<br>`EditGraph.selection`<br>`MaskPaint.Raster`<br>History labels ("Magic wand", "Feather 10 px", "Deselect") |
| `:core:io` | `masks/MaskRasters` (`FileMaskRasters`: 16-bit, deflate, content-hash names) |
| `:core:engine` | `EngineSession.setSelection` / `setAntsPhase` / `selectionAt` / `selectionRaster` / `registerMaskRaster`, `SelectionRaster` |
| `:feature:editor` | `SelectionOptionsBar`: modes, anti-alias, All, Invert, Deselect<br>`SelectPanel` per tool:<br>- marquee shape and ratios<br>- lasso loupe<br>- polygon Close<br>- wand tolerance (live), contiguous, sample all layers<br>- color range fuzziness (live), add / subtract<br>- Amount + Grow / Shrink / Feather / Smooth / Border / Invert<br>- To mask / To new layer<br>- Fill (Stage 18)<br><br>`EditorSelectionActions`: gestures, tap outside → deselect with Undo, polygon taps, the engine queue (in order, latest wins), mask rasters<br>`CanvasView`: paint pick mode for selections, the loupe point, the ants ticker<br>The canvas graph leaves the selection out (no re-render for it) |
| `:feature:export` | Saved selections registered for export |
| `:core:ui` | 5 Select tools `done` (162 tools, 82 done) |
| `:app` | `AppContainer.maskRasters`; 0.15.0 (versionCode 20) |

Tests added:
- ctest `selection_test` (6) with 19 golden files
- `MaskRastersTest` (1)
- `SelectionEditingTest` (6)
- the screenshot `select_wand` (2 widths)

## Tools delivered

| id | Name | UI slot | Tests |
| --- | --- | --- | --- |
| `select.marquee` | Marquee | `cat/Select/01` | selection_test (rect, ellipse), SelectionEditingTest (drag, 1:1 ratio, modes, tap-outside deselect) |
| `select.lasso` | Lasso | `cat/Select/02` | selection_test (lasso), SelectionEditingTest |
| `select.polygonal_lasso` | Polygonal lasso | `cat/Select/03` | selection_test (polygon), SelectionEditingTest (taps, close on the first corner) |
| `select.magic_wand` | Magic wand | `cat/Select/04` | selection_test (flood fill = cv2.floodFill, wand recipes), SelectionEditingTest (tap, live tolerance) |
| `select.color_range` | Color range | `cat/Select/05` | selection_test (color range), SelectionEditingTest (add / subtract picks, to mask) |

`TOOL_CATALOG.md` updated: yes.

## Decisions and deviations

1. **The selection is a recipe in the edit.**
   - `EditGraph.selection` is a list of operations in document pixels, so every change is a
     History step and a project reopens with its selection.
   - The engine evaluates it on the render dispatcher, never the main thread (the flood fill
     runs there), in order, the latest request winning. It evaluates on a grid at the
     composite's resolution (≤ 2048 px long edge) into R16F.
   - The canvas graph leaves the selection out, so selecting never re-renders the image.
   - The wand and color range read the image when they are evaluated. After later edits a
     cached result is kept, but a reopened project re-evaluates them on the edited image.
2. **Selection → mask saves pixels.** The current selection raster is saved
   (`files/masks`, 16-bit, content-hash name) and named by a `MaskPaint.Raster`, which replaces
   the mask and is sampled bilinearly over the document. The mask therefore never changes when
   the image does.
   - With a layer selected, its mask becomes the selection.
   - With the photo selected, an Exposure adjustment layer (+1 EV) is added with the
     selection as its mask, so the effect is visible at once. Change its kind or value
     afterwards.
   - The selection is cleared once it is a mask (as in Photoshop).
   - Masks from selections have the selection's resolution (≤ 2048 px), upscaled bilinearly at
     export, and any feather is baked into them.
3. **Selection → new layer.**
   - A selected photo layer or smart object is duplicated with the selection as its mask.
   - With the photo selected, the photo is copied into a smart object: 1:1, with the photo's
     point ops, the selection as its mask. This works only while the photo has no crop or
     optics: a perspective or lens warp cannot be expressed by a layer placement yet (the
     panel says so).
4. **Fill** is shown and disabled until Stage 18.
5. **Shapes.**
   - Anti-aliased shapes cover 4 × 4 samples per pixel; polygons use the even-odd rule on
     half-open edges.
   - A lasso is closed automatically when the finger lifts. Polygonal-lasso corners are taps;
     a tap near the first corner (2 % of the long edge), or Close shape, ends it.
   - Fixed ratios are Free, 1:1, 4:3, 3:2 and 16:9.
6. **Wand and color range.**
   - Distance is OKLab ΔE × 100 from the seed pixel. Contiguous means 4-connected.
   - "Sample all layers" off reads the photo alone.
   - Color range: `1 − nearest add ΔE / fuzziness`, times `subtract ΔE / fuzziness`.
   - Moving the tolerance or fuzziness slider re-runs the last wand or color range click (one
     History step per drag).
7. **Ops.** One Amount slider (px) drives:
   - **Grow / shrink:** exact Euclidean distance from the edge, ±1 px of soft edge.
   - **Feather:** Gaussian sigma, OpenCV kernel.
   - **Smooth:** Gaussian of half the radius, steepened around 0.5.
   - **Border:** a band around the edge.
8. **Marching ants (the brief's IF WRONG, built in).**
   - The outline is traced once per selection change at the selection's (preview) resolution
     and uploaded as GL line segments.
   - Each frame draws them with a dash shader whose offset is the only thing animated, about
     12 times a second, only while a selection exists.
   - The dashes are 4 screen pixels along screen diagonals, black and white, so they read on
     any photo.
9. **Tap outside to deselect** works with the marquee and the lasso (wand and color range taps
   select). It asks the engine for the selection under the finger (< 0.5 counts as outside)
   and shows "Deselected" with Undo.
10. **Loupe** (the eyedropper's) follows the finger while lassoing. It is on by default (the
    brief's question) and can be switched off in the Lasso panel.

## Dependencies added (R10)

None. The test reference uses OpenCV (already in `tools/golden/requirements.txt`).

## Golden images changed (R4)

No existing golden file changed.

New files under `testdata/golden/selection/`:
- the flood-fill image and five `cv2.floodFill` results;
- the blob, its two distance transforms, grow ×2, shrink ×2 and border;
- two recipes (JSON) and their selections (full size and half).

Screenshots changed, and why:

| Screenshot | Why |
| --- | --- |
| `editor/*/selection_options`, `category_select` | The options bar is live: mode chips (New selected), Anti-alias, All, Invert / Deselect (off without a selection) instead of the Stage 3 placeholder text |
| `editor/*/select_wand` | New: the magic wand panel (tolerance, contiguous, sample all layers, the selection ops and outputs) with mode Add |

## Troubleshooting log (R11)

| Symptom | Root cause | Fix |
| --- | --- | --- |
| `selection_test` raster check expected the wrong value | The test computed the raster coordinate from the pixel index instead of its centre (105.5 → 10.05) | Fixed the expectation |
| ViewModel test: 3672.0002 for 3672 | 0.6 × 6120 in float | Test points at exact fractions (0.125, 0.625 …) |

Plan B used: no.

## Proof

- CI run and APK artifact: see the Stage 15 report

## Phone checklist

POCO F7 Ultra, `lineover-<sha>.apk`:

- [ ] Open a landscape → Select → Magic wand → tap the sky. Marching ants appear around it.
      Move Tolerance: the ants follow live. Turn Contiguous off to pick every similar blue.
- [ ] In the options bar choose Add → Lasso → draw around a bit of sky the wand missed (the
      loupe follows your finger above it). It joins the selection.
- [ ] Amount 10 → Feather. Then To mask: an Exposure layer appears in Layers with the sky as
      its mask (thumbnail). Change its exposure; only the sky changes.
- [ ] Marquee: drag a box; tap outside it → "Deselected · Undo".
- [ ] Polygonal lasso: tap corners, tap the first one to close. Color range: tap colours to
      add, switch to Subtract colours and tap to remove.
- [ ] Undo / redo step through every selection change.

## Open questions (R12)

1. **Should the loupe default to on?** Now on (lasso only). Default if unanswered: on.
