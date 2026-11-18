package dev.mark.lineover.core.model

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The crop side of the geometry (Stage 9): aspect ratios, and the auto-constrain that keeps
 * the crop inside the valid image (the source border, undistorted, through the transform)
 * so no empty wedges show.
 */
@Suppress("MagicNumber") // rect (left, top, right, bottom) positions
object CropMath {
    /**
     * The edge of the valid image in frame pixels: the source border, undistorted (lens),
     * through [toFrame]. Points behind the projection are dropped.
     */
    fun validPolygon(
        g: GeometryAdjustments,
        sourceWidth: Int,
        sourceHeight: Int,
        toFrame: Mat3 = GeometryMath.sourceToFrame(g, sourceWidth, sourceHeight),
    ): List<Pair<Double, Double>> {
        val border = ArrayList<Pair<Double, Double>>(4 * BORDER_SAMPLES)
        val w = sourceWidth.toDouble()
        val h = sourceHeight.toDouble()
        for (i in 0 until BORDER_SAMPLES) border += (w * i / BORDER_SAMPLES) to 0.0
        for (i in 0 until BORDER_SAMPLES) border += w to (h * i / BORDER_SAMPLES)
        for (i in 0 until BORDER_SAMPLES) border += (w - w * i / BORDER_SAMPLES) to h
        for (i in 0 until BORDER_SAMPLES) border += 0.0 to (h - h * i / BORDER_SAMPLES)
        return border.mapNotNull { (x, y) ->
            val (ux, uy) = undistort(g, sourceWidth, sourceHeight, x, y)
            toFrame.apply(ux, uy)
        }
    }

    /** Inverse of the engine's lens model for the green channel (Newton on the radius). */
    fun undistort(
        g: GeometryAdjustments,
        sourceWidth: Int,
        sourceHeight: Int,
        x: Double,
        y: Double,
    ): Pair<Double, Double> {
        if (g.k1 == 0.0 && g.k2 == 0.0) return x to y
        val cx = sourceWidth / 2.0
        val cy = sourceHeight / 2.0
        val radius = 0.5 * hypot(sourceWidth.toDouble(), sourceHeight.toDouble())
        val rd = hypot(x - cx, y - cy) / radius
        if (rd == 0.0) return x to y
        // Distorted radius rd = r (1 + k1 r^2 + k2 r^4): solve for r.
        var r = rd
        repeat(NEWTON_STEPS) {
            val r2 = r * r
            val f = r * (1 + g.k1 * r2 + g.k2 * r2 * r2) - rd
            val df = 1 + 3 * g.k1 * r2 + 5 * g.k2 * r2 * r2
            if (df > 0) r -= f / df
        }
        val s = r / rd
        return cx + (x - cx) * s to cy + (y - cy) * s
    }

    /**
     * [crop] (normalised frame) shrunk about its centre, keeping its aspect, until it lies
     * inside the valid image (no wedges); when even its centre is outside, centred on the
     * valid area first.
     */
    fun constrainCrop(
        g: GeometryAdjustments,
        sourceWidth: Int,
        sourceHeight: Int,
        crop: FloatArray,
    ): FloatArray {
        val (frameW, frameH) = GeometryMath.frameSize(g, sourceWidth, sourceHeight)
        val polygon = validPolygon(g, sourceWidth, sourceHeight)
        if (polygon.size < 3) return crop
        val rect =
            doubleArrayOf(
                crop[0] * frameW.toDouble(),
                crop[1] * frameH.toDouble(),
                crop[2] * frameW.toDouble(),
                crop[3] * frameH.toDouble(),
            )
        if (rectInside(rect, polygon)) return crop
        var cx = (rect[0] + rect[2]) / 2
        var cy = (rect[1] + rect[3]) / 2
        if (!inside(cx, cy, polygon)) {
            cx = polygon.sumOf { it.first } / polygon.size
            cy = polygon.sumOf { it.second } / polygon.size
        }
        val hw = (rect[2] - rect[0]) / 2
        val hh = (rect[3] - rect[1]) / 2
        var lo = 0.0
        var hi = 1.0
        repeat(SEARCH_STEPS) {
            val mid = (lo + hi) / 2
            if (rectInside(doubleArrayOf(cx - hw * mid, cy - hh * mid, cx + hw * mid, cy + hh * mid), polygon)) lo = mid else hi = mid
        }
        return floatArrayOf(
            ((cx - hw * lo) / frameW).toFloat(),
            ((cy - hh * lo) / frameH).toFloat(),
            ((cx + hw * lo) / frameW).toFloat(),
            ((cy + hh * lo) / frameH).toFloat(),
        )
    }

    private fun rectInside(
        r: DoubleArray,
        polygon: List<Pair<Double, Double>>,
    ): Boolean {
        val n = BORDER_SAMPLES / 2
        for (i in 0..n) {
            val t = i.toDouble() / n
            val x = r[0] + (r[2] - r[0]) * t
            val y = r[1] + (r[3] - r[1]) * t
            if (!inside(x, r[1], polygon) || !inside(x, r[3], polygon)) return false
            if (!inside(r[0], y, polygon) || !inside(r[2], y, polygon)) return false
        }
        return true
    }

    /** Point in polygon (even-odd rule), or within [EDGE] pixels of its border. */
    fun inside(
        x: Double,
        y: Double,
        polygon: List<Pair<Double, Double>>,
    ): Boolean {
        var odd = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val (xi, yi) = polygon[i]
            val (xj, yj) = polygon[j]
            if (distanceToSegment(x, y, xi, yi, xj, yj) <= EDGE) return true
            if ((yi > y) != (yj > y)) {
                val xCross = (xj - xi) * (y - yi) / (yj - yi) + xi
                if (x < xCross) odd = !odd
            }
            j = i
        }
        return odd
    }

    @Suppress("LongParameterList")
    private fun distanceToSegment(
        x: Double,
        y: Double,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
    ): Double {
        val dx = x2 - x1
        val dy = y2 - y1
        val length2 = dx * dx + dy * dy
        val t = if (length2 == 0.0) 0.0 else (((x - x1) * dx + (y - y1) * dy) / length2).coerceIn(0.0, 1.0)
        return hypot(x - (x1 + t * dx), y - (y1 + t * dy))
    }

    /** Pixels: a crop edge this close to the image edge counts as inside (no visible wedge). */
    private const val EDGE = 1e-3
    private const val BORDER_SAMPLES = 24
    private const val SEARCH_STEPS = 30
    private const val NEWTON_STEPS = 12
    private const val MIN_RATIO = 0.01f

    /**
     * The largest crop of aspect [ratio] (width / height, frame pixels) centred in [crop];
     * a ratio below 1 is portrait.
     */
    fun fitAspect(
        crop: FloatArray,
        ratio: Float,
        frameWidth: Int,
        frameHeight: Int,
    ): FloatArray {
        val w = (crop[2] - crop[0]) * frameWidth
        val h = (crop[3] - crop[1]) * frameHeight
        val cx = (crop[0] + crop[2]) / 2
        val cy = (crop[1] + crop[3]) / 2
        val (nw, nh) = if (w / h > ratio) h * ratio to h else w to w / ratio
        val hx = nw / 2 / frameWidth
        val hy = nh / 2 / frameHeight
        return floatArrayOf(cx - hx, cy - hy, cx + hx, cy + hy)
    }

    /** The landscape ratio (width / height) of [aspect] for a frame of the given size; null = free. */
    fun ratioOf(
        g: GeometryAdjustments,
        frameWidth: Int,
        frameHeight: Int,
    ): Float? =
        when (g.aspect) {
            CropAspect.Free -> null
            CropAspect.Original -> max(frameWidth, frameHeight).toFloat() / min(frameWidth, frameHeight)
            CropAspect.Custom -> max(g.customWidth, g.customHeight) / min(g.customWidth, g.customHeight).coerceAtLeast(MIN_RATIO)
            else -> g.aspect.ratio?.let { max(it, 1f / it) }
        }
}
