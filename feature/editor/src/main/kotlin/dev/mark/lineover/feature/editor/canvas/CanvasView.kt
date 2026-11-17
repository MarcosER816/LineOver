package dev.mark.lineover.feature.editor.canvas

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.view.Choreographer
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.VelocityTracker
import dev.mark.lineover.core.designsystem.MotionTokens
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln

/**
 * The photo canvas: a SurfaceView rendered with GLES on its own thread. Gestures are
 * handled here, in the MotionEvent path, and never touch Compose state, so panning,
 * zooming and rotating cause no recomposition (rule R6, 8.3 ms per frame at 120 Hz).
 *
 * - Two fingers: pan, pinch zoom (0.1×–32× around the focal point) and rotate (snaps to
 *   0/90/180/270° with a haptic tick).
 * - Double tap: fit ↔ 100 %.
 * - Lifting the fingers while panning flings with an exponential decay.
 *
 * One finger is left free for the tools of later stages (brushes, handles).
 */
@SuppressLint("ViewConstructor")
class CanvasView(
    context: Context,
) : GLSurfaceView(context),
    Choreographer.FrameCallback {
    val transform = CanvasTransform()
    private val renderer = CanvasRenderer()
    private val tracker = TwoFingerTracker()
    private val fling = FlingDecay()
    private val matrix = FloatArray(MATRIX_SIZE)
    private val step = FloatArray(2)
    private val target = FloatArray(3)
    private var velocityTracker: VelocityTracker? = null
    private var twoFingers = false
    private var frameScheduled = false
    private var lastFrameNanos = 0L
    private var currentBitmap: Bitmap? = null
    private var currentSurround = 0

    // Double-tap animation (log-scale zoom, linear pan), driven by the same frame callback.
    private var animating = false
    private var animStartNanos = 0L
    private var animFromScale = 1f
    private var animToScale = 1f
    private var animFromX = 0f
    private var animFromY = 0f
    private var animToX = 0f
    private var animToY = 0f
    private var animToFitted = false

    /** GL frame timings (used by the frame-timing test). */
    val frameStats: FrameStats get() = renderer.stats

    /** Called after every change of the transform, on the UI thread (no allocation). */
    var onTransformChanged: (() -> Unit)? = null

    private val doubleTap =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val fitted = transform.doubleTapTarget(e.x, e.y, target)
                    animateTo(target[0], target[1], target[2], fitted)
                    return true
                }
            },
        )

    init {
        setEGLContextClientVersion(GLES_VERSION)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        contentDescription = "Photo canvas. Pinch to zoom, two fingers to pan and rotate, double tap to fit."
        isFocusable = false
    }

    /** Shows [bitmap] (the decoded preview, long edge ≤ 2048 px) as an image of [originalWidth] × [originalHeight]. */
    fun setImage(
        bitmap: Bitmap?,
        originalWidth: Int,
        originalHeight: Int,
    ) {
        // Recomposition may call this again with the same preview: keep the view as it is.
        if (bitmap === currentBitmap) return
        currentBitmap = bitmap
        renderer.setBitmap(bitmap)
        if (bitmap != null) transform.setImageSize(originalWidth, originalHeight)
        publish()
    }

    /** Colour around the photo (Settings → Canvas surround). */
    fun setSurround(argb: Int) {
        if (argb == currentSurround) return
        currentSurround = argb
        renderer.setSurround(argb)
        requestRender()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        // Ask for the panel's top rate (120 Hz on the POCO F7 Ultra) while the canvas is shown.
        holder.surface.setFrameRate(TARGET_FPS, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        transform.setViewSize(w, h)
        publish()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        frameScheduled = false
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        doubleTap.onTouchEvent(event)
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopMotion()
                tracker.clear()
                tracker.addMovement(event)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) startTwoFingers(event)
            }

            MotionEvent.ACTION_MOVE -> {
                if (twoFingers && event.pointerCount >= 2) moveTwoFingers(event)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2 && twoFingers) endTwoFingers(event, tracker)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                twoFingers = false
            }
        }
        return true
    }

    private fun startTwoFingers(event: MotionEvent) {
        stopMotion()
        twoFingers = true
        transform.beginGesture()
        this.tracker.start(event.getX(0), event.getY(0), event.getX(1), event.getY(1))
    }

    private fun moveTwoFingers(event: MotionEvent) {
        val t = this.tracker
        t.move(event.getX(0), event.getY(0), event.getX(1), event.getY(1))
        val snapped = transform.applyGesture(t.focusX, t.focusY, t.panX, t.panY, t.scaleFactor, t.rotationDelta)
        if (snapped) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        publish()
    }

    private fun endTwoFingers(
        event: MotionEvent,
        tracker: VelocityTracker,
    ) {
        twoFingers = false
        tracker.computeCurrentVelocity(MILLIS_PER_SECOND)
        val id0 = event.getPointerId(0)
        val id1 = event.getPointerId(1)
        val vx = (tracker.getXVelocity(id0) + tracker.getXVelocity(id1)) / 2f
        val vy = (tracker.getYVelocity(id0) + tracker.getYVelocity(id1)) / 2f
        if (hypot(vx, vy) >= MIN_FLING_SPEED) {
            fling.start(vx, vy)
            scheduleFrame()
        }
    }

    private fun animateTo(
        scale: Float,
        x: Float,
        y: Float,
        fitted: Boolean,
    ) {
        stopMotion()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            // Reduced motion: jump.
            transform.set(scale, transform.rotation, x, y, fitted)
            publish()
            return
        }
        animating = true
        animStartNanos = 0L
        animFromScale = transform.scale
        animToScale = scale
        animFromX = transform.centerX
        animFromY = transform.centerY
        animToX = x
        animToY = y
        animToFitted = fitted
        scheduleFrame()
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        val dt = if (lastFrameNanos == 0L) 0f else (frameTimeNanos - lastFrameNanos) / NANOS_PER_SECOND
        lastFrameNanos = frameTimeNanos
        if (animating) {
            if (animStartNanos == 0L) animStartNanos = frameTimeNanos
            val linear = ((frameTimeNanos - animStartNanos) / NANOS_PER_SECOND / DOUBLE_TAP_SECONDS).coerceIn(0f, 1f)
            val p = EASING.transform(linear)
            val scale = exp(ln(animFromScale) + (ln(animToScale) - ln(animFromScale)) * p)
            val done = linear >= 1f
            transform.set(
                scale,
                transform.rotation,
                animFromX + (animToX - animFromX) * p,
                animFromY + (animToY - animFromY) * p,
                done && animToFitted,
            )
            if (done) animating = false
        } else if (fling.isRunning) {
            fling.step(dt, step)
            if (!transform.panBy(step[0], step[1])) fling.stop()
        }
        publish()
        if (animating || fling.isRunning) scheduleFrame() else lastFrameNanos = 0L
    }

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopMotion() {
        fling.stop()
        animating = false
    }

    private fun publish() {
        transform.writeGlMatrix(matrix)
        renderer.setMatrix(matrix)
        requestRender()
        onTransformChanged?.invoke()
    }

    private companion object {
        const val GLES_VERSION = 3
        const val MATRIX_SIZE = 9
        const val TARGET_FPS = 120f
        const val MILLIS_PER_SECOND = 1000
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MIN_FLING_SPEED = 400f

        const val DOUBLE_TAP_SECONDS = MotionTokens.STANDARD_MILLIS / 1000f
        val EASING = MotionTokens.StandardEasing
    }
}
