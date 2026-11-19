# Stage 12 — Export

## Goal

The Export sheet becomes real: a full-resolution tiled render in a foreground service,
JPEG / PNG / WebP / HEIF / AVIF / TIFF / Ultra HDR encoders, colour space with an embedded ICC,
resize and sharpen for screen, metadata, watermark, MediaStore / SAF / Share.

## Definition of Done

- [x] Round-trip tests (export -> decode -> compare to the render within tolerance):
      `ExportRoundTripTest` on the emulator decodes every format with Android's decoders and
      compares it with the render the export started from:
      - PNG 8-bit and lossless WebP: exact.
      - JPEG q100 (Display P3): mean ≤ 1.5/255 and PSNR ≥ 38 dB.
      - 16-bit PNG against 16-bit TIFF: within 1/1024 (half-float decode).
      - HEIF / AVIF where the emulator has the encoder: PSNR ≥ 30 dB.
      - Resize and watermark change only what they should.
      - Ultra HDR keeps the photo's gain map or rebuilds one.

      On the host, ctest `output_test` writes PNG / TIFF and reads them back exactly, and
      `check_export_files.py` reads the same files with OpenCV in CI.
- [x] ICC presence tests:
      - ctest: iCCP / tag 34675 equal the profile; the three profiles are byte-identical to the
        Python reference.
      - CI: LittleCMS parses the files' profiles.
      - Emulator: the decoded Bitmap's colour space is Display P3 / sRGB / BT.2020; JPEG APP2 and
        WebP ICCP are present.
- [x] EXIF stripping tests:
      - `ExportFilesTest` (JVM):
        - location removed by default and kept on request;
        - Copyright only leaves only Artist / Copyright;
        - None writes no EXIF;
        - the camera's own block is replaced, not merged.
      - `ExportRoundTripTest` checks the same on real exported files.
- [x] CI green; APK built

## What was built

| Module | Content |
| --- | --- |
| `:core:engine-native` | `output.h` / `output.cpp`:<br>- Lanczos-3 resize (linear light, never enlarges)<br>- sharpen for screen (banded unsharp mask on cube-root luminance)<br>- watermark composite (linear light)<br>- conversion to 8 / 16-bit codes (gamut-mapped sRGB / P3, BT.2020 curve for Rec. 2020)<br>- ICC v4.3 matrix/TRC profiles<br>- Ultra HDR gain map rebuild<br><br>`output_files.cpp`:<br>- mapped float `Image` (temp file + mmap)<br>- PNG via libspng (iCCP, eXIf, progressive rows)<br>- baseline TIFF writer (front to back, no seeking)<br><br>`android/export_jni.cpp` (`NativeExport`) |
| `:core:model` | `ExportSettings` (formats, bit depths, colour spaces, resize modes, sharpen, metadata, watermark; `validated`, `outputSize`, JSON), `ExportEstimate` |
| `:core:io` | `export/`:<br>- `JpegSegments` (EXIF / ICC insertion that keeps Ultra HDR MPF offsets valid)<br>- `WebpChunks` (VP8X + ICCP + EXIF)<br>- `ExportMetadata` (tags by mode, EXIF block via ExifInterface)<br>- `ExportDestinations` (MediaStore Pictures/LineOver, pending until complete; SAF)<br><br>`AppPreferences.exportSettings`; exifinterface now `api` |
| `:core:engine` | `ExportImage` (process, toBitmap, rebuildGainmap, writePng, writeTiff, iccProfile), `EngineSession.renderForExport` |
| `:feature:export` | Export sheet (all slots filled, estimate, Quick export, save to, progress / done / failed, Save project); `ExportViewModel`; `ExportRunner`; `ExportEncoders`; `Watermarks`; `ExportCapabilities` (HEIF / AVIF by codec); `ExportService` (foreground, notification with Cancel, Open / Share) |
| `:feature:editor` | Export saves the edit first (`SaveNow`) |
| `:app` | `LineOverApp` provides `ExportEnvironment`; 0.12.0 |
| `:core:ui` | 11 export tools `done` (162 tools, 68 done) |

Tests added:
- ctest `output_test` (10)
- `check_export_files.py` (CI)
- `ExportSettingsTest` (4) and `ExportEstimateTest` (2)
- `ExportFilesTest` (9)
- `ExportViewModelTest` (4)
- `ExportSheetScreenshots` (6 states × 2 widths)
- instrumented `ExportRoundTripTest` (9)

## Tools delivered

| id | Name | UI slot | Tests |
| --- | --- | --- | --- |
| `export.jpeg` | JPEG | `sheet/export/format/01` | ExportRoundTripTest (P3 round trip, ICC), ExportFilesTest |
| `export.png` | PNG | `sheet/export/format/02` | output_test, check_export_files.py, ExportRoundTripTest (exact, 16-bit) |
| `export.webp` | WebP | `sheet/export/format/03` | ExportFilesTest (chunks), ExportRoundTripTest (lossless exact, ICCP, EXIF) |
| `export.heif` | HEIF | `sheet/export/format/04` | ExportRoundTripTest (where an encoder exists), ExportViewModelTest (hidden otherwise) |
| `export.avif` | AVIF | `sheet/export/format/05` | as HEIF |
| `export.tiff` | TIFF | `sheet/export/format/06` | output_test, check_export_files.py, ExportRoundTripTest (16-bit vs PNG) |
| `export.ultra_hdr` | Ultra HDR | `sheet/export/format/07` | output_test (gain map), ExportRoundTripTest (kept and rebuilt), ExportFilesTest (MPF offsets) |
| `export.resize` | Resize | `sheet/export/options/01` | output_test, ExportSettingsTest, ExportRoundTripTest |
| `export.metadata` | Metadata | `sheet/export/options/02` | ExportFilesTest, ExportRoundTripTest |
| `export.watermark` | Watermark | `sheet/export/options/03` | output_test, ExportRoundTripTest |
| `export.icc_profile` | ICC profile | `sheet/export/options/04` | output_test, check_export_files.py, ExportRoundTripTest |

`TOOL_CATALOG.md` updated: yes.

## Decisions and deviations

1. **Precision.** The export renders into float RGBA kept in a temporary file mapped into
   memory, not the Java heap. A 50 MP render is 800 MB that the kernel pages, not native heap.
   It stays float through resize, sharpen and watermark and is quantised once, in the
   encoder's row conversion. PNG and TIFF write 16 bits straight from the floats.
2. **Encoders.**
   - JPEG, WebP and Ultra HDR use `Bitmap.compress` on an 8-bit Bitmap in the target colour
     space. That is the brief's first option for JPEG; libjpeg-turbo was not vendored.
     Android's JPEG encoder uses 4:2:0 chroma, as every phone JPEG does.
   - PNG uses libspng.
   - TIFF uses our own baseline writer (uncompressed RGB strips).
   - HEIF and AVIF use androidx.heifwriter on the device's HEVC / AV1 encoders. They are
     8-bit sRGB because the platform encoder does not tag other spaces, so the sheet offers
     only sRGB for them.
3. **ICC.**
   - LineOver writes its own ICC v4.3 profiles: D50-adapted colorants (Bradford), a parametric
     curve and `chad`, with the profile ID left zero, which the spec allows.
   - Python generates the golden profile bytes; C++ must equal them.
   - The Rec. 2020 profile uses the BT.2020 curve with Android's parameters, so Android
     decodes 16-bit PNGs as `ColorSpace.BT2020`.
   - JPEG keeps the encoder's own ICC when it wrote one (Display P3) and gets LineOver's
     otherwise (sRGB).
4. **Gamut.** sRGB and P3 exports are gamut-mapped exactly like the display: OKLab lightness
   and hue are kept, chroma is bisected. Rec. 2020 is the working space, so it is only clipped
   to 0..1.
5. **Ultra HDR.** The SDR base is the clipped render.
   - The photo's gain map is kept, warped with the crop and geometry (`gainmapFor`); Android
     scales it to any export size.
   - Without one, a gain map is rebuilt from the render's highlights above the SDR base: mean
     log2 luminance ratio per 4 × 4 block, ratio min 1, epsilon 1/64.
   - When there are no highlights, the export says so instead of writing a fake HDR file.
6. **Resize** is a separate Lanczos-3 filter after the render, not part of the geometry matrix.
   Downscaling by large factors needs a prefilter, which the one bicubic resample of the
   geometry has not. It never enlarges.
7. **Metadata.** EXIF text is ASCII (the writer turns other characters into "?"), so "©"
   becomes "(C)" and accents are dropped. Orientation is set to normal, because the pixels are
   already upright. TIFF carries make, model, software, date, artist and copyright as TIFF
   tags, not a full EXIF IFD.
8. **Service.** The foreground service type is mediaProcessing on Android 15+ and dataSync on
   14. One export runs at a time. The notification permission is asked for on the first
   export; the export runs whether or not it is granted, and the sheet shows the progress too.
9. **Quick export** is in the sheet's header (two taps from the editor). It uses the factory
   defaults, whatever the sheet last used.
10. **Size estimate.** It encodes the project's thumbnail with the chosen encoder and scales
    by pixels, using factors because large photos compress better than their thumbnail. TIFF
    is exact.
11. **WebP** is limited to 16383 px per side by the format. The export stops with a message
    asking for a smaller size.

## Dependencies added (R10)

| Dependency | Licence | Why |
| --- | --- | --- |
| libspng 0.7.4 (vendored C, `third_party/spng`) | BSD-2-Clause | 16-bit PNG with iCCP / eXIf, row by row |
| zlib (NDK system `libz`, host `zlib1g`) | zlib | libspng's deflate |
| androidx.heifwriter 1.2.0 | Apache-2.0 | HEIF and AVIF (AvifWriter) through the device's encoders; minCompileSdk 34 |
| androidx.activity:activity-compose (already used by other modules) | Apache-2.0 | file pickers and the notification permission in the sheet |
| Pillow 12.3.0 (CI only, `tools/golden/requirements.txt`) | HPND (permissive) | reads the ICC profiles of exported files in `check_export_files.py` |

## Golden images changed (R4)

No existing golden file changed. New files are under `testdata/golden/export/`:
- resize ×2, sharpen, the watermark mark and two composites;
- conversions to sRGB / P3 8-bit, sRGB / P3 / Rec. 2020 16-bit;
- gain maps ×2;
- the three ICC profiles (`export/icc/*.icc`, a new golden file type compared byte for byte).

Screenshots changed, and why:

| Screenshot | Why |
| --- | --- |
| `export/*/export_sheet` | The sheet's controls are live: format chips without "Stage 12", quality, size modes with the size and estimate line, sharpen, colour space note, metadata modes, watermark, Save to, Quick export, Export |
| `export/*/export_tiff16_watermark`, `export_no_avif`, `export_running`, `export_done`, `export_failed` | New: the sheet's states |

## Troubleshooting log (R11)

| Symptom | Root cause | Fix |
| --- | --- | --- |
| PNG round trip: ICC profile garbage although the chunk was there | `spng_set_iccp` keeps a pointer; the copy went out of scope before encoding | Keep the copy alive until the image is encoded |
| "© Mark" came back as "? Mark" from EXIF | EXIF text tags are ASCII | Transliterate (`ExportMetadata.ascii`) |
| Copyright-only export still showed LightSource = 0 | ExifInterface writes it as a compatibility default ("unknown") | Accepted (no information); the test names it |
| Size slider showed 3008 px for 3000 | 16 px steps counted from the 256 px minimum | 1 px steps |

Plan B used: no.

## Proof

- CI run and APK artifact: see the Stage 12 report

## Phone checklist

POCO F7 Ultra, `lineover-<sha>.apk`:

- [ ] Open a photo → Export. The sheet shows the size and "≈ … MB". Close it and open it again:
      it remembers your settings.
- [ ] Export a **Display P3 JPEG** (default). A notification shows the progress, then "Exported";
      Open it in Gallery / Google Photos. Colours match the editor.
- [ ] Export **Ultra HDR** from one of your Ultra HDR photos and open it in Google Photos:
      the bright highlights glow brighter than the rest of the screen (HDR badge).
- [ ] Metadata: the default export has camera details but no location. "Copyright only" shows
      just the copyright.
- [ ] Watermark: turn it on (text), Bottom right, 70 %: the text is on the exported photo.
- [ ] PNG / TIFF 16-bit Rec. 2020, Long edge 3000: opens in a viewer at 3000 px.
- [ ] HEIF / AVIF chips appear only if the phone has the encoders; try one.
- [ ] Save to → Choose…: pick a folder and name; the file is there. Share opens the share sheet.
- [ ] Quick export: one tap in the sheet saves a JPEG with the defaults.

## Open questions (R12)

1. **Default format and quality?** Now JPEG, quality 90, Display P3, original size, location
   removed. Default if unanswered: keep.
2. **Watermark text?** Now "© Mark" (written as "(C) Mark" in EXIF copyright). Default if
   unanswered: keep.
