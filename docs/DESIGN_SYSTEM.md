# Design system

One dark theme, `LineOverTheme` (`:core:designsystem`). Components are prefixed `Lo`
(`LoSlider`, `LoPanel`, …). Tokens live in code as `LoColors`, `LoSpacing`, `LoDimens`,
`LoShapes`, `LoType`, `MotionTokens`; this document and the code must always agree. The
screenshot test `DesignTokensScreenshotTest` locks colours and type, and
`Compact/ExpandedComponentScreenshotTest` lock every component.

**Never hard-code a colour outside `LoColors`** (components, gallery and tests included).
There is no light theme.

## Colour

| Token | Value | Use |
| --- | --- | --- |
| `bg` | `#0E0E10` | Window background, splash |
| `surface1` | `#161618` | Bars, panels |
| `surface2` | `#1E1E21` | Raised elements, selected rows |
| `surface3` | `#26262A` | Controls on panels, pressed states |
| `outline` | `#3A3A3F` | Dividers, control borders |
| `textPrimary` | `#ECECEE` | Main text, icons |
| `textSecondary` | `#A1A1A8` | Labels, inactive icons |
| `textDisabled` | `#5C5C63` | Disabled text and icons |
| `accent` | `#5B9DFF` | Active tool, slider fill, primary actions |
| `onAccent` | `#0B1A33` | Text / icons on accent |
| `danger` | `#FF5A5F` | Destructive actions, errors |
| `success` | `#3DDC97` | Completed, saved |
| `warning` | `#FFB547` | Warnings |
| `maskOverlay` | `#FF3B5C` at 45 % | Mask overlay on the canvas |
| `clipHi` | `#FF2D55` | Highlight clipping warning |
| `clipLo` | `#2D7BFF` | Shadow clipping warning |
| `histogramRed` / `histogramGreen` / `histogramBlue` | `#FF5A5F` / `#3DDC97` / `#5B9DFF` at 55 % | Histogram channels |
| `histogramLuma` | `textPrimary` | Histogram luminance outline |
| `canvasSurround` | `#121212` / `#777777` | Area around the image: dark (default) or mid grey |
| `cropScrim` / `cropLine` / `cropGrid` | black at 60 % / white at 90 % / white at 50 % | Crop overlay: outside the crop, frame and handles, composition grid (Stage 9) |

Gradient and wheel tokens (only for tracks that show what a value does):

| Token | Value | Use |
| --- | --- | --- |
| `trackTemperatureCool` → `trackTemperatureWarm` | `#3A7BFF` → `#FFB13B` | Temperature slider track |
| `trackTintGreen` → `trackTintMagenta` | `#47C75A` → `#D94BD0` | Tint slider track |
| `hueStops` | `#FF3B30` `#FFCC00` `#34C759` `#32D7E6` `#2D7BFF` `#E447FF` `#FF3B30` | Hue track, colour wheel |
| `wheelCenter` | `#8E8E93` | Centre (zero saturation) of the colour wheel |
| `thumb` | = `textPrimary` | Slider and wheel thumbs |

The UI stays neutral grey so it never biases colour judgement. **Accent is only for the
active or selected state** (selected tool, slider being dragged, slider fill, selected
chip / segment / switch) and for primary actions.

## Spacing and size

| Token | Value |
| --- | --- |
| Spacing scale | 4 / 8 / 12 / 16 / 24 / 32 dp |
| Touch target | ≥ 48 dp |
| Top bar | 56 dp |
| Category bar | 64 dp |
| Tool strip | 56 dp |
| Bottom panel | 160–280 dp, never more than 40 % of the window height |
| Side panel (wide screens) | 320 dp |

## Shapes

| Token | Radius | Use |
| --- | --- | --- |
| `LoShapes.ExtraSmall` | 4 dp | Indicators, handles, tags |
| `LoShapes.Small` | 8 dp | Chips, segments, small cards |
| `LoShapes.Medium` | 12 dp | Cards, toasts, segmented control |
| `LoShapes.Large` | 16 dp | Panels (`PanelTop` rounds only the top corners) |

## Icons

Material Symbols **Rounded**, outlined (FILL 0, weight 400, grade 0), 24 dp, Apache-2.0.
Each icon is a vector drawable converted from the official SVG
(`core/designsystem/src/main/res/drawable/lo_ic_*.xml`) and exposed as `LoIcons.*`; no
icon library is bundled. Components tint icons: `textPrimary` / `textSecondary`,
`accent` when selected, `textDisabled` when disabled. Icon-only controls always have a
content description.

## Type

Roboto Flex (bundled variable font, SIL OFL 1.1), **tabular numerals** everywhere
(`tnum`) so values do not jitter while a slider moves.

| Style | Size | Use |
| --- | --- | --- |
| `Micro` | 11 sp | Dense labels, badges |
| `Caption` | 12 sp | Tool labels, secondary values |
| `Body` | 14 sp | Default text, slider values |
| `Title` | 16 sp | Panel titles, list items |
| `Headline` | 20 sp | Screen titles |
| `Display` | 24 sp | Large headings |

## Motion

| Token | Curve | Duration |
| --- | --- | --- |
| Enter | cubic-bezier(0.05, 0.7, 0.1, 1) | 350 ms |
| Exit | cubic-bezier(0.3, 0, 0.8, 0.15) | 200 ms |
| Standard | cubic-bezier(0.2, 0, 0, 1) | 250 ms |
| Panels | spring, damping ratio 0.85, stiffness 400 | — |

- **Never animate a value while it is being dragged.** Sliders, wheels, curves and canvas
  gestures follow the finger 1:1; a double-tap reset is also immediate.
- **Honour reduced motion.** `rememberReducedMotion()` reads and keeps observing
  `Settings.Global.ANIMATOR_DURATION_SCALE`; when it is 0, `LocalReducedMotion` is true and
  every `MotionTokens` spec becomes a snap.

## Haptics

`rememberLoHaptics()`: `tick()` uses `HapticFeedbackConstants.CLOCK_TICK` (slider passing
its default), `confirm()` uses `CONFIRM` (double-tap reset). If the view reports that no
feedback was performed, it falls back to `VibrationEffect.EFFECT_TICK` / `EFFECT_CLICK`
through the `Vibrator` (some OEM builds ignore the view constants).

## Accessibility

- Every control has a touch target of at least 48 dp (`minimumInteractiveComponentSize`
  or an explicit minimum height), including when drawn smaller (chips are 32 dp tall).
- Every control has a role and a content description; sliders expose their range and
  value (`progressBarRangeInfo`, `setProgress`), so TalkBack can adjust them.
- Values use tabular numerals. Layouts use minimum heights rather than fixed ones, and
  tool buttons widen with the font scale, so nothing is cut off at font scale 2.0
  (checked by the `fontScale 2.0` row of every component screenshot).

## Components (`:core:designsystem`, package `…designsystem.component`)

| Component | What it is |
| --- | --- |
| `LoSlider` | Value slider: centred-zero (`FromDefault`) or min-start (`FromStart`) fill; tabular value label; relative drag over the whole row; fine mode (finger ≥ 2 row heights below the slider → ¼ speed); tap left/right of the thumb = ±1 step; double tap resets (haptic confirm); haptic detent at the default; optional gradient track (`Temperature`, `Tint`, `Hue`); reports a second finger down (`onSecondaryPointerChange`) for the clipping preview. Math in `LoSliderModel` |
| `LoToolButton` | 24 dp icon + 11 sp label; selected = accent tint + 2 dp indicator; optional long press (pin to Favorites) |
| `LoCategoryBar` | Scrolling, snapping category bar (64 dp); after its first layout it jumps to an off-screen selected category, later changes scroll it into view |
| `LoPanel` | Bottom panel with detents 160 / 280 dp (≤ 40 % of the window, or of `maxHeight`: the editor passes 40 % of the canvas), spring settle, drag or tap the handle |
| `LoIconButton` | 48 dp icon button, optional selected (toggle) state |
| `LoChip` | Selectable chip, 32 dp drawn, 48 dp touch |
| `LoSegmented` | Segmented control; optional `descriptions` give short labels full spoken names |
| `LoSwitchRow` | Label (+ description) with a switch; the whole row toggles |
| `LoColorWheel` | Hue (angle) / saturation (radius) wheel for colour grading; double tap resets |
| `LoCurveEditorPlaceholder` | Reserves the curve editor (Stage 7); kept for slots of later curve-like tools |
| `LoCurveEditor` | Full-width curve editor (`aspectRatio`: square on wide layouts, 2.9 : 1 on phones so the photo stays visible): tap adds a point on the curve, drag moves, drag off the graph deletes (turns red first); 3× magnifier (at most the graph's height) and "In · Out" (0–255) readout in the graph's lower right corner; optional histogram behind the curve. Geometry in `LoCurveEditorMath` |
| `LoGradeWheel` | `LoColorWheel` inside a 270° luminance ring (open at the bottom): drag inside for hue / saturation, drag the ring for luminance; double tap the centre or the ring resets it. Geometry in `LoGradeWheelMath` |
| `LoToast` / `LoToastHost` / `LoToastState` | Toast and undo snackbar, one at a time |
| `LoEmptyState` | Icon, title, body, optional action |
| `LoPlaceholderTool` | "Coming in Stage N" for a reserved tool slot |

The debug build has a **component gallery** (`ComponentGalleryScreen`, debug source set
of `:core:designsystem`), opened from the debug menu (bug icon, top right). It is not
part of release builds.

## Layout

- Phone portrait: top bar · canvas (the bottom panel overlays its lower ≤ 40 %) ·
  selection options (Select) · tool strip · category bar.
- Tablet, unfolded foldable and landscape (D4): the category bar becomes an 88 dp rail on
  the left and panels and sheets dock in a 320 dp column on the right.
- **Frozen after Stage 3** (R9): see ARCHITECTURE, "Editor shell".
- The canvas is always the largest element; chrome never covers more than it must.

## Category order (fixed)

Favorites · Presets · Auto · Light · Color · Effects · Detail · Optics · Crop · Masks ·
Select · Retouch · Paint · Filters · Liquify · Text · Shapes · AI

The order never changes and reserved slots never move (R9). See `TOOL_CATALOG.md`.
