# Stage 11 — Presets and Auto

## Goal

The Presets and Auto categories: presets (partial edit graphs with an Amount), profiles (base
looks), imported .cube 3D LUTs, copy / paste settings, and a statistics-based Auto.

## Definition of Done

- [x] .cube parser tests, malformed files included: `CubeParserTest` (fixture LUTs at 17 and
      33 with a custom domain, a generated 65^3 written and read back, comments / tabs /
      `LUT_3D_INPUT_RANGE`, and 15 broken files in `testdata/luts/malformed`, each refused with
      its own message: no size, too few / too many rows, a word or NaN in the table, two
      values, size 1 / 129 / 2.5, a 1D LUT, inverted domain, size twice, data before the size,
      an unknown keyword, an empty file); the Python parser refuses the same files
- [x] Tetrahedral vs the Python reference, max 1/255: ctest `lut_test` (imported LUTs at 100 %
      and 60 % on the chart and the HDR scene, the three profiles and Flat at 50 %: max
      |diff| 4.8e-7; profile tables 6e-8) and `EngineGpuTest` (a LUT as a 3D texture and the
      Vivid profile through the canvas's shaders, 3/255)
- [x] CI green; APK built

## What was built

| Module | Content |
| --- | --- |
| `:core:engine-native` | `lut.h/.cpp`: `Lut3D`, the process-wide registry, the LUT / profile op (CPU reference + GLSL on an RGBA32F 3D texture), tetrahedral interpolation, the Vivid / Mono / Flat profile tables (OKLab). `auto_adjust.h/.cpp`: Auto. Graph JSON string fields (`lut` id, `profile` name). Android: 3D texture cache on unit 8, `setUniform3f`, `lo_register_lut`, `lo_engine_auto_settings`, thumbnails from a new 256 px proxy |
| `:core:model` | `CubeParser` / `CubeLut`, `OpParams.Lut` / `Profile`, `LookAdjustments` (in `editOps`: profile after white balance, LUT after the grading), `Preset` + `PresetMath` (Amount 0–200 %), `BuiltInPresets` (20), `SettingsGroup`, `AutoResult`, `LooksState` |
| `:core:io` | `LooksLibrary` (imported LUTs by SHA-256, user presets, favourites, copied settings; atomic writes) and `InMemoryLooksStore` |
| `:core:engine` | `EngineSession.registerLut`, `autoSettings`, `thumbnail(graph, maxEdge)` |
| `:feature:editor` | `EditorLooksActions` (apply, Amount, hold to compare, favourites, import, copy / paste, Auto, thumbnails, LUT registration before a graph names a LUT), `LooksPanels` (strips, sliders, Import .cube, checklist, Auto), Save current as preset (name dialog) |
| `:core:ui` | 5 tools `done` (162 tools, 57 done) |

Tests added: ctest `lut_test` (10: LUTs, profile tables, profiles, identity, graph mapping,
validation, Auto ×4); `CubeParserTest` (6), `PresetsTest` (6), `SettingsGroupTest` (3),
`LooksLibraryTest` (3), `LooksEditingTest` (9), `LooksPanelScreenshots` (3 sheets × 2 widths),
the `presets_panel` editor state; instrumented `EngineGpuTest` +2.

## Tools delivered

| id | Name | UI slot | Tests |
| --- | --- | --- | --- |
| `presets.presets` | Presets | `cat/Presets/01` | PresetsTest, LooksEditingTest (apply, Amount, undo, save, favourite), screenshots |
| `presets.profiles` | Profiles | `cat/Presets/02` | lut_test, EngineGpuTest, LooksEditingTest (compare, Amount), screenshots |
| `presets.3d_luts` | 3D LUTs | `cat/Presets/03` | CubeParserTest, lut_test, EngineGpuTest, LooksLibraryTest, LooksEditingTest (import, refuse, Amount, remove) |
| `presets.copy_paste_settings` | Copy/paste settings | `cat/Presets/04` | SettingsGroupTest, LooksLibraryTest, LooksEditingTest |
| `auto.auto` | Auto | `cat/Auto/01` | lut_test (dark / bright, colour cast, contrast and vibrance, empty image), LooksEditingTest |

`TOOL_CATALOG.md` updated: yes.

## Decisions and deviations

1. **Where a LUT works.** .cube files expect sRGB-encoded Rec.709 values in 0..1, so the op
   converts from the linear Rec.2020 working space, looks up, mixes by Amount in encoded
   space and converts back. Outside the domain the LUT continues with slope 1 **in luma
   only**: HDR highlights are not clipped, while out-of-sRGB chroma goes through the LUT (the
   first version continued all channels and left Mono colourful on wide-gamut colours).
2. **LUT data is not in the graph.** A 65^3 table is 824 k numbers. Kotlin stores the .cube
   under its SHA-256 and registers the table with the engine; the graph names the id (and a
   name for people). A project moved to another phone shows "LUT not on this device" until
   that .cube is imported there (the op is skipped meanwhile, never faked). Project files do
   not embed LUTs yet.
3. **3D texture, not an atlas.** GLES 3.0 guarantees 3D textures of at least 256^3 and LUTs
   stop at 65^3, so the brief's IF-WRONG fallback (a 2D atlas) was not needed. Lookups are
   `texelFetch` (four per pixel) with the tetrahedron chosen in the shader, so the GPU matches
   the reference instead of using trilinear filtering.
4. **Profiles are LUTs made by the engine** from OKLab formulas (Vivid: chroma ×1.25 pulled
   back into sRGB, a mild lightness S-curve; Mono: OKLab lightness; Flat: chroma ×0.85, half
   the midtone contrast, lifted blacks; Neutral: no op). No profile is copied from a camera
   maker or another app.
5. **Preset Amount** blends from the photo's own values (or neutral when it has none) towards
   the preset: 0 % changes nothing, 100 % is the preset, 200 % goes as far again. Hues of
   grading wheels, curve split points, LUT ids and the black & white mix are the preset's
   once Amount is above 0 (they do not blend linearly). The preset and its Amount are one
   History step, even when the autosave closed it in between.
6. **Built-in presets**: 20, in four groups, all LineOver's own combinations of its tools; no
   commercial look or film stock is imitated, and no third-party preset or LUT is bundled
   (brief DO NOT).
7. **Auto** is statistics only (the brief's plan B, a small learned model, is for later):
   gray-world white balance over the midtones, exposure 70 % of the way to mid grey (capped
   so the 99th percentile stays under 1.2), whites / blacks from the 99.5 / 0.5 percentiles,
   contrast from the 5–95 % spread (target 6.5 stops), vibrance up to +25 fading out as the
   mean OKLCh chroma reaches 0.12. One History step "Auto". The Light panel's own Auto
   (Stage 5) is unchanged.
8. **Thumbnails** show each preset / profile / LUT on the current edit, rendered from a 256 px
   proxy on the render dispatcher (CPU reference ops), debounced and re-rendered when the edit
   changes while a strip is open.
9. **Copy / paste** keeps the copied settings in the looks library (they survive closing the
   photo and the app). Crop and optics are off by default; when pasted, the panel values are
   rebuilt for the target photo's size.

## Dependencies added (R10)

None.

## Golden images changed (R4)

No existing golden file changed. New: `testdata/golden/luts/` (two original .cube fixtures,
their tables, the LUT op at two amounts, a GPU reference) and `testdata/golden/profiles/`
(the four profile tables, the profiles on the chart, Flat at 50 %, a GPU reference). The Vivid
GPU reference is on the HDR scene, not the chart: the chart's light yellow-greens lie on the
sRGB gamut face where the display gamut mapping turns one float16 step into more than 3/255.
New hand-written fixtures: `testdata/luts/malformed/*.cube`.

Screenshots changed, and why:

| Screenshot | Why |
| --- | --- |
| `editor/*/tool_panel` | The placeholder panel now shows `retouch.spot_healing` (Presets has a real panel) |
| `editor/expanded/category_presets`, `category_auto` | The five tools say "Ready" instead of "Arrives in Stage 11" |
| `editor/*/menu_more` | Copy settings and Paste settings lost their "Stage 11" line (they open the Copy / paste panel) |
| `editor/*/presets_panel`, `editor/looks_panel/*` | New: the Stage 11 panels |

## Troubleshooting log (R11)

| Symptom | Root cause | Fix |
| --- | --- | --- |
| Mono left wide-gamut chart colours colourful | The slope-1 continuation outside the domain carried every channel's residual | Continue in Rec.709 luma only |
| `nan` in a .cube read as a keyword | Lines starting with a letter were taken as keywords | A line is data when its first word is a number (nan / inf included) |
| Vivid GPU vs reference 0.0128 > 3/255 on one chart pixel | The display gamut mapping's steep spot (light yellow-green on the gamut face) amplifies one float16 step | The GPU reference uses the scene (see Golden images) |
| Preset Amount became a second History step | The autosave closes an idle open step | A keyed step reopens while it is the top step |
| Holding a thumbnail did not change the canvas | `setState` does not sync the canvas | `reduce` (graph unchanged, no History step) |
| An imported LUT's op had no name | The library's flow had not reached the state yet | The import passes the name it has |

Plan B used: no.

## Proof

- CI run and APK artifact: see the Stage 11 report

## Phone checklist

POCO F7 Ultra, `lineover-<sha>.apk`:

- [ ] Presets → Presets: the strip fills with thumbnails of your photo. Tap "Golden": the photo
      warms. Drag Amount from 0 % to 200 %. Hold another thumbnail: the canvas shows it until
      you let go. Star two presets: they move to the front. Undo removes the preset in one step.
- [ ] "Save current as preset", name it: it appears in the strip (also in another photo).
- [ ] Presets → Profiles: Vivid, Mono, Flat; Amount.
- [ ] Presets → 3D LUTs → Import .cube: pick a .cube from your files (e.g. one you made or
      downloaded with a licence that allows it). It applies; scrub the Amount. A broken file
      shows why it was refused.
- [ ] Copy / paste: on photo A copy Light and Color; open photo B → Paste: its Light and Color
      change, nothing else.
- [ ] Auto on a dark, bluish photo: brighter and neutral; one step "Auto" in History.

## Open questions (R12)

1. **Any preset styles you want built in?** The 20 now: Natural (Clean, Soft light, Crisp,
   Warm afternoon, Cool morning), Color (Punchy, Pastel, Deep blue, Golden, Teal shadows),
   Black & white (Mono soft, Mono contrast, Mono matte), Mood (Matte fade, Faded print, Night
   city, Moody, Dreamy, Bright & airy, Frame). Default if unanswered: keep these.
