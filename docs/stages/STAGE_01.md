# Stage 01 — Design system and components

## Goal

The `Lo*` component library in `:core:designsystem` on the Stage 0 tokens, so every later
screen and tool is assembled from tested parts. No real screens.

## Definition of Done

- [x] All components exist, each with a `@Preview`
- [x] Roborazzi screenshots of each component in its states (default, active, disabled,
      fontScale 2.0) at compact and expanded widths (28 component sheets + 2 gallery images)
- [x] Unit tests for the slider math (step, reset, fine mode, detent, clamping, taps)
- [x] CI green; APK built (links under Proof)

## What was built

| Area | Content |
| --- | --- |
| Theme | `LineOverTheme` (dark only): M3 colour scheme on the tokens, Roboto Flex typography, `LoShapes` 4/8/12/16 dp mapped to M3 `Shapes`, `MotionTokens` (renamed from `LoMotion`), `rememberReducedMotion()` observing `ANIMATOR_DURATION_SCALE` live |
| Haptics | `LoHaptics.tick()` = `CLOCK_TICK`, `confirm()` = `CONFIRM`; falls back to `VibrationEffect.EFFECT_TICK` / `EFFECT_CLICK` when the view performs nothing (`VIBRATE` permission added, a normal permission) |
| Icons | 33 Material Symbols Rounded icons (outlined, 24 dp) converted to vector drawables, `LoIcons.*` |
| Components | `LoSlider` (+ `LoSliderModel`), `LoToolButton`, `LoCategoryBar`, `LoPanel` (+ `LoPanelMath`), `LoIconButton`, `LoChip`, `LoSegmented`, `LoSwitchRow`, `LoColorWheel` (+ `LoColorWheelMath`), `LoCurveEditorPlaceholder`, `LoToast` / `LoToastHost` / `LoToastState`, `LoEmptyState`, `LoPlaceholderTool` |
| Gallery | `ComponentGalleryScreen` in the debug source set; `:app` debug builds show a debug menu (bug icon, top right) that opens it. Release builds use a no-op `DebugHost` from `app/src/nonDebug` and contain no gallery code (checked in the release dex) |
| Tests | `LoSliderModelTest` (15), `LoSliderDefaultsTest`, `LoColorWheelMathTest`, `LoPanelMathTest`, `LoToastStateTest`, `Compact/ExpandedComponentScreenshotTest` (14 each); instrumented `DebugMenuTest` (debug menu → gallery → back) |

## Tools delivered

None (no editor tools this stage). `TOOL_CATALOG.md` has a new **Design system** section
listing the 17 design-system items as `done`.

## Decisions and deviations

1. **Slider interaction details** (where the brief left room):
   - Dragging is *relative*: the thumb moves with the finger's movement and never jumps
     to it; this is what makes fine mode possible.
   - Fine mode: the finger is at least **2 row heights below the slider's centre** → ¼ speed.
   - Detent: a sticky zone of ±1 % of the range around the default; reaching it or
     crossing it fast both give one `CLOCK_TICK`.
   - Taps within 24 dp of the thumb do not step (a missed drag should not change the value).
     A double tap resets; its first tap still steps, which the reset then overrides, so a
     single tap is not delayed by 300 ms waiting for a possible second one.
   - A mostly vertical movement is left to the parent, so a panel full of sliders scrolls.
   - The value label turns accent only while dragging (accent = active state).
2. **Font**: the variable Roboto Flex stays bundled (Stage 0), not downloadable, so
   screenshots are identical on CI and phones.
3. **Icons as vector drawables** converted from the official SVGs, instead of an icon
   font or the `material-icons-extended` library: no new dependency, only the icons used
   ship, and they tint like any drawable.
4. **Gradient colours are tokens** (`LoColors.TrackTemperature*`, `TrackTint*`, `HueStops`,
   `WheelCenter`), so no colour is hard-coded in a component.
5. **Screenshot windows are taller than a phone** (411×1800 dp, 1024×1400 dp) so each state
   sheet fits in one image; the first recording at 891 dp cut off the panel's fontScale row.
6. **Tool buttons widen with the font scale** (max width × fontScale): at fontScale 2.0
   "Favorites" was cut to "Favorit…". Found by the fontScale screenshot.
7. **detekt `MatchingDeclarationName` disabled**: Compose files are named after their main
   composable, which the rule does not consider.
8. **Branch**: work is on the session branch `ccr-a94fce54-a1j7s3` rather than
   `stage-01-design-system` (constraint of the hosted session), as in Stage 0.
9. **Version** 0.1.0 (versionCode 2).

## Dependencies added (R10)

| Dependency | Version | Licence | Why |
| --- | --- | --- | --- |
| kotlinx-coroutines-core (to `:core:designsystem`) | 1.11.0 (already in the catalog) | Apache-2.0 | `LoToastState` suspends until acted on / timed out |
| kotlinx-coroutines-test (test only) | 1.11.0 (already in the catalog) | Apache-2.0 | `LoToastStateTest` |
| Material Symbols Rounded (33 icons, as vector drawables) | repo head, 2026-11-17 | Apache-2.0 | Icons; licence in `core/designsystem/MATERIAL_SYMBOLS_LICENSE.txt` |

No library was added to the version catalog.

## Golden images changed (R4)

None changed. New screenshots: `core/designsystem/src/test/screenshots/components/{compact,expanded}/*.png`.
`design_tokens.png` is unchanged.

## Troubleshooting log (R11)

| Symptom | Root cause | Fix |
| --- | --- | --- |
| `LoSliderModelTest`: 1.2299999 instead of 1.23; `stepBy(0, +1)` gave 0.009999888 | The float `0.01f` is 0.0099999998; multiplying it accumulates the error | Quantise in double precision on the *decimal* values of min and step |
| Max of a range that is not a whole number of steps was unreachable (0..10 step 3 stopped at 9) | Pure grid rounding | `max` is a valid end point when it is nearer than the last grid point |
| Panel screenshot missing its fontScale row | Image limited to the 891 dp test window | Taller test windows (decision 5) |
| "Favorit…" at fontScale 2.0 | Tool button max width fixed in dp | Scale the max width with the font scale (decision 6) |

Plan B used: no.

## Proof

- CI run: see the Stage 1 report (PR and `main` run links)
- APK artifact: `lineover-<sha>` on the `main` run
- Screenshot images: committed under `core/designsystem/src/test/screenshots/components/`;
  the `screenshot-diffs` artifact of the `unit` job holds the Roborazzi report

## Phone checklist

POCO F7 Ultra. The gallery is only in the **debug** APK: download
`lineover-<sha>-debug.apk` from the run (it installs as a second app next to LineOver):

- [ ] Open LineOver (debug), tap the bug icon (top right) → **Component gallery**.
- [ ] Drag each slider: it follows the finger smoothly at 120 Hz, no jank, and the value
      turns blue while dragging.
- [ ] Drag a slider and move the finger well below it (about two slider heights): it moves
      at a quarter of the speed.
- [ ] Drag Contrast across 0: you feel a light tick, and it sticks briefly at 0.
- [ ] Double-tap a slider: it jumps back to its default with a firmer haptic.
- [ ] Tap left or right of a thumb: the value changes by one step.
- [ ] Hold a slider with one finger and touch the screen with a second: "Second finger:
      down" appears at the top of the slider card.
- [ ] Category bar: fling it; it settles on a category. Tap one: it turns blue with a bar.
- [ ] Turn on "Show panel": drag the handle up/down or tap it; it settles at two heights.
- [ ] Colour wheel: drag the puck; double tap resets it to the centre.
- [ ] "Show undo toast": the toast slides in; tap Undo → "Last result: Undone".
- [ ] Settings → Display → Font size: largest. Reopen the gallery: nothing is cut off.
- [ ] Developer options → Animator duration scale: off. The toast and panel now snap.
- [ ] Back (gesture or arrow) returns to the LineOver screen.

## Open questions (R12)

Answered by Mark on 2026-11-17:

1. **Accent colour**: keep blue `#5B9DFF`.
2. **Haptic strength on HyperOS**: comfortable; no change.

Follow-up request: the CI download should offer one APK to install. Since then the run has
two unzipped artifacts: `lineover-<sha>.apk` (signed release, the app) and
`lineover-<sha>-debug.apk` (test build, only for checklists that need the debug menu).
