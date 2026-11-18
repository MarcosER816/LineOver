package dev.mark.lineover.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The parameters of one non-destructive edit step. Values are immutable; a layer's look
 * is the ordered list of its ops applied to the layer source by the engine (the native
 * engine reads the same JSON, see core/engine-native `graph.h`).
 *
 * Every op has a stable [SerialName]. Never rename or reuse one: project files
 * (.loproj) written by older versions must keep loading.
 */
@Serializable
sealed interface OpParams {
    /** True when the op leaves every pixel unchanged. */
    val isIdentity: Boolean

    /** Leaves the image unchanged. Used to validate the pipeline end to end. */
    @Serializable
    @SerialName("identity")
    data object Identity : OpParams {
        override val isIdentity: Boolean get() = true
    }

    /**
     * Multiplies RGB by 2^[stops] in the linear working space. A test op proving that
     * params flow from the UI through the graph to both engine paths; the Exposure tool
     * itself arrives in Stage 5.
     */
    @Serializable
    @SerialName("exposure_test")
    data class ExposureTest(
        val stops: Float,
    ) : OpParams {
        override val isIdentity: Boolean get() = stops == 0f
    }

    /** Exposure: RGB × 2^[ev] (ev in −5..5). Stage 5. */
    @Serializable
    @SerialName("exposure")
    data class Exposure(
        val ev: Float,
    ) : OpParams {
        override val isIdentity: Boolean get() = ev == 0f
    }

    /** Contrast (−100..100): an S-curve in log space around mid grey 0.18. */
    @Serializable
    @SerialName("contrast")
    data class Contrast(
        val amount: Float,
    ) : OpParams {
        override val isIdentity: Boolean get() = amount == 0f
    }

    /** Brightness (−100..100): a midtone gamma. */
    @Serializable
    @SerialName("brightness")
    data class Brightness(
        val amount: Float,
    ) : OpParams {
        override val isIdentity: Boolean get() = amount == 0f
    }

    /** Whites (−100..100): moves the white endpoint by up to ±1 stop. */
    @Serializable
    @SerialName("whites")
    data class Whites(
        val amount: Float,
    ) : OpParams {
        override val isIdentity: Boolean get() = amount == 0f
    }

    /** Blacks (−100..100): moves the black endpoint by up to ±1 stop. */
    @Serializable
    @SerialName("blacks")
    data class Blacks(
        val amount: Float,
    ) : OpParams {
        override val isIdentity: Boolean get() = amount == 0f
    }

    /** Highlights and Shadows (−100..100 each) on an edge-aware (guided-filter) base. */
    @Serializable
    @SerialName("highlights_shadows")
    data class HighlightsShadows(
        val highlights: Float,
        val shadows: Float,
    ) : OpParams {
        override val isIdentity: Boolean get() = highlights == 0f && shadows == 0f
    }

    /** White balance: Bradford adaptation from the (temperature K, tint) white to D65. Stage 6. */
    @Serializable
    @SerialName("white_balance")
    data class WhiteBalance(
        val temperature: Float,
        val tint: Float,
    ) : OpParams {
        override val isIdentity: Boolean get() = temperature == ColorAdjustments.DEFAULT_KELVIN && tint == 0f
    }

    /** Vibrance (-100..100): boosts less saturated colours more, spares skin. */
    @Serializable
    @SerialName("vibrance")
    data class Vibrance(
        val amount: Float,
    ) : OpParams {
        override val isIdentity: Boolean get() = amount == 0f
    }

    /** Saturation (-100..100) in OKLCh. */
    @Serializable
    @SerialName("saturation")
    data class Saturation(
        val amount: Float,
    ) : OpParams {
        override val isIdentity: Boolean get() = amount == 0f
    }

    /** HSL / Color Mixer: 8 bands (red … magenta) of hue, saturation, luminance (-100..100). */
    @Serializable
    @SerialName("hsl_mixer")
    data class HslMixer(
        val hue: List<Float>,
        val saturation: List<Float>,
        val luminance: List<Float>,
    ) : OpParams {
        override val isIdentity: Boolean get() = (hue + saturation + luminance).all { it == 0f }
    }

    /** Invert (on transfer-encoded values: mid grey stays mid grey). */
    @Serializable
    @SerialName("invert")
    data object Invert : OpParams {
        override val isIdentity: Boolean get() = false
    }

    /** Posterize to [levels] steps (2..32) per channel. */
    @Serializable
    @SerialName("posterize")
    data class Posterize(
        val levels: Float,
    ) : OpParams {
        override val isIdentity: Boolean get() = levels < 2f
    }

    /** Threshold at [level] (0..1) of encoded luminance: black or white. */
    @Serializable
    @SerialName("threshold")
    data class Threshold(
        val level: Float,
    ) : OpParams {
        override val isIdentity: Boolean get() = false
    }

    // ---- Stage 7: curves and grading. Flags are 0/1 floats (the engine reads numbers). ----

    /** Point curves: [x0, y0, x1, y1, …] per channel (empty = identity); [free] = 1 allows inversions. */
    @Serializable
    @SerialName("curves")
    data class Curves(
        val free: Float,
        val rgb: List<Float>,
        val red: List<Float>,
        val green: List<Float>,
        val blue: List<Float>,
        val luma: List<Float>,
    ) : OpParams {
        override val isIdentity: Boolean get() = listOf(rgb, red, green, blue, luma).all { MonotoneCurve.isIdentity(it) }
    }

    /** Parametric curve: region amounts (-100..100) and the three split points (0..1). */
    @Serializable
    @SerialName("parametric_curve")
    data class ParametricCurve(
        val highlights: Float,
        val lights: Float,
        val darks: Float,
        val shadows: Float,
        val split1: Float,
        val split2: Float,
        val split3: Float,
    ) : OpParams {
        override val isIdentity: Boolean get() = highlights == 0f && lights == 0f && darks == 0f && shadows == 0f
    }

    /** Levels: 4 rows (master, red, green, blue) × (in black, gamma, in white, out black, out white). */
    @Serializable
    @SerialName("levels")
    data class Levels(
        val values: List<Float>,
    ) : OpParams {
        override val isIdentity: Boolean get() = values == GradeAdjustments.LEVELS_IDENTITY
    }

    /** Color Grading: wheels (hue°, saturation 0..100, luminance -100..100), blending, balance. */
    @Serializable
    @SerialName("color_grading")
    data class ColorGrading(
        val blending: Float,
        val balance: Float,
        val shadows: List<Float>,
        val midtones: List<Float>,
        val highlights: List<Float>,
        val global: List<Float>,
    ) : OpParams {
        override val isIdentity: Boolean
            get() = listOf(shadows, midtones, highlights, global).all { it[1] == 0f && it[2] == 0f }
    }

    /** Color Balance: (cyan-red, magenta-green, yellow-blue) per tone range; [preserve] = 1 keeps luminosity. */
    @Serializable
    @SerialName("color_balance")
    data class ColorBalance(
        val preserve: Float,
        val shadows: List<Float>,
        val midtones: List<Float>,
        val highlights: List<Float>,
    ) : OpParams {
        override val isIdentity: Boolean get() = (shadows + midtones + highlights).all { it == 0f }
    }

    /** Selective Color: 9 families × (C, M, Y, K); [relative] = 1 for relative mode. */
    @Serializable
    @SerialName("selective_color")
    data class SelectiveColor(
        val relative: Float,
        val values: List<Float>,
    ) : OpParams {
        override val isIdentity: Boolean get() = values.all { it == 0f }
    }

    /** Channel Mixer: 3×3 percent matrix and constants; [monochrome] = 1 uses the first row. */
    @Serializable
    @SerialName("channel_mixer")
    data class ChannelMixer(
        val monochrome: Float,
        val matrix: List<Float>,
        val constant: List<Float>,
    ) : OpParams {
        override val isIdentity: Boolean
            get() = monochrome == 0f && matrix == GradeAdjustments.MIXER_IDENTITY && constant.all { it == 0f }
    }

    /** Black & White: 6 band mixes (reds … magentas, -100..100) and a tint. */
    @Serializable
    @SerialName("black_and_white")
    data class BlackAndWhite(
        val tintHue: Float,
        val tintSaturation: Float,
        val mix: List<Float>,
    ) : OpParams {
        override val isIdentity: Boolean get() = false
    }

    /** Photo Filter: OKLCh hue / saturation of the filter, density %, [preserve] = 1 keeps luminosity. */
    @Serializable
    @SerialName("photo_filter")
    data class PhotoFilter(
        val hue: Float,
        val saturation: Float,
        val density: Float,
        val preserve: Float,
    ) : OpParams {
        override val isIdentity: Boolean get() = density == 0f
    }

    /** Gradient Map: stops [position, OKLab L, a, b, …]. */
    @Serializable
    @SerialName("gradient_map")
    data class GradientMap(
        val stops: List<Float>,
    ) : OpParams {
        override val isIdentity: Boolean get() = false
    }

    /** Calibration: hue and saturation shifts (-100..100) of the red, green and blue primaries. */
    @Serializable
    @SerialName("calibration")
    data class Calibration(
        val hue: List<Float>,
        val saturation: List<Float>,
    ) : OpParams {
        override val isIdentity: Boolean get() = (hue + saturation).all { it == 0f }
    }

    /**
     * Crop, straighten, rotate / flip, Upright, the manual transform and perspective crop
     * composed into ONE matrix (Stage 9, [GeometryMath]): output normalised (u, v) ->
     * source pixel coordinates (edges at 0 and [sourceWidth]) before lens distortion; then
     * Brown-Conrady [k1] / [k2] (r normalised by the half diagonal) and a radial scale per
     * channel for chromatic aberration. The engine applies it with one bicubic resample;
     * lens vignetting correction ([vignette], [vignetteMid]) runs first in source space.
     * [settings] keeps the panel values (the native engine ignores the object).
     */
    @Serializable
    @SerialName("geometry")
    data class Geometry(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val k1: Double = 0.0,
        val k2: Double = 0.0,
        val caRed: Double = 0.0,
        val caBlue: Double = 0.0,
        val vignette: Float = 0f,
        val vignetteMid: Float = 50f,
        val settings: GeometryAdjustments = GeometryAdjustments(),
        val matrix: List<Double>,
    ) : OpParams {
        override val isIdentity: Boolean
            get() =
                outputWidth == sourceWidth && outputHeight == sourceHeight && k1 == 0.0 && k2 == 0.0 &&
                    caRed == 0.0 && caBlue == 0.0 && vignette == 0f &&
                    matrix == listOf(sourceWidth.toDouble(), 0.0, 0.0, 0.0, sourceHeight.toDouble(), 0.0, 0.0, 0.0, 1.0)
    }
}
