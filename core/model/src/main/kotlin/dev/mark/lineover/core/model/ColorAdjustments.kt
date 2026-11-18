package dev.mark.lineover.core.model

import kotlin.math.roundToInt

/** The 8 bands of the HSL / Color Mixer, in order. */
enum class HslBand(
    val label: String,
) {
    Red("Red"),
    Orange("Orange"),
    Yellow("Yellow"),
    Green("Green"),
    Aqua("Aqua"),
    Blue("Blue"),
    Purple("Purple"),
    Magenta("Magenta"),
}

/** What the HSL panel's sliders change. */
enum class HslChannel(
    val label: String,
) {
    Hue("Hue"),
    Saturation("Saturation"),
    Luminance("Luminance"),
}

/**
 * The Color basics (Stage 6). [beforeLight] runs first (white balance is a scene
 * correction); [afterLight] after the Light tools: Vibrance → Saturation → HSL → Invert →
 * Posterize → Threshold. Controls at their default add no op.
 */
data class ColorAdjustments(
    val temperature: Float = DEFAULT_KELVIN,
    val tint: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    val hsl: Map<HslChannel, List<Float>> = emptyMap(),
    val invert: Boolean = false,
    /** Posterize levels (2..32), 0 = off. */
    val posterize: Int = 0,
    /** Threshold level (0..1), null = off. */
    val threshold: Float? = null,
) {
    fun hsl(channel: HslChannel): List<Float> = hsl[channel] ?: ZERO_BANDS

    fun withHsl(
        channel: HslChannel,
        band: HslBand,
        value: Float,
    ): ColorAdjustments {
        val values = hsl(channel).toMutableList()
        values[band.ordinal] = value.coerceIn(-RANGE, RANGE)
        return copy(hsl = hsl + (channel to values))
    }

    /** Adds [delta] × weight to every band (targeted adjustment: the bands under the finger). */
    fun nudgeHsl(
        channel: HslChannel,
        weights: FloatArray,
        delta: Float,
    ): ColorAdjustments {
        val values = hsl(channel).mapIndexed { i, v -> (v + delta * weights[i]).coerceIn(-RANGE, RANGE) }
        return copy(hsl = hsl + (channel to values))
    }

    fun withWhiteBalance(
        kelvin: Float,
        tint: Float,
    ) = copy(temperature = kelvin.coerceIn(MIN_KELVIN, MAX_KELVIN), tint = tint.coerceIn(-TINT_RANGE, TINT_RANGE))

    val isIdentity: Boolean get() = beforeLight().isEmpty() && afterLight().isEmpty()

    fun beforeLight(): List<OpParams> = listOf(OpParams.WhiteBalance(temperature, tint)).filterNot { it.isIdentity }

    fun afterLight(): List<OpParams> =
        listOfNotNull(
            OpParams.Vibrance(vibrance),
            OpParams.Saturation(saturation),
            OpParams.HslMixer(hsl(HslChannel.Hue), hsl(HslChannel.Saturation), hsl(HslChannel.Luminance)),
            OpParams.Invert.takeIf { invert },
            OpParams.Posterize(posterize.toFloat()),
            threshold?.let { OpParams.Threshold(it) },
        ).filterNot { it.isIdentity }

    companion object {
        const val DEFAULT_KELVIN = 6504f
        const val MIN_KELVIN = 2000f
        const val MAX_KELVIN = 50000f
        const val TINT_RANGE = 150f
        const val RANGE = 100f
        const val DEFAULT_POSTERIZE = 6
        const val DEFAULT_THRESHOLD = 0.5f
        private val ZERO_BANDS = List(HslBand.entries.size) { 0f }

        private const val MIRED = 1_000_000f

        /** Temperature slider position (0..1, linear in mireds) for [kelvin]. */
        fun temperaturePosition(kelvin: Float): Float = (MIRED / MIN_KELVIN - MIRED / kelvin) / (MIRED / MIN_KELVIN - MIRED / MAX_KELVIN)

        /** Kelvin at slider [position] (0..1): blue (2000 K) to amber (50000 K). */
        fun kelvinAt(position: Float): Float {
            val mired = MIRED / MIN_KELVIN - position.coerceIn(0f, 1f) * (MIRED / MIN_KELVIN - MIRED / MAX_KELVIN)
            return MIRED / mired
        }

        /** "5600 K" with 50 K steps (Lightroom-like). */
        fun kelvinText(kelvin: Float): String = "${(kelvin / KELVIN_STEP).roundToInt() * KELVIN_STEP} K"

        private const val KELVIN_STEP = 50

        /** Reads the Color values back from a layer's ops (other ops are ignored). */
        fun fromOps(ops: List<OpParams>): ColorAdjustments {
            var color = ColorAdjustments()
            for (op in ops) {
                color =
                    when (op) {
                        is OpParams.WhiteBalance -> {
                            color.copy(temperature = op.temperature, tint = op.tint)
                        }

                        is OpParams.Vibrance -> {
                            color.copy(vibrance = op.amount)
                        }

                        is OpParams.Saturation -> {
                            color.copy(saturation = op.amount)
                        }

                        is OpParams.HslMixer -> {
                            color.copy(
                                hsl =
                                    mapOf(
                                        HslChannel.Hue to op.hue,
                                        HslChannel.Saturation to op.saturation,
                                        HslChannel.Luminance to op.luminance,
                                    ),
                            )
                        }

                        is OpParams.Invert -> {
                            color.copy(invert = true)
                        }

                        is OpParams.Posterize -> {
                            color.copy(posterize = op.levels.roundToInt())
                        }

                        is OpParams.Threshold -> {
                            color.copy(threshold = op.level)
                        }

                        else -> {
                            color
                        }
                    }
            }
            return color
        }
    }
}

/** The whole background edit: Color before Light (white balance), Light, then the rest of Color. */
fun editOps(
    light: LightAdjustments,
    color: ColorAdjustments,
): List<OpParams> = color.beforeLight() + light.toOps() + color.afterLight()
