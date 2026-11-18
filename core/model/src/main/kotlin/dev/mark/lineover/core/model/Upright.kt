package dev.mark.lineover.core.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

/** What Upright decided: camera angles (degrees) for [GeometryAdjustments.uprightPitch] etc. */
data class UprightResult(
    val pitch: Float,
    val yaw: Float,
    val roll: Float,
    /** The mode actually applied (Level when the asked one was out of range). */
    val applied: UprightMode,
    val fellBack: Boolean,
)

/**
 * Upright (Stage 9): line segments -> vanishing points -> the camera rotation that makes
 * verticals vertical (and, for Full, horizontals parallel), as K·R·K⁻¹ in
 * [GeometryMath.sourceToFrame]. Vanishing points are the least-squares intersection of
 * the segments' homogeneous lines (smallest eigenvector of Σ w·l·lᵀ, weights = length).
 * Every angle is limited to ±[LIMIT] degrees; beyond, Upright falls back to Level (brief
 * IF WRONG rule).
 */
@Suppress("MagicNumber") // 3-vector / 3 x 3 indices, degrees in a right angle
object Upright {
    const val LIMIT = 25.0
    private const val AUTO_YAW_LIMIT = 15.0
    private const val CLASSIFY = 30.0
    private const val MIN_SEGMENTS = 2
    private const val MIN_LENGTH = 0.02

    /** Segments are normalised (0..1) source coordinates of a [width] x [height] image. */
    fun solve(
        mode: UprightMode,
        segments: List<LineSegment>,
        width: Int,
        height: Int,
    ): UprightResult {
        val focal = GeometryMath.DEFAULT_FOCAL * max(width, height)
        val lines = segments.mapNotNull { Line.of(it, width, height, focal) }
        val verticals = lines.filter { abs(it.angle) > 90 - CLASSIFY }
        val horizontals = lines.filter { abs(it.angle) < CLASSIFY }
        val level = levelRoll(verticals, horizontals)
        return when (mode) {
            UprightMode.Off -> UprightResult(0f, 0f, 0f, UprightMode.Off, false)
            UprightMode.Level -> levelResult(level, fellBack = false)
            UprightMode.Vertical -> rotation(verticals, emptyList(), mode, level, useYaw = false)
            UprightMode.Auto -> rotation(verticals, horizontals, mode, level, useYaw = true, yawLimit = AUTO_YAW_LIMIT)
            UprightMode.Full -> rotation(verticals, horizontals, mode, level, useYaw = true)
            UprightMode.Guided -> guided(verticals, horizontals, level)
        }
    }

    /** Guided: the user's 2-4 lines; one vertical line levels by it, two give its vanishing point. */
    fun solveGuided(
        guides: List<GuideLine>,
        width: Int,
        height: Int,
    ): UprightResult = solve(UprightMode.Guided, guides.map { LineSegment(it.x1, it.y1, it.x2, it.y2) }, width, height)

    private fun guided(
        verticals: List<Line>,
        horizontals: List<Line>,
        level: Double?,
    ): UprightResult =
        when {
            verticals.size >= MIN_SEGMENTS -> {
                rotation(verticals, horizontals, UprightMode.Guided, level, useYaw = true)
            }

            verticals.size == 1 -> {
                levelResult(-deviation(verticals[0]), fellBack = false, mode = UprightMode.Guided)
            }

            else -> {
                levelResult(level, fellBack = false, mode = UprightMode.Guided)
            }
        }

    @Suppress("ReturnCount") // each missing input falls back to Level
    private fun rotation(
        verticals: List<Line>,
        horizontals: List<Line>,
        mode: UprightMode,
        level: Double?,
        useYaw: Boolean,
        yawLimit: Double = LIMIT,
    ): UprightResult {
        if (verticals.size < MIN_SEGMENTS) return levelResult(level, fellBack = true)
        val v = vanishingPoint(verticals) ?: return levelResult(level, fellBack = true)
        // Orient the vertical direction downwards (+y).
        val d = if (v[1] < 0) doubleArrayOf(-v[0], -v[1], -v[2]) else v
        val roll = atan2(d[0], d[1])
        val afterRoll = GeometryMath.rotationZ(roll).times(d)
        val pitch = atan2(-afterRoll[2], afterRoll[1])
        var yaw = 0.0
        if (useYaw && horizontals.size >= MIN_SEGMENTS) {
            vanishingPoint(horizontals)?.let { h ->
                val hd = if (h[0] < 0) doubleArrayOf(-h[0], -h[1], -h[2]) else h
                val turned = GeometryMath.rotationX(pitch).times(GeometryMath.rotationZ(roll).times(hd))
                val candidate = GeometryMath.degrees(atan2(turned[2], turned[0]))
                yaw = if (abs(candidate) <= yawLimit) candidate else 0.0
            }
        }
        val pitchDeg = GeometryMath.degrees(pitch)
        val rollDeg = GeometryMath.degrees(roll)
        if (abs(pitchDeg) > LIMIT || abs(rollDeg) > LIMIT || abs(yaw) > LIMIT) return levelResult(level, fellBack = true)
        return UprightResult(pitchDeg.toFloat(), yaw.toFloat(), rollDeg.toFloat(), mode, false)
    }

    private fun levelResult(
        roll: Double?,
        fellBack: Boolean,
        mode: UprightMode = UprightMode.Level,
    ): UprightResult {
        val r = roll ?: 0.0
        if (abs(r) > LIMIT) return UprightResult(0f, 0f, 0f, mode, true)
        return UprightResult(0f, 0f, r.toFloat(), mode, fellBack || roll == null)
    }

    /** Roll (degrees) making the dominant lines level: the length-weighted median tilt, undone. */
    private fun levelRoll(
        verticals: List<Line>,
        horizontals: List<Line>,
    ): Double? {
        val tilts = (verticals + horizontals).map { deviation(it) to it.length }.sortedBy { it.first }
        if (tilts.isEmpty()) return null
        val half = tilts.sumOf { it.second } / 2
        var sum = 0.0
        for ((tilt, weight) in tilts) {
            sum += weight
            if (sum >= half) return -tilt
        }
        return -tilts.last().first
    }

    /** Clockwise tilt of a line from horizontal / vertical (degrees). */
    private fun deviation(line: Line): Double = if (abs(line.angle) > 45) line.angle - sign(line.angle) * 90 else line.angle

    /** The least-squares vanishing point (a unit 3-vector in camera coordinates). */
    private fun vanishingPoint(lines: List<Line>): DoubleArray? {
        val a = Array(3) { DoubleArray(3) }
        for (line in lines) {
            for (r in 0 until 3) for (c in 0 until 3) a[r][c] += line.length * line.h[r] * line.h[c]
        }
        val (values, vectors) = Jacobi.eigen(a)
        val smallest = values.indices.minBy { values[it] }
        val v = DoubleArray(3) { vectors[it][smallest] }
        val n = sqrt(v.sumOf { it * it })
        return if (n == 0.0) null else DoubleArray(3) { v[it] / n }
    }

    /** A segment as a homogeneous line in camera coordinates (x / f, y / f, 1), unit normal. */
    private class Line(
        val h: DoubleArray,
        val angle: Double,
        val length: Double,
    ) {
        companion object {
            fun of(
                s: LineSegment,
                width: Int,
                height: Int,
                focal: Double,
            ): Line? {
                val x1 = (s.x1 * width - width / 2.0) / focal
                val y1 = (s.y1 * height - height / 2.0) / focal
                val x2 = (s.x2 * width - width / 2.0) / focal
                val y2 = (s.y2 * height - height / 2.0) / focal
                val length = hypot(x2 - x1, y2 - y1)
                if (length * focal < MIN_LENGTH * max(width, height)) return null
                val l = doubleArrayOf(y1 - y2, x2 - x1, x1 * y2 - x2 * y1)
                val n = hypot(l[0], l[1])
                return Line(DoubleArray(3) { l[it] / n }, GeometryMath.lineAngle(x1, y1, x2, y2), length)
            }
        }
    }
}

/** M·v for a homogeneous 3-vector. */
@Suppress("MagicNumber") // 3 x 3 indices
fun Mat3.times(v: DoubleArray): DoubleArray = DoubleArray(3) { r -> this[r, 0] * v[0] + this[r, 1] * v[1] + this[r, 2] * v[2] }

/** Cyclic Jacobi eigen-decomposition of a symmetric 3 x 3 matrix: (values, column eigenvectors). */
@Suppress("MagicNumber") // 3 x 3 indices
internal object Jacobi {
    private const val SWEEPS = 50
    private const val TINY = 1e-30

    fun eigen(input: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val a = Array(3) { input[it].copyOf() }
        val v = Array(3) { r -> DoubleArray(3) { c -> if (r == c) 1.0 else 0.0 } }
        repeat(SWEEPS) {
            val off = a[0][1] * a[0][1] + a[0][2] * a[0][2] + a[1][2] * a[1][2]
            if (off < TINY) return DoubleArray(3) { a[it][it] } to v
            for (p in 0 until 2) {
                for (q in p + 1 until 3) {
                    if (abs(a[p][q]) < TINY) continue
                    val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
                    val t = sign(theta).let { if (it == 0.0) 1.0 else it } / (abs(theta) + sqrt(theta * theta + 1))
                    val c = 1 / sqrt(t * t + 1)
                    val s = t * c
                    rotate(a, v, p, q, c, s)
                }
            }
        }
        return DoubleArray(3) { a[it][it] } to v
    }

    @Suppress("LongParameterList")
    private fun rotate(
        a: Array<DoubleArray>,
        v: Array<DoubleArray>,
        p: Int,
        q: Int,
        c: Double,
        s: Double,
    ) {
        for (k in 0 until 3) {
            val akp = a[k][p]
            val akq = a[k][q]
            a[k][p] = c * akp - s * akq
            a[k][q] = s * akp + c * akq
        }
        for (k in 0 until 3) {
            val apk = a[p][k]
            val aqk = a[q][k]
            a[p][k] = c * apk - s * aqk
            a[q][k] = s * apk + c * aqk
        }
        for (k in 0 until 3) {
            val vkp = v[k][p]
            val vkq = v[k][q]
            v[k][p] = c * vkp - s * vkq
            v[k][q] = s * vkp + c * vkq
        }
    }
}
