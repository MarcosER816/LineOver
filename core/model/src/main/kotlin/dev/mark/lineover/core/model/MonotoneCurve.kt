package dev.mark.lineover.core.model

import kotlin.math.sqrt

/** A curve control point, both coordinates 0..1. */
data class CurvePoint(
    val x: Float,
    val y: Float,
)

/**
 * The Fritsch-Carlson monotone cubic the engine samples into its 4096-entry LUT
 * (core/engine-native `grade::curveLut`, tools/golden `spline_eval`): the editor draws
 * exactly what the engine applies. Monotone data gives a monotone curve; flat outside
 * the first and last point.
 */
class MonotoneCurve(
    points: List<CurvePoint>,
) {
    private val xs: DoubleArray
    private val ys: DoubleArray
    private val m: DoubleArray

    init {
        val kept = mutableListOf<CurvePoint>()
        for (p in points) if (kept.isEmpty() || p.x > kept.last().x + MIN_GAP) kept += p
        xs = DoubleArray(kept.size) { kept[it].x.toDouble() }
        ys = DoubleArray(kept.size) { kept[it].y.toDouble() }
        m = tangents(xs, ys)
    }

    /** The curve at [x] (cubic Hermite basis; flat outside the points). */
    @Suppress("MagicNumber", "ReturnCount")
    operator fun invoke(x: Float): Float {
        val n = xs.size
        if (n == 0) return x
        if (n == 1 || x <= xs[0]) return ys[0].toFloat()
        if (x >= xs[n - 1]) return ys[n - 1].toFloat()
        var k = 0
        while (k + 2 < n && x >= xs[k + 1]) k++
        val h = xs[k + 1] - xs[k]
        val t = ((x - xs[k]) / h).coerceIn(0.0, 1.0)
        val t2 = t * t
        val t3 = t2 * t
        return (
            (2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h * m[k] + (-2 * t3 + 3 * t2) * ys[k + 1] +
                (t3 - t2) * h * m[k + 1]
        ).toFloat()
    }

    companion object {
        /** Points closer than this in x are merged (the engine skips them too). */
        const val MIN_GAP = 1e-6f
        private const val LIMIT = 9.0
        private const val THREE = 3.0

        @Suppress("MagicNumber") // Fritsch-Carlson: the mean of the secants
        fun tangents(
            xs: DoubleArray,
            ys: DoubleArray,
        ): DoubleArray {
            val n = xs.size
            if (n < 2) return DoubleArray(n)
            val d = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
            val m = DoubleArray(n)
            m[0] = d[0]
            m[n - 1] = d[n - 2]
            for (k in 1 until n - 1) m[k] = if (d[k - 1] * d[k] <= 0.0) 0.0 else 0.5 * (d[k - 1] + d[k])
            for (k in 0 until n - 1) {
                if (d[k] == 0.0) {
                    m[k] = 0.0
                    m[k + 1] = 0.0
                    continue
                }
                val a = m[k] / d[k]
                val b = m[k + 1] / d[k]
                val s = a * a + b * b
                if (s > LIMIT) {
                    val t = THREE / sqrt(s)
                    m[k] = t * a * d[k]
                    m[k + 1] = t * b * d[k]
                }
            }
            return m
        }

        /** Flattened [x0, y0, x1, y1, …] → points. */
        @Suppress("MagicNumber") // (x, y) pairs
        fun points(flat: List<Float>): List<CurvePoint> = flat.chunked(2).filter { it.size == 2 }.map { CurvePoint(it[0], it[1]) }

        fun flatten(points: List<CurvePoint>): List<Float> = points.flatMap { listOf(it.x, it.y) }

        /** Empty, or every point on the diagonal: the curve changes nothing. */
        @Suppress("MagicNumber") // fewer than two points
        fun isIdentity(flat: List<Float>): Boolean = flat.size < 4 || points(flat).all { it.x == it.y }
    }
}
