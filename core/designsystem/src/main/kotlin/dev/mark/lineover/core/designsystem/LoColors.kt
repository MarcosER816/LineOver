package dev.mark.lineover.core.designsystem

import androidx.compose.ui.graphics.Color

/** Colour tokens (docs/DESIGN_SYSTEM.md). LineOver has a single, dark theme. */
object LoColors {
    val Bg = Color(0xFF0E0E10)
    val Surface1 = Color(0xFF161618)
    val Surface2 = Color(0xFF1E1E21)
    val Surface3 = Color(0xFF26262A)
    val Outline = Color(0xFF3A3A3F)

    val TextPrimary = Color(0xFFECECEE)
    val TextSecondary = Color(0xFFA1A1A8)
    val TextDisabled = Color(0xFF5C5C63)

    val Accent = Color(0xFF5B9DFF)
    val OnAccent = Color(0xFF0B1A33)
    val Danger = Color(0xFFFF5A5F)
    val Success = Color(0xFF3DDC97)
    val Warning = Color(0xFFFFB547)

    /** #FF3B5C at 45 % opacity, drawn over masked areas. */
    val MaskOverlay = Color(0x73FF3B5C)

    /** Highlight / shadow clipping warnings. */
    val ClipHi = Color(0xFFFF2D55)
    val ClipLo = Color(0xFF2D7BFF)

    /** Area around the canvas: dark by default, mid grey as the alternative. */
    val CanvasSurroundDark = Color(0xFF121212)
    val CanvasSurroundGrey = Color(0xFF777777)

    /** Slider and wheel thumb. */
    val Thumb = TextPrimary

    /** Gradient tracks: temperature (cool -> warm) and tint (green -> magenta). */
    val TrackTemperatureCool = Color(0xFF3A7BFF)
    val TrackTemperatureWarm = Color(0xFFFFB13B)
    val TrackTintGreen = Color(0xFF47C75A)
    val TrackTintMagenta = Color(0xFFD94BD0)

    /** Hue stops for the hue track and the colour wheel, red -> red, evenly spaced. */
    val HueStops =
        listOf(
            Color(0xFFFF3B30),
            Color(0xFFFFCC00),
            Color(0xFF34C759),
            Color(0xFF32D7E6),
            Color(0xFF2D7BFF),
            Color(0xFFE447FF),
            Color(0xFFFF3B30),
        )

    /** Centre of the colour wheel: neutral, so zero saturation reads as grey. */
    val WheelCenter = Color(0xFF8E8E93)
}
