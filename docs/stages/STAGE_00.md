# Stage 00 — Scaffolding, CI, test infrastructure, permanent docs

## Goal

Create everything later stages build on: the module skeleton, convention plugins, CI,
golden and screenshot test infrastructure, and the permanent documentation. No feature
UI and no tools.

## Definition of Done

- [x] CI is fully green on `main`
- [x] Debug and release APKs are CI artifacts (`lineover-<sha>`)
- [x] Host ctest passes (3 executables, 15 test cases)
- [x] One Roborazzi test passes (`DesignTokensScreenshotTest`)
- [x] One golden test passes: identity op vs the Python reference (host ctest, JVM, and on
      the emulator through JNI)
- [x] Emulator job passes a GL smoke test (clear to a colour, read the pixel back)
- [x] All docs exist and `TOOL_CATALOG.md` lists every tool (162, all `reserved`)

## What was built

| Module | Content |
| --- | --- |
| `build-logic` | Convention plugins `lineover.android.{application,library,test,compose,feature,baselineprofile}`, `lineover.kotlin.serialization`, `lineover.roborazzi`, `lineover.quality` (detekt + ktlint) |
| `:app` | `MainActivity` showing "LineOver" on `#0E0E10`; window/splash theme; adaptive icon; release signing from the environment; instrumented launch test |
| `:core:designsystem` | `LineOverTheme`, `LoColors`, `LoSpacing`, `LoDimens`, `LoType` (bundled Roboto Flex), `LoMotion`, `LocalReducedMotion`; token screenshot test |
| `:core:ui` | `MviViewModel<State, Intent, Effect>` |
| `:core:model` | `EditGraph`, `Layer`, `Op` (only `Identity`), `EditGraphJson`, `ToolCategory` (fixed order) |
| `:core:engine` | `LoEngine` (JNI); `GoldenAssert`, `PngIo`, `NpyIo` as test fixtures; instrumented GL smoke and identity golden tests |
| `:core:engine-native` | CMake project: portable core (`lo_engine`), Android `liblineover_engine.so` (JNI + EGL/GLES 3), host test helpers and ctest tests |
| `:core:io` | `LoPaths` (`.loproj`, `Pictures/LineOver`) |
| `:core:ml` | `ModelSpec`, `Sha256` (D3 groundwork) |
| `:feature:*` | Empty modules wired for Compose and Roborazzi (no UI this stage, by design) |
| `:benchmark`, `:baselineprofile` | Startup macrobenchmark and startup baseline-profile generator (run on the phone, not in CI) |
| `tools/golden` | `generate.py` (+ `--check`), `reference.py` |
| CI | `ci.yml` (lint, unit, native, build, emulator API 34 + 36), `release.yml` |

## Tools delivered

None (by design). `TOOL_CATALOG.md` created: 162 tools, all `reserved`.

## Decisions and deviations

1. **Versions: "latest stable" won over the numbers in the brief.** The brief named
   AGP 9.2.x / Gradle 9.4.1 and asked to verify the latest stable versions first. Verified
   on 2026-11-16 against Google Maven, Maven Central and services.gradle.org:

   | Component | Brief | Pinned | Note |
   | --- | --- | --- | --- |
   | AGP | 9.2.x | 9.5.1 | latest stable; built-in Kotlin, `kotlin-android` not applied |
   | Gradle | 9.4.1 | 9.9.1 | latest stable |
   | Kotlin | 2.4.x | 2.4.21 | set through `kotlin-gradle-plugin` on the build-logic classpath |
   | Compose BOM | check mapping | 2026.06.01 | compose 1.11.4 + material3 1.4.0; see item 2 |
   | material3 | 1.4.0 | 1.4.0 | 1.5.0 is at rc01, not stable |
   | Roborazzi | 1.7x | 1.79.0 | |
   | Robolectric | 4.17 | 4.18 | latest stable |
   | LiteRT | 2.x | 2.3.2 | pinned in the catalog only; wired in when the first model ships |
   | NDK | r28 | 28.2.13676358 (r28c) | CMake 3.31.6 |
   | JDK | 17 | 17 (CI) | |

2. **compileSdk 36 caps AndroidX.** BOM 2026.08.00+ (Compose 1.12), `core` 1.19+ and
   lifecycle 2.11+ declare `minCompileSdk=37`. D2 fixes compileSdk 36, so the newest
   compatible versions are pinned: BOM 2026.06.01, core-ktx 1.18.0, lifecycle 2.10.0.
   Android lint's `OldTargetApi` hint is disabled for the same reason.
3. **Branch name.** This stage was developed on the session branch
   `ccr-a94fce54-a1j7s3` instead of `stage-00-scaffolding` (a constraint of the hosted
   session). `main` was created with a README-only bootstrap commit so that a PR could
   target it.
4. **Debug application id** is `dev.mark.lineover.debug`, so the debug and release APKs
   (which may be signed with different keys) install side by side. Release keeps
   `dev.mark.lineover` (D1).
5. **ktlint runs as a CLI** through a `JavaExec` task instead of a Gradle plugin, so it
   does not depend on AGP/Kotlin plugin internals. detekt uses its official plugin.
6. **No test framework dependency in C++.** ctest executables use a 60-line harness
   (`testing/lo_test.h`). PNG decoding in the test helpers uses zlib only.
7. **Float goldens are `.npy`** (float32), 8-bit goldens are PNG.
8. **`opencv-python-headless`** is used instead of `opencv-python`: the same `cv2`
   module without GUI libraries, which is what a headless CI runner needs.
9. **Feature modules contain no screens**, so they have Roborazzi wired
   (plugin, dependencies, `src/test/screenshots`) but no screenshot test yet. The one
   Roborazzi test of this stage is in `:core:designsystem`.
10. **Emulator matrix: API 34 and API 36.** A stable `google_apis` x86_64 image exists for
    36. The GL smoke test uses an off-screen pbuffer surface.
11. **Proposed scope for stages 1–4 and 29–30** is in `ROADMAP.md`; the brief only fixed
    the tools of stages 5–28.

## Dependencies added (R10)

| Dependency | Version | Licence | Why |
| --- | --- | --- | --- |
| Android Gradle Plugin | 9.5.1 | Apache-2.0 | Build |
| Kotlin (Gradle, compose, serialization plugins) | 2.4.21 | Apache-2.0 | Language, Compose compiler, serialisation |
| kotlinx-serialization-json | 1.13.0 | Apache-2.0 | Edit graph / `.loproj` JSON |
| kotlinx-coroutines (core, android, test) | 1.11.0 | Apache-2.0 | State flows in `MviViewModel` |
| Compose BOM (ui, foundation, material3, tooling, ui-test) | 2026.06.01 | Apache-2.0 | UI toolkit |
| androidx.activity:activity-compose | 1.13.0 | Apache-2.0 | `setContent`, edge-to-edge |
| androidx.core:core-ktx | 1.18.0 | Apache-2.0 | Platform helpers |
| androidx.lifecycle:lifecycle-viewmodel | 2.10.0 | Apache-2.0 | One ViewModel per screen |
| androidx.profileinstaller | 1.4.1 | Apache-2.0 | Installs baseline profiles |
| androidx.benchmark (macro-junit4, baselineprofile plugin) | 1.5.0 | Apache-2.0 | `:benchmark`, `:baselineprofile` |
| androidx.test.uiautomator | 2.4.1 | Apache-2.0 | Needed by macrobenchmark |
| androidx.test (runner, ext.junit) | 1.7.0 / 1.3.0 | Apache-2.0 | Instrumented tests |
| JUnit 4 | 4.13.2 | EPL-1.0 | Tests only, not shipped |
| Robolectric | 4.18 | MIT | JVM Android runtime for screenshot tests; not shipped |
| Roborazzi | 1.79.0 | Apache-2.0 | Screenshot tests; not shipped |
| detekt | 1.23.8 | Apache-2.0 | Lint; not shipped |
| ktlint | 1.8.0 | MIT | Lint; not shipped |
| Roboto Flex (font) | repo head, sha256 `94a7ea95…7ffe63` | SIL OFL 1.1 | Design-system typeface; licence in `core/designsystem/ROBOTO_FLEX_OFL.txt` |
| zlib (system / NDK) | system | zlib | PNG in host test helpers only |
| numpy / opencv-python-headless / colour-science | 2.5.3 / 5.0.0.93 / 0.4.7 | BSD-3 / Apache-2.0 / BSD-3 | Golden reference tooling; never shipped |
| LiteRT | 2.3.2 | Apache-2.0 | Pinned only, not yet a dependency of any module |

No GPL, LGPL or non-commercial dependency was added.

## Golden images changed (R4)

First generation, nothing replaced: `input/pattern_rgba8.png`,
`input/pattern_rgba8_as_f32.npy`, `input/pattern_rec2020_f32.npy`,
`identity/expected_rgba8.png`, `identity/expected_rec2020_f32.npy`.

## Troubleshooting log (R11)

| Symptom | Root cause | Fix |
| --- | --- | --- |
| `Plugin 'androidx.baselineprofile' was not found` | A plugin that is only on the build-logic classpath cannot be applied by id from a module | Wrapped in `lineover.android.baselineprofile` |
| `checkDebugAarMetadata`: "requires compileSdk 37" | Newest AndroidX needs compileSdk 37 | Pinned the newest versions with `minCompileSdk ≤ 36` (decision 2) |
| CI `unit` job: `UnsupportedOperationException at DefaultSdkProvider` (run 1) | Robolectric's SDK 36 runtime requires Java 21; CI uses JDK 17 (local checks had run on JDK 21) | Screenshot test runs on Robolectric SDK 35; image re-recorded before first merge; local verification repeated on JDK 17 |
| `OPT_IN_USAGE_ERROR` in `LoType.kt` | Variable-font axis API is `@ExperimentalTextApi` | Explicit opt-in on that one function |

Plan B used: no. Both emulator jobs (API 34 and API 36) passed on the first run without `continue-on-error`.

## Proof

- CI on `main`: <https://github.com/MarcosER816/LineOver/actions/workflows/ci.yml?query=branch%3Amain>
- APK artifact: `lineover-<sha>` on that run (debug + release APK)
- Screenshot diffs: artifact `screenshot-diffs` of the `unit` job (empty when nothing differs)

## Phone checklist

On the POCO F7 Ultra (Android 16):

- [ ] Download the artifact `lineover-<sha>` from the green run on `main`, unzip it.
- [ ] Install `lineover-<sha>-release.apk` (allow "install unknown apps" for the file manager).
- [ ] Open LineOver: a dark screen (`#0E0E10`) with the word "LineOver" centred. No crash.
- [ ] The launcher icon is a white frame crossed by a blue line on a dark background.
- [ ] Rotate the phone: the word stays centred, no flicker, no restart.
- [ ] Optional: also install `lineover-<sha>-debug.apk`; it appears as a second app.

## Open questions (R12)

1. **Keystore secrets.** Have you added `KEYSTORE_B64`, `KEYSTORE_PASS`, `KEY_ALIAS`,
   `KEY_PASS`? Until then the release APK is signed with the debug key (default used).
   Steps for Termux are in `README.md`, section Signing.
2. **LibRaw (LGPL-2.1) for Stage 25**, built and shipped as a separate shared library
   (`libraw.so`, dynamically linked, never statically): acceptable? Default if no answer:
   yes, under exactly that condition (R10).
3. **Stages 1–4 and 29–30**: is the proposed scope in `ROADMAP.md` what you want?
   Default: proceed as proposed.
