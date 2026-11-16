# Roadmap

Status: ✅ done · 🔜 next · ⬜ planned. A stage is done only when its Definition of Done
is met, CI is green on `main` and the stage notes (`docs/stages/STAGE_XX.md`) are written.

Stages 5–28 and their tools are fixed by the tool catalog. The scope of stages 1–4 and
29–30 was proposed in Stage 0 and is open to adjustment by Mark before each starts.

| # | Stage | Goal | Status |
| --- | --- | --- | --- |
| 0 | Scaffolding | Modules, convention plugins, CI (lint, unit, native, build, emulator), release workflow, golden + screenshot test infrastructure, permanent docs, installable APK | ✅ |
| 1 | Design system components | The `Lo*` component library on the tokens: `LoSlider`, `LoPanel`, `LoIconButton`, `LoToolChip`, `LoBottomSheet`, …; each screenshot-tested; 48 dp targets; reduced-motion support | 🔜 |
| 2 | App shell | Navigation (Home → Editor → Export, Settings); editor shell with every reserved slot (top bar, canvas, category bar in the fixed order, tool strip, panels); phone, tablet, foldable and landscape layouts (D4) | ⬜ |
| 3 | Engine core | GLES 3.2 context and render thread, RGBA16F 512×512 tile cache, linear Rec.2020 working space, shader pipeline executing an op list, CPU reference path, GPU-vs-CPU golden tests | ⬜ |
| 4 | Import and canvas | Photo picker / SAF import (original never modified), decode + colour management + EXIF orientation, 2048-px preview proxy, canvas with pan / zoom / rotate at 120 Hz, full-resolution background render | ⬜ |
| 5 | Light | Exposure, Contrast, Highlights, Shadows, Whites, Blacks, Brightness; Histogram; clipping warnings | ⬜ |
| 6 | Color basics | White balance, Vibrance, Saturation, HSL / Color Mixer, Invert, Posterize, Threshold | ⬜ |
| 7 | Curves and grading | Curves (point, parametric), Levels; Color Grading, Split toning, Color Balance, Selective Color, Channel Mixer, Black & White, Photo Filter, Gradient Map, Calibration | ⬜ |
| 8 | History and projects | Undo / redo, history list, snapshots, versions, before / after, `.loproj` project file | ⬜ |
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
| 25 | RAW | RAW development (LibRaw decision pending, see STAGE_00 open questions) | ⬜ |
| 26 | AI tools | Background removal, super resolution, AI denoise, sky replacement, portrait retouch, depth blur, generative fill — all on-device, models downloaded on first use | ⬜ |
| 27 | Merge | HDR, panorama, focus stacking | ⬜ |
| 28 | Workflow | Batch, sync settings, tool search, favorites, mini tutorials, guides / grid / rulers | ⬜ |
| 29 | Performance and hardening | Macrobenchmarks and baseline profiles on the phone, memory limits on huge images, thermal behaviour, accessibility pass, crash-free soak test | ⬜ |
| 30 | Release 1.0 | Final QA on the phone and a tablet / foldable, complete tool catalog, signed `v1.0.0` release | ⬜ |
