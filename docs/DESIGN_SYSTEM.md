# Design system

One dark theme, `LineOverTheme` (`:core:designsystem`). Components are prefixed `Lo`
(`LoSlider`, `LoPanel`, …). Tokens live in code as `LoColors`, `LoSpacing`, `LoDimens`,
`LoType`, `LoMotion`; this document and the code must always agree. The screenshot test
`DesignTokensScreenshotTest` locks colours and type.

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
| `canvasSurround` | `#121212` / `#777777` | Area around the image: dark (default) or mid grey |

The UI stays neutral grey so it never biases colour judgement; accent is used sparingly.

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

- **Never animate a value while it is being dragged.** Sliders, curves and canvas gestures
  follow the finger 1:1.
- **Honour reduced motion.** When the system animator scale is 0 (`LocalReducedMotion`),
  every `LoMotion` spec becomes a snap.

## Layout

- Phone portrait: top bar · canvas · bottom panel · tool strip · category bar.
- Tablet, foldable and landscape (from Stage 2, D4): the panel moves to a 320 dp side
  panel and the canvas keeps the remaining space.
- The canvas is always the largest element; chrome never covers more than it must.

## Category order (fixed)

Favorites · Presets · Auto · Light · Color · Effects · Detail · Optics · Crop · Masks ·
Select · Retouch · Paint · Filters · Liquify · Text · Shapes · AI

The order never changes and reserved slots never move (R9). See `TOOL_CATALOG.md`.
