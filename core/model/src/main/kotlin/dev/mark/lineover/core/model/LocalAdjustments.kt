package dev.mark.lineover.core.model

import kotlin.math.ln
import kotlin.math.pow

/** One slider of a local mask's adjustment set (Stage 17), with its range and the op it drives. */
enum class LocalParam(
    val label: String,
    val min: Float,
    val max: Float,
) {
    Exposure("Exposure", -4f, 4f),
    Contrast("Contrast", -100f, 100f),
    Highlights("Highlights", -100f, 100f),
    Shadows("Shadows", -100f, 100f),
    Whites("Whites", -100f, 100f),
    Blacks("Blacks", -100f, 100f),
    Temperature("Temp", -100f, 100f),
    Tint("Tint", -100f, 100f),
    Saturation("Saturation", -100f, 100f),
    Texture("Texture", -100f, 100f),
    Clarity("Clarity", -100f, 100f),
    Dehaze("Dehaze", -100f, 100f),
    Sharpness("Sharpness", 0f, 150f),
    Noise("Noise", 0f, 100f),
    ColorAmount("Color", 0f, 100f),
    ColorHue("Color hue", 0f, 360f),
}

/**
 * A local mask's full adjustment set (Stage 17): exposure, contrast, highlights, shadows, whites,
 * blacks, temp, tint, saturation, texture, clarity, dehaze, sharpness, noise and a colour tint.
 * They are the Stage 5-10 ops in an adjustment layer whose mask is the local mask, so the engine
 * reuses them (no second implementation). Temp and tint are relative to the photo: temp t moves
 * the white balance to 6504 K x 2^(t / 100) (warmer for t > 0), tint is added; the colour tint is
 * a Photo Filter of [colorHue] at [colorAmount] % density, luminosity kept.
 */
data class LocalAdjustments(
    val values: Map<LocalParam, Float> = emptyMap(),
) {
    operator fun get(p: LocalParam): Float = values[p] ?: default(p)

    fun with(
        p: LocalParam,
        value: Float,
    ): LocalAdjustments = copy(values = values + (p to value.coerceIn(p.min, p.max)))

    /** The ops, in the develop pipeline's order; neutral sliders add nothing. */
    fun toOps(): List<OpParams> {
        fun v(p: LocalParam) = this[p]
        return listOfNotNull(
            OpParams.WhiteBalance(kelvin(v(LocalParam.Temperature)), v(LocalParam.Tint)),
            OpParams.HighlightsShadows(v(LocalParam.Highlights), v(LocalParam.Shadows)),
            OpParams.Exposure(v(LocalParam.Exposure)),
            OpParams.Contrast(v(LocalParam.Contrast)),
            OpParams.Whites(v(LocalParam.Whites)),
            OpParams.Blacks(v(LocalParam.Blacks)),
            OpParams.Saturation(v(LocalParam.Saturation)),
            OpParams.PhotoFilter(v(LocalParam.ColorHue), FULL, v(LocalParam.ColorAmount), 1f),
            OpParams.Texture(v(LocalParam.Texture)),
            OpParams.Clarity(v(LocalParam.Clarity)),
            OpParams.Dehaze(v(LocalParam.Dehaze)),
            OpParams.NoiseReduction(v(LocalParam.Noise)),
            OpParams.Sharpen(v(LocalParam.Sharpness)),
        ).filterNot { it.isIdentity }
    }

    companion object {
        private const val FULL = 100f
        private const val DEFAULT_HUE = 35f // warm orange, like Lightroom's first swatch
        private const val STOPS = 100f

        fun default(p: LocalParam): Float = if (p == LocalParam.ColorHue) DEFAULT_HUE else 0f

        fun kelvin(temp: Float): Float = ColorAdjustments.DEFAULT_KELVIN * 2f.pow(temp / STOPS)

        /** The sliders of an adjustment layer's [ops] (what [toOps] wrote). */
        @Suppress("CyclomaticComplexMethod") // a table: one branch per op
        fun fromOps(ops: List<OpParams>): LocalAdjustments {
            val m = mutableMapOf<LocalParam, Float>()
            for (op in ops) {
                when (op) {
                    is OpParams.WhiteBalance -> {
                        m[LocalParam.Temperature] = (ln(op.temperature / ColorAdjustments.DEFAULT_KELVIN) / ln(2f)) * STOPS
                        m[LocalParam.Tint] = op.tint
                    }

                    is OpParams.HighlightsShadows -> {
                        m[LocalParam.Highlights] = op.highlights
                        m[LocalParam.Shadows] = op.shadows
                    }

                    is OpParams.Exposure -> {
                        m[LocalParam.Exposure] = op.ev
                    }

                    is OpParams.Contrast -> {
                        m[LocalParam.Contrast] = op.amount
                    }

                    is OpParams.Whites -> {
                        m[LocalParam.Whites] = op.amount
                    }

                    is OpParams.Blacks -> {
                        m[LocalParam.Blacks] = op.amount
                    }

                    is OpParams.Saturation -> {
                        m[LocalParam.Saturation] = op.amount
                    }

                    is OpParams.PhotoFilter -> {
                        m[LocalParam.ColorHue] = op.hue
                        m[LocalParam.ColorAmount] = op.density
                    }

                    is OpParams.Texture -> {
                        m[LocalParam.Texture] = op.amount
                    }

                    is OpParams.Clarity -> {
                        m[LocalParam.Clarity] = op.amount
                    }

                    is OpParams.Dehaze -> {
                        m[LocalParam.Dehaze] = op.amount
                    }

                    is OpParams.NoiseReduction -> {
                        m[LocalParam.Noise] = op.luminance
                    }

                    is OpParams.Sharpen -> {
                        m[LocalParam.Sharpness] = op.amount
                    }

                    else -> {
                        Unit
                    }
                }
            }
            return LocalAdjustments(m)
        }
    }
}
