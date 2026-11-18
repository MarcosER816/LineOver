package dev.mark.lineover.core.model

/**
 * Maps the classic HSV hue the UI shows (colour wheels, hue tracks) to the OKLCh hue the
 * engine's ops take, piecewise linearly through the HSL band centres (the OKLCh hues of
 * saturated sRGB colours at HSV 0, 30, 60, 120, 180, 240, 270, 300 degrees), and back.
 */
object HueMap {
    private val HSV = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 270f, 300f, 360f)
    private val OKLCH = floatArrayOf(29.2f, 52.8f, 109.8f, 142.5f, 194.8f, 264.1f, 293.8f, 328.4f, 389.2f)
    private const val TURN = 360f

    fun hsvToOklch(hsv: Float): Float = map(wrap(hsv), HSV, OKLCH) % TURN

    fun oklchToHsv(oklch: Float): Float {
        var h = wrap(oklch)
        if (h < OKLCH[0]) h += TURN
        return map(h, OKLCH, HSV) % TURN
    }

    private fun wrap(h: Float): Float = ((h % TURN) + TURN) % TURN

    private fun map(
        h: Float,
        from: FloatArray,
        to: FloatArray,
    ): Float {
        for (i in 0 until from.size - 1) {
            if (h <= from[i + 1]) {
                val t = (h - from[i]) / (from[i + 1] - from[i])
                return to[i] + t * (to[i + 1] - to[i])
            }
        }
        return to.last()
    }
}
