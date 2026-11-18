package dev.mark.lineover.feature.editor

import kotlin.math.cbrt
import kotlin.math.pow

/**
 * The canvas's display transform for colours the UI draws itself (eyedropper loupe,
 * gradient stops): linear Rec.2020 -> sRGB, out-of-gamut colours gamut-mapped as the GPU
 * display pass does (color::gamutMap, reference.gamut_map): OKLab lightness (clamped to
 * 0..1) and hue kept, chroma bisected towards the grey L^3 in [STEPS] steps.
 */
@Suppress("MagicNumber") // 3 x 3 matrix indices and the sRGB transfer constants
internal object DisplayGamut {
    private const val STEPS = 16
    private const val EPS = 5e-4

    // Row-major; the same constants as op_util.h / color.cpp.
    private val REC2020_TO_SRGB =
        doubleArrayOf(
            1.6603034854214451,
            -0.58757014253161666,
            -0.072890060215055993,
            -0.12437559530838646,
            1.1328344814319582,
            -0.008359737187401451,
            -0.018112279959916489,
            -0.10058360850721247,
            1.1187703261570985,
        )
    private val RGB_TO_LMS =
        doubleArrayOf(
            0.616688441751,
            0.36015907047,
            0.023043293521,
            0.265140196183,
            0.635856484699,
            0.099030233566,
            0.100150645103,
            0.204004323432,
            0.696324677437,
        )
    private val LMS_TO_RGB =
        doubleArrayOf(
            2.140140411041,
            -1.246355950476,
            0.106431725912,
            -0.884832452797,
            2.163172720721,
            -0.27836159213,
            -0.048579058003,
            -0.454490907968,
            1.502356294642,
        )
    private val LMS_TO_LAB =
        doubleArrayOf(
            0.2104542553,
            0.793617785,
            -0.0040720468,
            1.9779984951,
            -2.428592205,
            0.4505937099,
            0.0259040371,
            0.7827717662,
            -0.808675766,
        )
    private val LAB_TO_LMS =
        doubleArrayOf(
            0.999999998451,
            0.396337792174,
            0.215803758061,
            1.000000008882,
            -0.105561342324,
            -0.063854174772,
            1.000000054672,
            -0.089484182095,
            -1.291485537864,
        )

    /** Encoded sRGB (r, g, b) in 0..1. */
    fun workingToSrgb(
        r: Float,
        g: Float,
        b: Float,
    ): FloatArray {
        val linear = gamutMap(doubleArrayOf(r.toDouble(), g.toDouble(), b.toDouble()))
        return FloatArray(3) { encode(linear[it]).toFloat() }
    }

    /** Linear working RGB -> linear sRGB in 0..1. */
    fun gamutMap(rgb: DoubleArray): DoubleArray {
        var display = mul(REC2020_TO_SRGB, rgb)
        if (!inside(display)) {
            val lab = toLab(rgb)
            lab[0] = lab[0].coerceIn(0.0, 1.0)
            val l3 = lab[0] * lab[0] * lab[0]
            val grey = toLab(doubleArrayOf(l3, l3, l3))
            var lo = 0.0
            var hi = 1.0
            repeat(STEPS) {
                val mid = 0.5 * (lo + hi)
                if (inside(mul(REC2020_TO_SRGB, fromLab(mix(grey, lab, mid))))) lo = mid else hi = mid
            }
            display = mul(REC2020_TO_SRGB, fromLab(mix(grey, lab, lo)))
        }
        return DoubleArray(3) { display[it].coerceIn(0.0, 1.0) }
    }

    private fun inside(d: DoubleArray) = d.all { it >= -EPS && it <= 1.0 + EPS }

    private fun mix(
        a: DoubleArray,
        b: DoubleArray,
        t: Double,
    ) = DoubleArray(3) { a[it] + t * (b[it] - a[it]) }

    private fun mul(
        m: DoubleArray,
        v: DoubleArray,
    ) = DoubleArray(3) { m[3 * it] * v[0] + m[3 * it + 1] * v[1] + m[3 * it + 2] * v[2] }

    private fun toLab(rgb: DoubleArray): DoubleArray = mul(LMS_TO_LAB, mul(RGB_TO_LMS, rgb).map { cbrt(it) }.toDoubleArray())

    private fun fromLab(lab: DoubleArray): DoubleArray = mul(LMS_TO_RGB, mul(LAB_TO_LMS, lab).map { it * it * it }.toDoubleArray())

    private fun encode(x: Double): Double = if (x <= 0.0031308) x * 12.92 else 1.055 * x.pow(1.0 / 2.4) - 0.055
}
