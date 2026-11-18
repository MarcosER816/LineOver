# Roadmap

Status: ✅ done · 🔜 next · ⬜ planned. A stage is done only when its Definition of Done
is met, CI is green on `main` and the stage notes (`docs/stages/STAGE_XX.md`) are written.

The split of all stages was fixed by Mark after Stage 0. Stages 5–28 deliver the tools
listed in `TOOL_CATALOG.md`; stages 1–4 build the foundation they plug into.

| # | Stage | Goal | Status |
| --- | --- | --- | --- |
| 0 | Scaffolding | Modules, convention plugins, CI (lint, unit, native, build, emulator), release workflow, golden + screenshot test infrastructure, permanent docs, installable APK | ✅ |
| 1 | Design system and components | The `Lo*` component library on the tokens (`LoSlider`, `LoPanel`, `LoToolButton`, `LoCategoryBar`, `LoColorWheel`, …), each screenshot-tested at compact and expanded widths; 48 dp targets; haptics; reduced-motion support; debug component gallery. No screens | ✅ |
| 2 | App shell, home and settings | Navigation 3 host with predictive back; Home (import, projects in Room, thumbnails, rename / duplicate / delete with undo, device albums); Settings with reserved rows; editor and export placeholders; phone, tablet, foldable and landscape layouts (D4) | ✅ |
| 3 | Editor shell | The full editor layout with a visible placeholder slot for **every** tool in `TOOL_CATALOG.md`; canvas view with zoom / pan / rotate gestures; side sheets; export sheet; tool search. **The layout is frozen after this stage (R9).** No engine yet | ✅ |
| 4 | Engine foundation, file loading and edit graph | GLES 3.2 engine foundation (render thread, RGBA16F 512×512 tiles, linear Rec.2020, CPU reference path, GPU-vs-CPU golden tests); file loading (original never modified, decode, colour management, 2048-px preview proxy, full-resolution background render); edit graph wired from UI to engine | ✅ |
| 5 | Light | Exposure, Contrast, Highlights, Shadows, Whites, Blacks, Brightness; Histogram; clipping warnings; before / after (hold, split, side by side) | ✅ |
| 6 | Color basics | White balance, Vibrance, Saturation, HSL / Color Mixer, Invert, Posterize, Threshold | 🔜 |
| 7 | Curves and grading | Curves (point, parametric), Levels; Color Grading, Split toning, Color Balance, Selective Color, Channel Mixer, Black & White, Photo Filter, Gradient Map, Calibration | ⬜ |
| 8 | History and projects | Undo / redo, history list, snapshots, versions, `.loproj` project file (before / after moved to Stage 5) | ⬜ |
| 9 | Crop and optics | Crop, aspect ratios, Straighten, Rotate, Flip, perspective crop; Upright, lens distortion, chromatic aberration, lens vignetting | ⬜ |
| 10 | Effects and detail | Texture, Clarity, Dehaze, Vignette, Grain; Sharpen, Noise reduction | ⬜ |
| 11 | Presets and Auto | Presets, Profiles, 3D LUTs, copy / paste settings, Auto | ⬜ |
| 12 | Export | JPEG, PNG, WebP, HEIF, AVIF, TIFF, Ultra HDR; resize, metadata, watermark, ICC; output to `Pictures/LineOver` | ⬜ |
| 13 | Layers | Raster, adjustment, group, smart object; opacity, fill, 27 blend modes | ⬜ |
| 14 | Layer masks | Layer mask, clipping mask | ⬜ |
| 15 | Selections I | Marquee, lasso, polygonal lasso, magic wand, color range | ⬜ |
| 16 | Selections II | Magnetic lasso, quick selection, object / subject / sky select, refine edge (first downloadable models, D3) | ⬜ |
| 17 | Local masks | Linear, radial, brush, color range, luminance range, depth range, subject / sky / background / people | ⬜ |
| 18 | Paint | Brush, pencil, eraser, mixer brush, gradient, paint bucket, pattern, color picker | ⬜ |
| 19 | Retouch I | Spot healing, healing brush, clone stamp, patch, red-eye, dodge, burn, sponge, blur / sharpen / smudge | ⬜ |
| 20 | Retouch II | Content-aware fill, AI remove, content-aware scale | ⬜ |
| 21 | Filters | Gaussian, box, motion, radial, lens, surface blur; tilt-shift, bokeh, high pass, emboss, distort, noise, pixelate, stylize, filter gallery | ⬜ |
| 22 | Liquify and transform | Liquify, face-aware liquify, free transform, warp, puppet warp | ⬜ |
| 23 | Text | Text layers | ⬜ |
| 24 | Shapes and paths | Shapes, pen, curvature pen, path selection, vector mask | ⬜ |
| 25 | RAW | RAW development. LibRaw (LGPL-2.1) is approved, only as a separate, dynamically linked `libraw.so` | ⬜ |
| 26 | AI tools | Background removal, super resolution, AI denoise, sky replacement, portrait retouch, depth blur, generative fill — all on-device, models downloaded on first use | ⬜ |
| 27 | Merge | HDR, panorama, focus stacking | ⬜ |
| 28 | Workflow | Batch, sync settings, favorites, mini tutorials, guides / grid / rulers (tool search ships with the editor shell in Stage 3) | ⬜ |
| 29 | Performance hardening | Macrobenchmarks and baseline profiles on the phone, memory limits on huge images, thermal behaviour, crash-free soak test | ⬜ |
| 30 | Accessibility, polish and release v1.0 | Accessibility pass, visual polish, final QA on the phone and a tablet / foldable, signed `v1.0.0` release | ⬜ |
