package dev.mark.lineover.feature.editor.canvas

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where the photo sits on the canvas: a similarity transform (zoom, rotation, pan) from
 * image pixels to view pixels. Pure math, free of Android, unit-tested, and written so the
 * per-gesture-event methods never allocate (the canvas runs at 120 Hz, rule R6).
 *
 * view = center + R(rotation) · scale · (image − imageCenter)
 *
 * [scale] is view pixels per pixel of the **original** image, so 1.0 is 100 %.
 */
class CanvasTransform {
    var imageWidth = 1f
        private set
    var imageHeight = 1f
        private set
    var viewWidth = 1f
        private set
    var viewHeight = 1f
        private set

    var scale = 1f
        private set

    /** Rotation shown on screen, degrees, in [0, 360). */
    var rotation = 0f
        private set
    var centerX = 0.5f
        private set
    var centerY = 0.5f
        private set

    /** True while the photo is exactly fitted (a resize then refits it). */
    var isFitted = true
        private set

    /** Rotation the fingers asked for; [rotation] equals it except inside a snap detent. */
    private var rawRotation = 0f

    val minScale: Float get() = minOf(MIN_SCALE, fitScale())
    val maxScale: Float get() = MAX_SCALE

    fun setImageSize(
        width: Int,
        height: Int,
    ) {
        imageWidth = width.coerceAtLeast(1).toFloat()
        imageHeight = height.coerceAtLeast(1).toFloat()
        fit()
    }

    fun setViewSize(
        width: Int,
        height: Int,
    ) {
        val oldW = viewWidth
        val oldH = viewHeight
        viewWidth = width.coerceAtLeast(1).toFloat()
        viewHeight = height.coerceAtLeast(1).toFloat()
        if (isFitted) {
            fit()
        } else {
            // Keep the same image point in the middle of the view.
            centerX += (viewWidth - oldW) / 2f
            centerY += (viewHeight - oldH) / 2f
            clampPan()
        }
    }

    /** Scale at which the whole (rotated) photo fits the view. */
    fun fitScale(): Float {
        val r = rotation * DEG_TO_RAD
        val c = abs(cos(r))
        val s = abs(sin(r))
        val boundsW = c * imageWidth + s * imageHeight
        val boundsH = s * imageWidth + c * imageHeight
        return minOf(viewWidth / boundsW, viewHeight / boundsH)
    }

    /** Fits the photo (keeping its rotation) and centres it. */
    fun fit() {
        scale = fitScale()
        centerX = viewWidth / 2f
        centerY = viewHeight / 2f
        isFitted = true
    }

    /** Called when a two-finger gesture starts: rotation continues from what is shown. */
    fun beginGesture() {
        rawRotation = rotation
    }

    /**
     * Applies one step of a two-finger gesture around the focal point ([focusX], [focusY]):
     * the image point under the fingers stays under them, then moves by ([panX], [panY]).
     *
     * @return true when the rotation has just entered a 0/90/180/270° snap (play a haptic tick)
     */
    @Suppress("LongParameterList")
    fun applyGesture(
        focusX: Float,
        focusY: Float,
        panX: Float,
        panY: Float,
        scaleFactor: Float,
        rotationDelta: Float,
    ): Boolean {
        val newScale = (scale * scaleFactor).coerceIn(minScale, maxScale)
        val appliedFactor = newScale / scale
        val wasSnapped = isSnapped(rotation)
        rawRotation += rotationDelta
        val newRotation = snapRotation(normalize(rawRotation))
        var appliedRotation = newRotation - rotation
        if (appliedRotation > HALF_TURN) appliedRotation -= FULL_TURN
        if (appliedRotation < -HALF_TURN) appliedRotation += FULL_TURN

        val r = appliedRotation * DEG_TO_RAD
        val cos = cos(r)
        val sin = sin(r)
        val dx = (centerX - focusX) * appliedFactor
        val dy = (centerY - focusY) * appliedFactor
        centerX = focusX + cos * dx - sin * dy + panX
        centerY = focusY + sin * dx + cos * dy + panY
        scale = newScale
        rotation = newRotation
        isFitted = false
        clampPan()
        return !wasSnapped && isSnapped(rotation) && rotationDelta != 0f
    }

    /** Moves the photo (used by the fling). Returns false once a pan limit stops it. */
    fun panBy(
        dx: Float,
        dy: Float,
    ): Boolean {
        val beforeX = centerX
        val beforeY = centerY
        centerX += dx
        centerY += dy
        isFitted = false
        clampPan()
        return abs(centerX - (beforeX + dx)) < EPSILON && abs(centerY - (beforeY + dy)) < EPSILON
    }

    /** Sets everything at once (the end of a double-tap animation). */
    fun set(
        scale: Float,
        rotation: Float,
        centerX: Float,
        centerY: Float,
        fitted: Boolean,
    ) {
        this.scale = scale.coerceIn(minScale, maxScale)
        this.rotation = normalize(rotation)
        this.centerX = centerX
        this.centerY = centerY
        this.isFitted = fitted
        rawRotation = this.rotation
        if (!fitted) clampPan()
    }

    /**
     * Double tap: from fit go to 100 % around the tap, otherwise back to fit. Writes the
     * target (scale, centerX, centerY) into [out] and returns whether the target is "fitted".
     */
    fun doubleTapTarget(
        tapX: Float,
        tapY: Float,
        out: FloatArray,
    ): Boolean {
        val fit = fitScale()
        val atFit = abs(scale - fit) <= fit * FIT_TOLERANCE
        if (!atFit) {
            out[0] = fit
            out[1] = viewWidth / 2f
            out[2] = viewHeight / 2f
            return true
        }
        val target = (if (abs(fit - 1f) < FIT_TOLERANCE) fit * 2f else 1f).coerceIn(minScale, maxScale)
        val factor = target / scale
        out[0] = target
        out[1] = tapX + (centerX - tapX) * factor
        out[2] = tapY + (centerY - tapY) * factor
        return false
    }

    /**
     * The 1:1 toggle of the detail tools (Stage 10): 100 % (one image pixel per screen pixel)
     * around the middle of the view, or back to fit when already there. Writes (scale,
     * centerX, centerY) into [out] and returns whether the target is "fitted".
     */
    fun oneToOneTarget(out: FloatArray): Boolean {
        if (abs(scale - 1f) <= FIT_TOLERANCE) {
            out[0] = fitScale()
            out[1] = viewWidth / 2f
            out[2] = viewHeight / 2f
            return true
        }
        val target = 1f.coerceIn(minScale, maxScale)
        val factor = target / scale
        val midX = viewWidth / 2f
        val midY = viewHeight / 2f
        out[0] = target
        out[1] = midX + (centerX - midX) * factor
        out[2] = midY + (centerY - midY) * factor
        return false
    }

    /** Maps an image point to the view; writes x, y into [out]. */
    fun imageToView(
        x: Float,
        y: Float,
        out: FloatArray,
    ) {
        val r = rotation * DEG_TO_RAD
        val ix = (x - imageWidth / 2f) * scale
        val iy = (y - imageHeight / 2f) * scale
        out[0] = centerX + cos(r) * ix - sin(r) * iy
        out[1] = centerY + sin(r) * ix + cos(r) * iy
    }

    /** Maps a view point to the image; writes x, y into [out]. */
    fun viewToImage(
        x: Float,
        y: Float,
        out: FloatArray,
    ) {
        val r = -rotation * DEG_TO_RAD
        val vx = x - centerX
        val vy = y - centerY
        out[0] = (cos(r) * vx - sin(r) * vy) / scale + imageWidth / 2f
        out[1] = (sin(r) * vx + cos(r) * vy) / scale + imageHeight / 2f
    }

    /**
     * The transform as an affine map of original pixels to view pixels, written as
     * (a, b, c, d, e, f): view = (a·x + c·y + e, b·x + d·y + f). Allocation-free.
     */
    @Suppress("MagicNumber") // element indices
    fun writeAffine(out: FloatArray) {
        val r = rotation * DEG_TO_RAD
        val cos = cos(r) * scale
        val sin = sin(r) * scale
        out[0] = cos
        out[1] = sin
        out[2] = -sin
        out[3] = cos
        out[4] = centerX - (cos * imageWidth - sin * imageHeight) / 2f
        out[5] = centerY - (sin * imageWidth + cos * imageHeight) / 2f
    }

    /**
     * Column-major 3×3 matrix (for `glUniformMatrix3fv`) that maps the unit quad
     * (0..1, 0..1 = image left-top .. right-bottom) to normalized device coordinates.
     */
    @Suppress("MagicNumber") // Matrix element indices.
    fun writeGlMatrix(out: FloatArray) {
        val r = rotation * DEG_TO_RAD
        val cos = cos(r)
        val sin = sin(r)
        val sx = 2f / viewWidth
        val sy = -2f / viewHeight
        // view(u, v) = center + R · scale · ((u − 0.5)·w, (v − 0.5)·h)
        val a = cos * scale * imageWidth
        val b = sin * scale * imageWidth
        val c = -sin * scale * imageHeight
        val d = cos * scale * imageHeight
        val tx = centerX - (a + c) / 2f
        val ty = centerY - (b + d) / 2f
        out[0] = a * sx
        out[1] = b * sy
        out[2] = 0f
        out[3] = c * sx
        out[4] = d * sy
        out[5] = 0f
        out[6] = tx * sx - 1f
        out[7] = ty * sy + 1f
        out[8] = 1f
    }

    /**
     * The middle of the view must always show a part of the photo, so the photo can never
     * be lost off-screen, at any zoom.
     */
    private fun clampPan() {
        val r = -rotation * DEG_TO_RAD
        val vx = viewWidth / 2f - centerX
        val vy = viewHeight / 2f - centerY
        val px = (cos(r) * vx - sin(r) * vy) / scale
        val py = (sin(r) * vx + cos(r) * vy) / scale
        val halfW = imageWidth / 2f
        val halfH = imageHeight / 2f
        val cx = px.coerceIn(-halfW, halfW)
        val cy = py.coerceIn(-halfH, halfH)
        if (cx == px && cy == py) return
        val f = rotation * DEG_TO_RAD
        centerX = viewWidth / 2f - (cos(f) * cx - sin(f) * cy) * scale
        centerY = viewHeight / 2f - (sin(f) * cx + cos(f) * cy) * scale
    }

    companion object {
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 32f

        /** Rotations within this many degrees of a right angle snap to it. */
        const val SNAP_DEGREES = 5f

        private const val FULL_TURN = 360f
        private const val HALF_TURN = 180f
        private const val RIGHT_ANGLE = 90f
        private const val FIT_TOLERANCE = 0.01f
        private const val EPSILON = 0.01f
        private const val DEG_TO_RAD = (PI / 180.0).toFloat()

        fun normalize(degrees: Float): Float {
            val d = degrees % FULL_TURN
            return if (d < 0f) d + FULL_TURN else d
        }

        /** [degrees] snapped to the nearest right angle when within [SNAP_DEGREES]. */
        fun snapRotation(degrees: Float): Float {
            val nearest = (degrees / RIGHT_ANGLE).roundToInt() * RIGHT_ANGLE
            return if (abs(degrees - nearest) <= SNAP_DEGREES) normalize(nearest) else degrees
        }

        fun isSnapped(degrees: Float): Boolean = abs(degrees % RIGHT_ANGLE) < EPSILON || abs(degrees % RIGHT_ANGLE - RIGHT_ANGLE) < EPSILON
    }
}

/**
 * Turns two finger positions per event into pan / zoom / rotate steps. Rotation only
 * starts once the fingers have turned [ROTATION_SLOP_DEGREES], so a plain pinch never tilts
 * the photo. Allocation-free.
 */
class TwoFingerTracker {
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 1f
    private var lastAngle = 0f
    private var turned = 0f
    var rotating = false
        private set

    var focusX = 0f
        private set
    var focusY = 0f
        private set
    var panX = 0f
        private set
    var panY = 0f
        private set
    var scaleFactor = 1f
        private set
    var rotationDelta = 0f
        private set

    fun start(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
    ) {
        lastX = (x0 + x1) / 2f
        lastY = (y0 + y1) / 2f
        lastSpan = hypot(x1 - x0, y1 - y0).coerceAtLeast(MIN_SPAN)
        lastAngle = angle(x0, y0, x1, y1)
        turned = 0f
        rotating = false
    }

    /** Updates [focusX], [focusY], [panX], [panY], [scaleFactor] and [rotationDelta]. */
    fun move(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
    ) {
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        val span = hypot(x1 - x0, y1 - y0).coerceAtLeast(MIN_SPAN)
        val angle = angle(x0, y0, x1, y1)
        var delta = angle - lastAngle
        if (delta > HALF_TURN) delta -= FULL_TURN
        if (delta < -HALF_TURN) delta += FULL_TURN
        if (!rotating) {
            turned += delta
            if (abs(turned) >= ROTATION_SLOP_DEGREES) rotating = true
            delta = 0f
        }
        focusX = lastX
        focusY = lastY
        panX = cx - lastX
        panY = cy - lastY
        scaleFactor = span / lastSpan
        rotationDelta = delta
        lastX = cx
        lastY = cy
        lastSpan = span
        lastAngle = angle
    }

    private fun angle(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
    ): Float = (kotlin.math.atan2(y1 - y0, x1 - x0) * RAD_TO_DEG)

    companion object {
        const val ROTATION_SLOP_DEGREES = 10f
        private const val MIN_SPAN = 1f
        private const val FULL_TURN = 360f
        private const val HALF_TURN = 180f
        private const val RAD_TO_DEG = (180.0 / PI).toFloat()
    }
}

/** Exponential velocity decay for the pan fling. Allocation-free. */
class FlingDecay {
    var velocityX = 0f
        private set
    var velocityY = 0f
        private set
    val isRunning: Boolean get() = hypot(velocityX, velocityY) >= STOP_SPEED

    fun start(
        vx: Float,
        vy: Float,
    ) {
        velocityX = vx
        velocityY = vy
    }

    fun stop() {
        velocityX = 0f
        velocityY = 0f
    }

    /**
     * Advances by [dtSeconds] and writes the distance travelled into [out] (x, y). The
     * integral of v·e^(−kt) is used, so the result does not depend on the frame rate.
     */
    fun step(
        dtSeconds: Float,
        out: FloatArray,
    ) {
        val decay = exp(-FRICTION * dtSeconds)
        val travelled = (1f - decay) / FRICTION
        out[0] = velocityX * travelled
        out[1] = velocityY * travelled
        velocityX *= decay
        velocityY *= decay
    }

    companion object {
        /** Velocity decays by e every 1/FRICTION s. */
        const val FRICTION = 4f

        /** px/s below which the fling stops. */
        const val STOP_SPEED = 30f

        /** Total distance a fling of [v] px/s covers. */
        fun totalDistance(v: Float): Float = v / FRICTION
    }
}
