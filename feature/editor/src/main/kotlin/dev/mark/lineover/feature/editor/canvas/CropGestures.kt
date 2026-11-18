package dev.mark.lineover.feature.editor.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What one finger edits on the canvas while a Crop / Optics tool is open (Stage 9). */
enum class CropMode { Crop, Corners, Guides }

/**
 * The crop being edited, in normalised canvas-image coordinates (0..1). While cropping the
 * canvas shows the whole frame (the uncropped, transformed photo), so these are frame
 * coordinates; for [CropMode.Guides] the canvas shows the source photo.
 */
class CropFrame(
    val mode: CropMode,
    /** left, top, right, bottom. */
    val rect: FloatArray = floatArrayOf(0f, 0f, 1f, 1f),
    /** Locked aspect (width / height in image pixels) or null for free. */
    val ratio: Float? = null,
    /** TL, TR, BR, BL as x, y pairs. */
    val corners: FloatArray = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
)

/** Crop gestures, reported to the editor (UI thread). */
interface CropGestureListener {
    /** The crop rect while dragging ([done] on lift). */
    fun onCropRect(
        rect: FloatArray,
        done: Boolean,
    ) = Unit

    /** A crop handle is held (the grid shows only while dragging). */
    fun onCropDragging(dragging: Boolean) = Unit

    /** Double tap on the frame: reset the crop. */
    fun onCropReset() = Unit

    fun onCorners(
        corners: FloatArray,
        done: Boolean,
    ) = Unit

    /** A guide line was drawn (normalised source coordinates). */
    fun onGuideLine(line: FloatArray) = Unit
}

/**
 * The canvas transform as the Compose overlay reads it: [version] changes on every publish
 * and is read only in the draw phase, so pans and zooms redraw the overlay without
 * recomposing anything (CanvasRecompositionTest).
 */
class CanvasMatrix {
    var version by mutableIntStateOf(0)
        private set
    val affine = FloatArray(AFFINE)

    /** Image size of the transform (the canvas image). */
    var imageWidth = 1f
        private set
    var imageHeight = 1f
        private set

    /** The guide line being drawn (view pixels), or NaN. */
    val drawing = floatArrayOf(Float.NaN, 0f, 0f, 0f)

    fun update(transform: CanvasTransform) {
        transform.writeAffine(affine)
        imageWidth = transform.imageWidth
        imageHeight = transform.imageHeight
        version++
    }

    fun touch() {
        version++
    }

    /** Image pixel -> view pixel. */
    @Suppress("MagicNumber") // affine element indices
    fun toView(
        x: Float,
        y: Float,
        out: FloatArray,
        offset: Int = 0,
    ) {
        out[offset] = affine[0] * x + affine[2] * y + affine[4]
        out[offset + 1] = affine[1] * x + affine[3] * y + affine[5]
    }

    private companion object {
        const val AFFINE = 6
    }
}

/**
 * The one-finger crop gestures of [CanvasView] (kept here so the view stays small): 48 dp
 * handles on the corners and edges of the crop rect, a drag inside it moves it; four corner
 * handles for perspective crop; a drag draws a line for Guided Upright. All in the touch
 * path, no Compose state.
 */
@Suppress("MagicNumber") // handle indices and rect (left, top, right, bottom) positions
internal class CropGestures(
    private val transform: CanvasTransform,
    private val handleRadiusPx: Float,
) {
    var frame: CropFrame? = null
    var listener: CropGestureListener? = null
    var matrix: CanvasMatrix? = null

    private var handle = NONE
    private val start = FloatArray(4)
    private val corners = FloatArray(CORNER_VALUES)
    private val p = FloatArray(2)
    private val q = FloatArray(2)
    private var downX = 0f
    private var downY = 0f

    val active: Boolean get() = handle != NONE

    /** True when the finger at view ([x], [y]) starts a crop gesture. */
    fun begin(
        x: Float,
        y: Float,
    ): Boolean {
        val f = frame ?: return false
        downX = x
        downY = y
        handle =
            when (f.mode) {
                CropMode.Crop -> hitRect(f.rect, x, y)
                CropMode.Corners -> hitCorner(f.corners, x, y)
                CropMode.Guides -> GUIDE
            }
        if (handle == NONE) return false
        f.rect.copyInto(start)
        f.corners.copyInto(corners)
        if (f.mode == CropMode.Crop) listener?.onCropDragging(true)
        if (f.mode == CropMode.Guides) drawGuide(x, y)
        return true
    }

    fun move(
        x: Float,
        y: Float,
    ) {
        val f = frame ?: return
        when (f.mode) {
            CropMode.Crop -> listener?.onCropRect(dragRect(f, x, y), false)
            CropMode.Corners -> listener?.onCorners(dragCorner(x, y), false)
            CropMode.Guides -> drawGuide(x, y)
        }
    }

    fun end(
        x: Float,
        y: Float,
        lifted: Boolean,
    ) {
        val f = frame
        if (f != null && handle != NONE) {
            when (f.mode) {
                CropMode.Crop -> {
                    listener?.onCropRect(if (lifted) dragRect(f, x, y) else start.copyOf(), true)
                    listener?.onCropDragging(false)
                }

                CropMode.Corners -> {
                    listener?.onCorners(if (lifted) dragCorner(x, y) else corners.copyOf(), true)
                }

                CropMode.Guides -> {
                    matrix?.let {
                        it.drawing[0] = Float.NaN
                        it.touch()
                    }
                    if (lifted && hypot(x - downX, y - downY) > handleRadiusPx) {
                        val a = normalised(downX, downY, p)
                        val b = normalised(x, y, q)
                        listener?.onGuideLine(floatArrayOf(a[0], a[1], b[0], b[1]))
                    }
                }
            }
        }
        handle = NONE
    }

    private fun drawGuide(
        x: Float,
        y: Float,
    ) {
        val m = matrix ?: return
        m.drawing[0] = downX
        m.drawing[1] = downY
        m.drawing[2] = x
        m.drawing[3] = y
        m.touch()
    }

    private fun normalised(
        x: Float,
        y: Float,
        out: FloatArray,
    ): FloatArray {
        transform.viewToImage(x, y, out)
        out[0] = (out[0] / transform.imageWidth).coerceIn(0f, 1f)
        out[1] = (out[1] / transform.imageHeight).coerceIn(0f, 1f)
        return out
    }

    private fun viewOf(
        u: Float,
        v: Float,
    ): FloatArray {
        transform.imageToView(u * transform.imageWidth, v * transform.imageHeight, p)
        return p
    }

    /** Corners 0-3 (TL, TR, BR, BL), edges 4-7 (top, right, bottom, left), inside 8. */
    private fun hitRect(
        r: FloatArray,
        x: Float,
        y: Float,
    ): Int {
        val points =
            arrayOf(
                r[0] to r[1],
                r[2] to r[1],
                r[2] to r[3],
                r[0] to r[3],
                (r[0] + r[2]) / 2 to r[1],
                r[2] to (r[1] + r[3]) / 2,
                (r[0] + r[2]) / 2 to r[3],
                r[0] to (r[1] + r[3]) / 2,
            )
        var best = NONE
        var bestDistance = handleRadiusPx
        for ((i, pt) in points.withIndex()) {
            val v = viewOf(pt.first, pt.second)
            val d = hypot(v[0] - x, v[1] - y)
            if (d <= bestDistance) {
                best = i
                bestDistance = d
            }
        }
        if (best != NONE) return best
        val n = normalised(x, y, q)
        return if (n[0] in r[0]..r[2] && n[1] in r[1]..r[3]) INSIDE else NONE
    }

    private fun hitCorner(
        c: FloatArray,
        x: Float,
        y: Float,
    ): Int {
        var best = NONE
        var bestDistance = handleRadiusPx
        for (i in 0 until 4) {
            val v = viewOf(c[2 * i], c[2 * i + 1])
            val d = hypot(v[0] - x, v[1] - y)
            if (d <= bestDistance) {
                best = i
                bestDistance = d
            }
        }
        return best
    }

    private fun dragCorner(
        x: Float,
        y: Float,
    ): FloatArray {
        val out = corners.copyOf()
        val n = normalised(x, y, q)
        out[2 * handle] = n[0]
        out[2 * handle + 1] = n[1]
        return out
    }

    /** The rect after dragging [handle] to view ([x], [y]): clamped, at least [MIN_SIZE], aspect kept. */
    private fun dragRect(
        f: CropFrame,
        x: Float,
        y: Float,
    ): FloatArray {
        val a = normalised(downX, downY, FloatArray(2))
        val b = normalised(x, y, FloatArray(2))
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        var l = start[0]
        var t = start[1]
        var r = start[2]
        var btm = start[3]
        if (handle == INSIDE) {
            val w = r - l
            val h = btm - t
            l = (l + dx).coerceIn(0f, 1f - w)
            t = (t + dy).coerceIn(0f, 1f - h)
            return floatArrayOf(l, t, l + w, t + h)
        }
        if (handle in LEFT_HANDLES) l = (l + dx).coerceIn(0f, r - MIN_SIZE)
        if (handle in RIGHT_HANDLES) r = (r + dx).coerceIn(l + MIN_SIZE, 1f)
        if (handle in TOP_HANDLES) t = (t + dy).coerceIn(0f, btm - MIN_SIZE)
        if (handle in BOTTOM_HANDLES) btm = (btm + dy).coerceIn(t + MIN_SIZE, 1f)
        val ratio = f.ratio ?: return floatArrayOf(l, t, r, btm)
        return keepRatio(floatArrayOf(l, t, r, btm), ratio)
    }

    /** Fixes the dimension the handle did not drive so width / height (pixels) == [ratio]. */
    private fun keepRatio(
        rect: FloatArray,
        ratio: Float,
    ): FloatArray {
        val iw = transform.imageWidth
        val ih = transform.imageHeight
        var w = (rect[2] - rect[0]) * iw
        var h = (rect[3] - rect[1]) * ih
        val horizontalDrive = handle == EDGE_LEFT || handle == EDGE_RIGHT
        val verticalDrive = handle == EDGE_TOP || handle == EDGE_BOTTOM
        when {
            horizontalDrive -> h = w / ratio
            verticalDrive -> w = h * ratio
            abs(w - (start[2] - start[0]) * iw) >= abs(h - (start[3] - start[1]) * ih) -> h = w / ratio
            else -> w = h * ratio
        }
        // Fit inside the frame, keeping the ratio.
        val fit = min(1f, min(iw / w, ih / h))
        w *= fit
        h *= fit
        val nw = w / iw
        val nh = h / ih
        // Anchor: the side / corner opposite the handle; edges stay centred on the other axis.
        val cx = (rect[0] + rect[2]) / 2
        val cy = (rect[1] + rect[3]) / 2
        var l =
            when {
                handle in LEFT_HANDLES -> start[2] - nw
                handle in RIGHT_HANDLES -> start[0]
                else -> cx - nw / 2
            }
        var t =
            when {
                handle in TOP_HANDLES -> start[3] - nh
                handle in BOTTOM_HANDLES -> start[1]
                else -> cy - nh / 2
            }
        l = l.coerceIn(0f, max(0f, 1f - nw))
        t = t.coerceIn(0f, max(0f, 1f - nh))
        return floatArrayOf(l, t, l + nw, t + nh)
    }

    private companion object {
        const val NONE = -1
        const val EDGE_TOP = 4
        const val EDGE_RIGHT = 5
        const val EDGE_BOTTOM = 6
        const val EDGE_LEFT = 7
        const val INSIDE = 8
        const val GUIDE = 9
        const val CORNER_VALUES = 8
        const val MIN_SIZE = 0.05f
        val LEFT_HANDLES = setOf(0, 3, EDGE_LEFT)
        val RIGHT_HANDLES = setOf(1, 2, EDGE_RIGHT)
        val TOP_HANDLES = setOf(0, 1, EDGE_TOP)
        val BOTTOM_HANDLES = setOf(2, 3, EDGE_BOTTOM)
    }
}
