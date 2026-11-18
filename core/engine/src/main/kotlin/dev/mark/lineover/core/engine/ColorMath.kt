package dev.mark.lineover.core.engine

/**
 * The engine's colour maths the editor needs without an open image (core/engine-native
 * `color_ops.h`): the eyedropper's white balance and the targeted adjustment's band weights.
 */
object ColorMath {
    init {
        System.loadLibrary("lineover_engine")
    }

    /** (temperature K, tint) that turns working-space [rgb] neutral, or null (black). */
    fun whiteBalanceForNeutral(rgb: FloatArray): Pair<Float, Float>? = nativeNeutral(rgb[0], rgb[1], rgb[2])?.let { it[0] to it[1] }

    /** The 8 HSL band weights of [rgb]'s OKLCh hue, faded by chroma (all 0 for grey). */
    fun hslWeights(rgb: FloatArray): FloatArray = nativeHslWeights(rgb[0], rgb[1], rgb[2])

    @JvmStatic
    private external fun nativeNeutral(
        r: Float,
        g: Float,
        b: Float,
    ): FloatArray?

    @JvmStatic
    private external fun nativeHslWeights(
        r: Float,
        g: Float,
        b: Float,
    ): FloatArray
}
