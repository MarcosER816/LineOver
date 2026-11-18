// 3 x 3 matrices and the four-point homography (Stage 9).
package dev.mark.lineover.core.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A row-major 3 x 3 matrix (homogeneous 2D transforms). */
@Suppress("MagicNumber") // row-major element indices
class Mat3(
    val m: DoubleArray,
) {
    init {
        require(m.size == SIZE) { "a 3 x 3 matrix has 9 values" }
    }

    operator fun get(
        row: Int,
        col: Int,
    ): Double = m[3 * row + col]

    operator fun times(o: Mat3): Mat3 =
        Mat3(
            DoubleArray(SIZE) { i ->
                val r = i / 3
                val c = i % 3
                m[3 * r] * o.m[c] + m[3 * r + 1] * o.m[3 + c] + m[3 * r + 2] * o.m[6 + c]
            },
        )

    /** (x, y) mapped and divided by w; null behind the projection (w <= 0). */
    fun apply(
        x: Double,
        y: Double,
    ): Pair<Double, Double>? {
        val w = m[6] * x + m[7] * y + m[8]
        if (w <= EPS) return null
        return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
    }

    fun inverse(): Mat3? {
        val det =
            m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        if (abs(det) < DET_EPS) return null
        val inv =
            doubleArrayOf(
                m[4] * m[8] - m[5] * m[7],
                m[2] * m[7] - m[1] * m[8],
                m[1] * m[5] - m[2] * m[4],
                m[5] * m[6] - m[3] * m[8],
                m[0] * m[8] - m[2] * m[6],
                m[2] * m[3] - m[0] * m[5],
                m[3] * m[7] - m[4] * m[6],
                m[1] * m[6] - m[0] * m[7],
                m[0] * m[4] - m[1] * m[3],
            )
        return Mat3(DoubleArray(SIZE) { inv[it] / det })
    }

    /** Scaled so m[8] == 1 (the form OpenCV returns). */
    fun normalised(): Mat3 = Mat3(DoubleArray(SIZE) { m[it] / m[8] })

    fun toList(): List<Double> = m.toList()

    companion object {
        const val SIZE = 9
        private const val EPS = 1e-12
        private const val DET_EPS = 1e-300

        val IDENTITY = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun translate(
            x: Double,
            y: Double,
        ) = Mat3(doubleArrayOf(1.0, 0.0, x, 0.0, 1.0, y, 0.0, 0.0, 1.0))

        fun scale(
            x: Double,
            y: Double,
        ) = Mat3(doubleArrayOf(x, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, 1.0))

        /** Rotation by [radians], clockwise on screen (y points down). */
        fun rotate(radians: Double): Mat3 {
            val c = cos(radians)
            val s = sin(radians)
            return Mat3(doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0))
        }
    }
}

/** Direct linear transform: the homography taking four points to four points. */
@Suppress("MagicNumber") // four (x, y) points: 8 values, 8 unknowns
object Homography {
    /**
     * The matrix H (h33 = 1) with H·src[i] ~ dst[i] for the 4 points (x, y pairs), as
     * OpenCV's getPerspectiveTransform solves it (8 x 8 linear system); null when three
     * points are collinear.
     */
    fun fromQuads(
        src: DoubleArray,
        dst: DoubleArray,
    ): Mat3? {
        require(src.size == QUAD && dst.size == QUAD) { "four x, y points" }
        val a = Array(UNKNOWNS) { DoubleArray(UNKNOWNS + 1) }
        for (i in 0 until 4) {
            val x = src[2 * i]
            val y = src[2 * i + 1]
            val u = dst[2 * i]
            val v = dst[2 * i + 1]
            a[i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -x * u, -y * u, u)
            a[i + 4] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -x * v, -y * v, v)
        }
        val h = solve(a) ?: return null
        return Mat3(DoubleArray(Mat3.SIZE) { if (it < UNKNOWNS) h[it] else 1.0 })
    }

    /** Gaussian elimination with partial pivoting on an n x (n + 1) augmented matrix. */
    private fun solve(a: Array<DoubleArray>): DoubleArray? {
        val n = a.size
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < SINGULAR) return null
            val t = a[pivot]
            a[pivot] = a[col]
            a[col] = t
            for (r in 0 until n) if (r != col) eliminate(a, r, col)
        }
        return DoubleArray(n) { a[it][n] / a[it][it] }
    }

    /** Row [r] minus the multiple of row [col] that zeroes its column [col]. */
    private fun eliminate(
        a: Array<DoubleArray>,
        r: Int,
        col: Int,
    ) {
        val f = a[r][col] / a[col][col]
        if (f == 0.0) return
        for (c in col until a[r].size) a[r][c] -= f * a[col][c]
    }

    private const val QUAD = 8
    private const val UNKNOWNS = 8
    private const val SINGULAR = 1e-12
}
