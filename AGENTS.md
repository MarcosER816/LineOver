# AGENTS.md — LineOver

LineOver is a personal, native Android image editor (Photoshop + Lightroom class) built
stage by stage. Owner: Mark (expert in C and Python; works from a phone, has no Android
Studio). Test device: POCO F7 Ultra — Snapdragon 8 Elite, Adreno 830, 1440×3200 120 Hz
AMOLED, HyperOS on Android 16 (API 36). Everything runs on the device; no paid cloud APIs.

Because Mark cannot build locally, **CI is the build machine**: every change is proven by
a green CI run and an APK artifact he installs on the phone.

## Permanent rules

**R0 — Final decisions. Never ask about these again.**

- **D1** App name `LineOver`. Package `dev.mark.lineover`. Theme `LineOverTheme`.
  Component prefix `Lo` (`LoSlider`, `LoPanel`, …). Project file extension `.loproj`.
  Export folder `Pictures/LineOver`. APK artifacts `lineover-<sha>`.
- **D2** `minSdk 34`, `compileSdk 36`, `targetSdk 36`. The test phone runs Android 16.
- **D3** AI models are never bundled in the APK. Each model downloads on first use of its
  tool, with size and licence shown, a SHA-256 check, and a delete option in Settings.
- **D4** Tablet, foldable and landscape layouts are in scope from Stage 2 onward.

**R1** Never stop until the stage's Definition of Done is fully met.

**R2** Read `AGENTS.md`, `docs/ROADMAP.md`, `docs/ARCHITECTURE.md`, `docs/DESIGN_SYSTEM.md`,
`docs/TOOL_CATALOG.md` and the previous `docs/stages/STAGE_XX.md` notes before coding.

**R3** Branch `stage-XX-name`, conventional commits, open a PR, merge to `main` only when
CI is green; never force-push `main`.

**R4** Never weaken, skip or delete tests to make them pass; never change golden images
without explaining why in the stage notes.

**R5** No TODO stubs or fake implementations reported as done.

**R6** Never process full-resolution pixels on the main thread; the UI thread budget is
8.3 ms per frame.

**R7** Editing stays non-destructive; the original file is never modified.

**R8** Never use RenderScript or other deprecated APIs; never commit secrets or keystores.

**R9** Don't move reserved UI slots or restructure the shell; only fill slots.

**R10** Every new dependency is justified in the stage notes with its licence (permissive
preferred; LGPL only as a dynamic library; flag GPL / non-commercial and refuse them).

**R11** Troubleshooting ladder: read the full log, fix the root cause, check version
compatibility, isolate in a minimal test; after 3 failed attempts switch to the documented
plan B; after plan B fails, revert to the last green commit and ask Mark with a precise
report (what failed, logs, options).

**R12** Questions: ask before starting only if blocking; otherwise use the stated default
and list the questions at the end.

**R13** Stage handoff: update ROADMAP, TOOL_CATALOG, `STAGE_XX.md`, CHANGELOG; report the
CI run link, APK artifact link, screenshot diffs and the phone checklist.

## Where things are

| Path | What |
| --- | --- |
| `docs/ROADMAP.md` | Stages 0–30, goals and status |
| `docs/ARCHITECTURE.md` | Modules, state model, edit graph, engine, testing |
| `docs/DESIGN_SYSTEM.md` | Tokens: colour, spacing, type, motion; fixed category order |
| `docs/TOOL_CATALOG.md` | Every tool: id, category, reserved UI slot, stage, status |
| `docs/stages/STAGE_XX.md` | Template for stage notes; `STAGE_00.md`, … are the real notes |
| `CHANGELOG.md` | User-visible changes per stage |
| `gradle/libs.versions.toml` | The only place versions are pinned |
| `build-logic/convention` | `lineover.*` convention plugins used by every module |
| `tools/golden`, `testdata/golden` | Python reference implementations and their golden files |

The module list in `settings.gradle.kts` is **fixed forever**; never add, remove or rename
a module.

## Commands

CI (`.github/workflows/ci.yml`) runs exactly these; run the relevant ones before pushing.

```sh
./gradlew detekt ktlintCheck          # lint job (./gradlew ktlintFormat fixes style)
./gradlew :app:lintDebug              # lint job, Android lint for the whole app
./gradlew testDebugUnitTest verifyRoborazziDebug     # unit job
./gradlew recordRoborazziDebug        # re-record screenshots into src/test/screenshots
./gradlew assembleDebug assembleRelease              # build job
./gradlew connectedDebugAndroidTest   # emulator job (needs a device or emulator)

# native job: host build of the C++ engine + ctest + golden freshness
cmake -S core/engine-native/src/main/cpp -B build/native-host -G Ninja
cmake --build build/native-host && ctest --test-dir build/native-host --output-on-failure
python3 -m pip install -r tools/golden/requirements.txt
python3 tools/golden/generate.py --check
```

## Build facts that are easy to get wrong

- **Built-in Kotlin.** AGP 9 compiles Kotlin itself. Never apply
  `org.jetbrains.kotlin.android`. The Kotlin version is chosen by the
  `kotlin-gradle-plugin` dependency of `build-logic`.
- **compileSdk 36 caps AndroidX.** Compose 1.12+, `androidx.core` 1.19+ and lifecycle
  2.11+ require compileSdk 37 and must not be adopted while D2 stands. Check a library's
  `minCompileSdk` (in its `aar-metadata.properties`) before bumping it.
- **material3** stays on the stable line (1.4.x); move to 1.5.0 only once it is stable.
- **ABIs:** release is `arm64-v8a` only; debug also carries `x86_64` for the CI emulator.
- **Module plugins:** a module applies only `lineover.*` plugins. Third-party Gradle
  plugins are added to `build-logic` and wrapped in a convention plugin.
- **Signing:** release is signed from the `KEYSTORE_B64` / `KEYSTORE_PASS` / `KEY_ALIAS` /
  `KEY_PASS` secrets and falls back to the debug key when they are absent or invalid (CI
  then shows a warning naming the bad secret; `release.yml` fails instead). The debug build
  has the application id `dev.mark.lineover.debug` so both can be installed side by side.
- **NDK in CI** is installed by `tools/ci/install-native-toolchain.sh` (verified, retried)
  before Gradle runs. Do not rely on AGP's own NDK download: it is not retried and a
  truncated archive fails the build.
- **Emulator = SwiftShader.** Instrumented tests prove correctness only. Performance is
  measured on the phone (`:benchmark`), never in CI.

## Testing conventions

- **Golden tests:** add the reference to `tools/golden/reference.py`, regenerate, and
  compare with `GoldenAssert` (C++ and Kotlin). Tolerances: 8-bit max |diff| ≤ 2/255 and
  mean ≤ 0.5/255; float ops max 1e-3; GPU vs CPU reference max 3/255. A failure writes a
  diff heatmap PNG.
- **Screenshot tests:** Roborazzi, `@GraphicsMode(NATIVE)`, `@Config(sdk = [35])`, wrapped
  in `LineOverTheme`, recorded images committed under `src/test/screenshots`. Robolectric's
  SDK 36 runtime needs Java 21 and CI runs JDK 17, so screenshot tests use SDK 35. Verify
  on JDK 17 before pushing.
- **Engine code** gets a host ctest and, when it touches GL, an instrumented test.
