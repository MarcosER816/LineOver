# Tool catalog

Every tool LineOver will ever have, with the UI slot reserved for it. **162 tools, 77 done.**

- **id**: stable identifier (`<group>.<tool>`), used in code, favourites and project files. Never renamed.
- **UI slot**: where the tool lives. Slots are reserved now and are never moved (rule R9); a stage only *fills* slots.
- **Status**: `reserved` (slot exists, tool not built) or `done` (built, tested, merged). Nothing else.

## Category bar

The category bar always shows these 18 categories in this order (also `ToolCategory` in `:core:model`):

1 Favorites · 2 Presets · 3 Auto · 4 Light · 5 Color · 6 Effects · 7 Detail · 8 Optics · 9 Crop · 10 Masks · 11 Select · 12 Retouch · 13 Paint · 14 Filters · 15 Liquify · 16 Text · 17 Shapes · 18 AI

## Design system

Components of `:core:designsystem` that every tool is built from (Stage 1). Not tools,
so they have no UI slot and are not counted above.

| id | Name | Stage | Status |
| --- | --- | --- | --- |
| `ds.theme` | LineOverTheme (colours, type, shapes, motion, reduced motion) | 1 | done |
| `ds.haptics` | LoHaptics | 1 | done |
| `ds.icons` | LoIcons (Material Symbols Rounded) | 1 | done |
| `ds.slider` | LoSlider | 1 | done |
| `ds.tool_button` | LoToolButton | 1 | done |
| `ds.category_bar` | LoCategoryBar | 1 | done |
| `ds.panel` | LoPanel | 1 | done |
| `ds.icon_button` | LoIconButton | 1 | done |
| `ds.chip` | LoChip | 1 | done |
| `ds.segmented` | LoSegmented | 1 | done |
| `ds.switch_row` | LoSwitchRow | 1 | done |
| `ds.color_wheel` | LoColorWheel | 1 | done |
| `ds.curve_editor_placeholder` | LoCurveEditorPlaceholder | 1 | done |
| `ds.toast` | LoToast / undo snackbar | 1 | done |
| `ds.empty_state` | LoEmptyState | 1 | done |
| `ds.placeholder_tool` | LoPlaceholderTool | 1 | done |
| `ds.gallery` | ComponentGalleryScreen (debug only) | 1 | done |

## Slot naming

| Slot | Meaning |
| --- | --- |
| `cat/<Category>/<nn>` | Position `nn` in the tool strip of a category |
| `canvas/overlay/<name>` | Overlay drawn on top of the canvas, toggled from the top bar |
| `topbar/<name>` | Action in the editor top bar |
| `panel/history/…`, `panel/layers/…` | Section of the History or Layers panel (side panel on wide screens) |
| `sheet/export/…` | Section of the export sheet |
| `home/…` | Entry on the Home screen |
| `settings/…` | Entry on the Settings screen |

## Favorites

The Favorites strip shows the tools the user pinned; it has no tools of its own.

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `favorites.favorites` | Favorites | Favorites | `cat/Favorites/01` | 28 | reserved |

## Presets

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `presets.presets` | Presets | Presets | `cat/Presets/01` | 11 | done |
| `presets.profiles` | Profiles | Presets | `cat/Presets/02` | 11 | done |
| `presets.3d_luts` | 3D LUTs | Presets | `cat/Presets/03` | 11 | done |
| `presets.copy_paste_settings` | Copy/paste settings | Presets | `cat/Presets/04` | 11 | done |

## Auto

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `auto.auto` | Auto | Auto | `cat/Auto/01` | 11 | done |

## Light

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `light.exposure` | Exposure | Light | `cat/Light/01` | 5 | done |
| `light.contrast` | Contrast | Light | `cat/Light/02` | 5 | done |
| `light.highlights` | Highlights | Light | `cat/Light/03` | 5 | done |
| `light.shadows` | Shadows | Light | `cat/Light/04` | 5 | done |
| `light.whites` | Whites | Light | `cat/Light/05` | 5 | done |
| `light.blacks` | Blacks | Light | `cat/Light/06` | 5 | done |
| `light.brightness` | Brightness | Light | `cat/Light/07` | 5 | done |
| `light.curves_point` | Curves (point) | Light | `cat/Light/08` | 7 | done |
| `light.curves_parametric` | Curves (parametric) | Light | `cat/Light/09` | 7 | done |
| `light.levels` | Levels | Light | `cat/Light/10` | 7 | done |

## Light: canvas overlays

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `light.histogram` | Histogram | Light | `canvas/overlay/histogram` | 5 | done |
| `light.clipping_warnings` | Clipping warnings | Light | `canvas/overlay/clipping` | 5 | done |

## Color

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `color.white_balance` | White balance | Color | `cat/Color/01` | 6 | done |
| `color.vibrance` | Vibrance | Color | `cat/Color/02` | 6 | done |
| `color.saturation` | Saturation | Color | `cat/Color/03` | 6 | done |
| `color.hsl_color_mixer` | HSL / Color Mixer | Color | `cat/Color/04` | 6 | done |
| `color.invert` | Invert | Color | `cat/Color/05` | 6 | done |
| `color.posterize` | Posterize | Color | `cat/Color/06` | 6 | done |
| `color.threshold` | Threshold | Color | `cat/Color/07` | 6 | done |
| `color.color_grading` | Color Grading | Color | `cat/Color/08` | 7 | done |
| `color.split_toning` | Split toning | Color | `cat/Color/09` | 7 | done |
| `color.color_balance` | Color Balance | Color | `cat/Color/10` | 7 | done |
| `color.selective_color` | Selective Color | Color | `cat/Color/11` | 7 | done |
| `color.channel_mixer` | Channel Mixer | Color | `cat/Color/12` | 7 | done |
| `color.black_and_white` | Black & White | Color | `cat/Color/13` | 7 | done |
| `color.photo_filter` | Photo Filter | Color | `cat/Color/14` | 7 | done |
| `color.gradient_map` | Gradient Map | Color | `cat/Color/15` | 7 | done |
| `color.calibration` | Calibration | Color | `cat/Color/16` | 7 | done |

## Effects

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `effects.texture` | Texture | Effects | `cat/Effects/01` | 10 | done |
| `effects.clarity` | Clarity | Effects | `cat/Effects/02` | 10 | done |
| `effects.dehaze` | Dehaze | Effects | `cat/Effects/03` | 10 | done |
| `effects.vignette` | Vignette | Effects | `cat/Effects/04` | 10 | done |
| `effects.grain` | Grain | Effects | `cat/Effects/05` | 10 | done |

## Detail

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `detail.sharpen` | Sharpen | Detail | `cat/Detail/01` | 10 | done |
| `detail.noise_reduction` | Noise reduction | Detail | `cat/Detail/02` | 10 | done |

## Optics

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `optics.upright` | Upright | Optics | `cat/Optics/01` | 9 | done |
| `optics.lens_distortion` | Lens distortion | Optics | `cat/Optics/02` | 9 | done |
| `optics.chromatic_aberration` | Chromatic aberration | Optics | `cat/Optics/03` | 9 | done |
| `optics.lens_vignetting` | Lens vignetting | Optics | `cat/Optics/04` | 9 | done |

## Crop

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `crop.crop` | Crop | Crop | `cat/Crop/01` | 9 | done |
| `crop.aspect_ratios` | Aspect ratios | Crop | `cat/Crop/02` | 9 | done |
| `crop.straighten` | Straighten | Crop | `cat/Crop/03` | 9 | done |
| `crop.rotate` | Rotate | Crop | `cat/Crop/04` | 9 | done |
| `crop.flip` | Flip | Crop | `cat/Crop/05` | 9 | done |
| `crop.perspective_crop` | Perspective crop | Crop | `cat/Crop/06` | 9 | done |

## Masks (local adjustments)

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `masks.linear_gradient_mask` | Linear gradient mask | Masks | `cat/Masks/01` | 17 | reserved |
| `masks.radial_gradient_mask` | Radial gradient mask | Masks | `cat/Masks/02` | 17 | reserved |
| `masks.brush_mask` | Brush mask | Masks | `cat/Masks/03` | 17 | reserved |
| `masks.color_range_mask` | Color range mask | Masks | `cat/Masks/04` | 17 | reserved |
| `masks.luminance_range_mask` | Luminance range mask | Masks | `cat/Masks/05` | 17 | reserved |
| `masks.depth_range_mask` | Depth range mask | Masks | `cat/Masks/06` | 17 | reserved |
| `masks.subject_mask` | Subject mask | Masks | `cat/Masks/07` | 17 | reserved |
| `masks.sky_mask` | Sky mask | Masks | `cat/Masks/08` | 17 | reserved |
| `masks.background_mask` | Background mask | Masks | `cat/Masks/09` | 17 | reserved |
| `masks.people_mask` | People mask | Masks | `cat/Masks/10` | 17 | reserved |

## Select

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `select.marquee` | Marquee | Select | `cat/Select/01` | 15 | reserved |
| `select.lasso` | Lasso | Select | `cat/Select/02` | 15 | reserved |
| `select.polygonal_lasso` | Polygonal lasso | Select | `cat/Select/03` | 15 | reserved |
| `select.magic_wand` | Magic wand | Select | `cat/Select/04` | 15 | reserved |
| `select.color_range` | Color range | Select | `cat/Select/05` | 15 | reserved |
| `select.magnetic_lasso` | Magnetic lasso | Select | `cat/Select/06` | 16 | reserved |
| `select.quick_selection` | Quick selection | Select | `cat/Select/07` | 16 | reserved |
| `select.object_select` | Object select | Select | `cat/Select/08` | 16 | reserved |
| `select.subject_select` | Subject select | Select | `cat/Select/09` | 16 | reserved |
| `select.sky_select` | Sky select | Select | `cat/Select/10` | 16 | reserved |
| `select.refine_edge` | Refine edge | Select | `cat/Select/11` | 16 | reserved |

## Retouch

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `retouch.spot_healing` | Spot healing | Retouch | `cat/Retouch/01` | 19 | reserved |
| `retouch.healing_brush` | Healing brush | Retouch | `cat/Retouch/02` | 19 | reserved |
| `retouch.clone_stamp` | Clone stamp | Retouch | `cat/Retouch/03` | 19 | reserved |
| `retouch.patch` | Patch | Retouch | `cat/Retouch/04` | 19 | reserved |
| `retouch.red_eye` | Red-eye | Retouch | `cat/Retouch/05` | 19 | reserved |
| `retouch.dodge` | Dodge | Retouch | `cat/Retouch/06` | 19 | reserved |
| `retouch.burn` | Burn | Retouch | `cat/Retouch/07` | 19 | reserved |
| `retouch.sponge` | Sponge | Retouch | `cat/Retouch/08` | 19 | reserved |
| `retouch.blur` | Blur | Retouch | `cat/Retouch/09` | 19 | reserved |
| `retouch.sharpen` | Sharpen | Retouch | `cat/Retouch/10` | 19 | reserved |
| `retouch.smudge` | Smudge | Retouch | `cat/Retouch/11` | 19 | reserved |
| `retouch.content_aware_fill` | Content-aware fill | Retouch | `cat/Retouch/12` | 20 | reserved |
| `retouch.ai_remove` | AI remove | Retouch | `cat/Retouch/13` | 20 | reserved |
| `retouch.content_aware_scale` | Content-aware scale | Retouch | `cat/Retouch/14` | 20 | reserved |

## Paint

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `paint.brush` | Brush | Paint | `cat/Paint/01` | 18 | reserved |
| `paint.pencil` | Pencil | Paint | `cat/Paint/02` | 18 | reserved |
| `paint.eraser` | Eraser | Paint | `cat/Paint/03` | 18 | reserved |
| `paint.mixer_brush` | Mixer brush | Paint | `cat/Paint/04` | 18 | reserved |
| `paint.gradient` | Gradient | Paint | `cat/Paint/05` | 18 | reserved |
| `paint.paint_bucket` | Paint bucket | Paint | `cat/Paint/06` | 18 | reserved |
| `paint.pattern` | Pattern | Paint | `cat/Paint/07` | 18 | reserved |
| `paint.color_picker` | Color picker | Paint | `cat/Paint/08` | 18 | reserved |

## Filters

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `filters.gaussian_blur` | Gaussian blur | Filters | `cat/Filters/01` | 21 | reserved |
| `filters.box_blur` | Box blur | Filters | `cat/Filters/02` | 21 | reserved |
| `filters.motion_blur` | Motion blur | Filters | `cat/Filters/03` | 21 | reserved |
| `filters.radial_blur` | Radial blur | Filters | `cat/Filters/04` | 21 | reserved |
| `filters.lens_blur` | Lens blur | Filters | `cat/Filters/05` | 21 | reserved |
| `filters.surface_blur` | Surface blur | Filters | `cat/Filters/06` | 21 | reserved |
| `filters.tilt_shift` | Tilt-shift | Filters | `cat/Filters/07` | 21 | reserved |
| `filters.bokeh` | Bokeh | Filters | `cat/Filters/08` | 21 | reserved |
| `filters.high_pass` | High pass | Filters | `cat/Filters/09` | 21 | reserved |
| `filters.emboss` | Emboss | Filters | `cat/Filters/10` | 21 | reserved |
| `filters.distort` | Distort | Filters | `cat/Filters/11` | 21 | reserved |
| `filters.noise` | Noise | Filters | `cat/Filters/12` | 21 | reserved |
| `filters.pixelate` | Pixelate | Filters | `cat/Filters/13` | 21 | reserved |
| `filters.stylize` | Stylize | Filters | `cat/Filters/14` | 21 | reserved |
| `filters.filter_gallery` | Filter gallery | Filters | `cat/Filters/15` | 21 | reserved |

## Liquify

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `liquify.liquify` | Liquify | Liquify | `cat/Liquify/01` | 22 | reserved |
| `liquify.face_aware_liquify` | Face-aware liquify | Liquify | `cat/Liquify/02` | 22 | reserved |
| `liquify.free_transform` | Free transform | Liquify | `cat/Liquify/03` | 22 | reserved |
| `liquify.warp` | Warp | Liquify | `cat/Liquify/04` | 22 | reserved |
| `liquify.puppet_warp` | Puppet warp | Liquify | `cat/Liquify/05` | 22 | reserved |

## Text

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `text.text` | Text | Text | `cat/Text/01` | 23 | reserved |

## Shapes

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `shapes.shapes` | Shapes | Shapes | `cat/Shapes/01` | 24 | reserved |
| `shapes.pen` | Pen | Shapes | `cat/Shapes/02` | 24 | reserved |
| `shapes.curvature_pen` | Curvature pen | Shapes | `cat/Shapes/03` | 24 | reserved |
| `shapes.path_selection` | Path selection | Shapes | `cat/Shapes/04` | 24 | reserved |

## AI

Every AI tool downloads its model on first use (size and licence shown, SHA-256 verified, deletable in Settings). Nothing is bundled in the APK (R0 / D3).

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `ai.background_removal` | Background removal | AI | `cat/AI/01` | 26 | reserved |
| `ai.super_resolution` | Super resolution | AI | `cat/AI/02` | 26 | reserved |
| `ai.ai_denoise` | AI denoise | AI | `cat/AI/03` | 26 | reserved |
| `ai.sky_replacement` | Sky replacement | AI | `cat/AI/04` | 26 | reserved |
| `ai.portrait_retouch` | Portrait retouch | AI | `cat/AI/05` | 26 | reserved |
| `ai.depth_blur` | Depth blur | AI | `cat/AI/06` | 26 | reserved |
| `ai.generative_fill` | Generative fill | AI | `cat/AI/07` | 26 | reserved |

## History

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `history.undo_redo` | Undo / redo | — (shell) | `topbar/undo-redo` | 8 | done |
| `history.history_list` | History list | — (shell) | `panel/history/list` | 8 | done |
| `history.snapshots` | Snapshots | — (shell) | `panel/history/snapshots` | 8 | done |
| `history.versions` | Versions | — (shell) | `panel/history/versions` | 8 | done |
| `history.before_after` | Before / after | — (shell) | `topbar/before-after` | 5 | done |
| `history.project_file` | Project file (.loproj) | — (shell) | `home/projects` | 8 | done |

## Export

Exports are written to `Pictures/LineOver`.

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `export.jpeg` | JPEG | — (export sheet) | `sheet/export/format/01` | 12 | done |
| `export.png` | PNG | — (export sheet) | `sheet/export/format/02` | 12 | done |
| `export.webp` | WebP | — (export sheet) | `sheet/export/format/03` | 12 | done |
| `export.heif` | HEIF | — (export sheet) | `sheet/export/format/04` | 12 | done |
| `export.avif` | AVIF | — (export sheet) | `sheet/export/format/05` | 12 | done |
| `export.tiff` | TIFF | — (export sheet) | `sheet/export/format/06` | 12 | done |
| `export.ultra_hdr` | Ultra HDR | — (export sheet) | `sheet/export/format/07` | 12 | done |
| `export.resize` | Resize | — (export sheet) | `sheet/export/options/01` | 12 | done |
| `export.metadata` | Metadata | — (export sheet) | `sheet/export/options/02` | 12 | done |
| `export.watermark` | Watermark | — (export sheet) | `sheet/export/options/03` | 12 | done |
| `export.icc_profile` | ICC profile | — (export sheet) | `sheet/export/options/04` | 12 | done |

## Layers

The 27 blend modes: Normal, Dissolve, Darken, Multiply, Color Burn, Linear Burn, Darker Color, Lighten, Screen, Color Dodge, Linear Dodge (Add), Lighter Color, Overlay, Soft Light, Hard Light, Vivid Light, Linear Light, Pin Light, Hard Mix, Difference, Exclusion, Subtract, Divide, Hue, Saturation, Color, Luminosity.

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `layers.raster_layer` | Raster layer | — (layers panel) | `panel/layers/add/01` | 13 | done |
| `layers.adjustment_layer` | Adjustment layer | — (layers panel) | `panel/layers/add/02` | 13 | done |
| `layers.group` | Group | — (layers panel) | `panel/layers/add/03` | 13 | done |
| `layers.smart_object` | Smart object | — (layers panel) | `panel/layers/add/04` | 13 | done |
| `layers.opacity` | Opacity | — (layers panel) | `panel/layers/props/01` | 13 | done |
| `layers.fill` | Fill | — (layers panel) | `panel/layers/props/02` | 13 | done |
| `layers.blend_modes` | Blend modes (27) | — (layers panel) | `panel/layers/props/03` | 13 | done |
| `layers.layer_mask` | Layer mask | — (layers panel) | `panel/layers/mask/01` | 14 | done |
| `layers.clipping_mask` | Clipping mask | — (layers panel) | `panel/layers/mask/02` | 14 | done |
| `layers.vector_mask` | Vector mask | — (layers panel) | `panel/layers/mask/03` | 24 | reserved |

## RAW

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `raw.raw_development` | RAW development | — (import) | `home/import/raw` | 25 | reserved |

## Merge

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `merge.hdr_merge` | HDR merge | — (home) | `home/merge/01` | 27 | reserved |
| `merge.panorama` | Panorama | — (home) | `home/merge/02` | 27 | reserved |
| `merge.focus_stacking` | Focus stacking | — (home) | `home/merge/03` | 27 | reserved |

## Workflow

| id | Name | Category | UI slot | Stage | Status |
| --- | --- | --- | --- | --- | --- |
| `workflow.batch` | Batch | — (shell) | `home/batch` | 28 | reserved |
| `workflow.sync_settings` | Sync settings | — (shell) | `home/sync-settings` | 28 | reserved |
| `workflow.tool_search` | Tool search | — (shell) | `topbar/tool-search` | 3 | done |
| `workflow.mini_tutorials` | Mini tutorials | — (shell) | `settings/tutorials` | 28 | reserved |
| `workflow.guides_grid_rulers` | Guides / grid / rulers | — (shell) | `canvas/overlay/guides` | 28 | reserved |
