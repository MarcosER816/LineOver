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
     * Source pixels the crop keeps clear of the photo's edge: the bicubic sampler reads 2
     * pixels around each position (outside the photo it reads 0, so an edge pixel would mix
     * in black), and the red / blue channels sample up to |CA| × the half diagonal further
     * out. Zero when every output pixel lands on a source pixel centre (crop, quarter turns
     * and flips only): those sample exactly one pixel.
     */
    fun edgeMargin(
        g: GeometryAdjustments,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Double {
        val planar =
            listOf(g.straighten + g.rotate, g.uprightPitch, g.uprightYaw, g.uprightRoll, g.vertical, g.horizontal, g.aspectAmount)
        val lens = listOf(g.k1, g.k2, g.caRed, g.caBlue) + g.lensDistortion + g.lensTcaRed + g.lensTcaBlue
        val exact = planar.all { it == 0f } && lens.all { it == 0.0 } && g.scale == 100f && g.offsetX == 0f && g.offsetY == 0f
        if (exact) return 0.0
        val halfDiagonal = 0.5 * hypot(sourceWidth.toDouble(), sourceHeight.toDouble())
        return BICUBIC_REACH + max(channelShift(g.caRed, g.lensTcaRed), channelShift(g.caBlue, g.lensTcaBlue)) * halfDiagonal
    }

    /**
     * The edge of the valid image in frame pixels: the source border inset by [edgeMargin],
     * undistorted (lens), through [toFrame]. Points behind the projection are dropped.
     */
    fun validPolygon(
        g: GeometryAdjustments,
        sourceWidth: Int,
        sourceHeight: Int,
        toFrame: Mat3 = GeometryMath.sourceToFrame(g, sourceWidth, sourceHeight),
    ): List<Pair<Double, Double>> {
        val border = ArrayList<Pair<Double, Double>>(4 * BORDER_SAMPLES)
        val m = min(edgeMargin(g, sourceWidth, sourceHeight), min(sourceWidth, sourceHeight) / 4.0)
        val x0 = m
        val y0 = m
        val w = sourceWidth - 2 * m
        val h = sourceHeight - 2 * m
        for (i in 0 until BORDER_SAMPLES) border += (x0 + w * i / BORDER_SAMPLES) to y0
        for (i in 0 until BORDER_SAMPLES) border += (x0 + w) to (y0 + h * i / BORDER_SAMPLES)
        for (i in 0 until BORDER_SAMPLES) border += (x0 + w - w * i / BORDER_SAMPLES) to (y0 + h)
        for (i in 0 until BORDER_SAMPLES) border += x0 to (y0 + h - h * i / BORDER_SAMPLES)
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
        if (g.k1 == 0.0 && g.k2 == 0.0 && g.lensDistortion.all { it == 0.0 }) return x to y
        val cx = sourceWidth / 2.0
        val cy = sourceHeight / 2.0
        val radius = 0.5 * hypot(sourceWidth.toDouble(), sourceHeight.toDouble())
        val rd = hypot(x - cx, y - cy) / radius
        if (rd == 0.0) return x to y
        // Distorted radius rd = r s(r): solve for r on the monotone part (up to the fold,
        // where the model turns back). A radius past the fold has no undistorted position:
        // the valid image ends at the fold.
        val fold = foldRadius(g)
        val reach = fold * g.radialScale(fold * fold)
        val r =
            if (rd >= reach) {
                fold
            } else {
                var lo = 0.0
                var hi = fold
                repeat(BISECTION_STEPS) {
                    val mid = (lo + hi) / 2
                    if (mid * g.radialScale(mid * mid) < rd) lo = mid else hi = mid
                }
                (lo + hi) / 2
            }
        val s = r / rd
        return cx + (x - cx) * s to cy + (y - cy) * s
    }

    /** The first radius where r s(r) stops growing (MAX_RADIUS when it never turns back). */
    private fun foldRadius(g: GeometryAdjustments): Double {
        var previous = 0.0
        for (i in 1..FOLD_STEPS) {
            val r = MAX_RADIUS * i / FOLD_STEPS
            val value = r * g.radialScale(r * r)
            if (value <= previous) return MAX_RADIUS * (i - 1) / FOLD_STEPS
            previous = value
        }
        return MAX_RADIUS
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
    private const val BICUBIC_REACH = 2.0
    private const val BORDER_SAMPLES = 96
    private const val SEARCH_STEPS = 30
    private const val BISECTION_STEPS = 60
    private const val MAX_RADIUS = 4.0
    private const val FOLD_STEPS = 4000
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

/** A bound on |scale - 1| of a channel over the image (distorted radius up to [RD_BOUND]). */
@Suppress("MagicNumber") // tv, tc, tb
private fun channelShift(
    ca: Double,
    tca: List<Double>,
): Double {
    val t = tca + List(3 - tca.size.coerceAtMost(3)) { 0.0 }
    return kotlin.math.abs(ca + t[0]) + kotlin.math.abs(t[1]) * RD_BOUND + kotlin.math.abs(t[2]) * RD_BOUND * RD_BOUND
}

private const val RD_BOUND = 1.5
