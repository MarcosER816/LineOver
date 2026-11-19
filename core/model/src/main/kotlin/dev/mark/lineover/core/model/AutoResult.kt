package dev.mark.lineover.core.model

/**
 * Auto (Stage 11): what the engine's analysis proposes (auto_adjust.h). Applying it sets
 * white balance, Exposure, Contrast, Whites, Blacks and Vibrance and leaves everything else.
 */
data class AutoResult(
    val temperature: Float,
    val tint: Float,
    val exposure: Float,
    val contrast: Float,
    val whites: Float,
    val blacks: Float,
    val vibrance: Float,
) {
    fun applyTo(light: LightAdjustments): LightAdjustments =
        light
            .with(LightParam.Exposure, exposure)
            .with(LightParam.Contrast, contrast)
            .with(LightParam.Whites, whites)
            .with(LightParam.Blacks, blacks)

    fun applyTo(color: ColorAdjustments): ColorAdjustments =
        color.withWhiteBalance(temperature, tint).copy(vibrance = vibrance.coerceIn(-ColorAdjustments.RANGE, ColorAdjustments.RANGE))

    companion object {
        private const val VALUES = 7

        /** The engine's 7 floats (temperature, tint, exposure, contrast, whites, blacks, vibrance). */
        @Suppress("MagicNumber") // positions of the engine's array
        fun of(v: FloatArray): AutoResult? = if (v.size < VALUES) null else AutoResult(v[0], v[1], v[2], v[3], v[4], v[5], v[6])
    }
}
