package dev.mark.lineover.feature.editor.canvas

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.VelocityTracker
import android.view.ViewConfiguration
import dev.mark.lineover.core.designsystem.MotionTokens
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln

/** One-finger gestures the editor reacts to. Called on the UI thread, never per pixel of work. */
interface CanvasGestureListener {
    /** Hold to compare started (true) or ended (false). */
    fun onHoldChanged(holding: Boolean)

    /** Swipe mode: the finger moved [steps] parameters down (positive) or up. */
    fun onSwipeSelect(steps: Int)

    /** Swipe mode: the finger moved horizontally by [fraction] of the canvas width. */
    fun onSwipeAdjust(fraction: Float)

    /** Swipe mode: a swipe started (true) or ended (false), for the HUD. */
    fun onSwipeActive(active: Boolean)

    /** Eyedropper: the finger is at view ([viewX], [viewY]) = image point ([u], [v]), 0..1. */
    fun onEyedropperMove(
        viewX: Float,
        viewY: Float,
        u: Float,
        v: Float,
    ) = Unit

    /** Eyedropper: lifted at image point ([u], [v]); null when cancelled (second finger). */
    fun onEyedropperEnd(
        u: Float?,
        v: Float?,
    ) = Unit

    /** Targeted adjustment: a drag started at image point ([u], [v]). */
    fun onTargetedStart(
        u: Float,
        v: Float,
    ) = Unit

    /** Targeted adjustment: the finger moved up by [fraction] of the canvas height (down < 0). */
    fun onTargetedDrag(fraction: Float) = Unit

    fun onTargetedEnd() = Unit
}

/** What one finger does on the canvas while a tool needs the image itself. */
enum class PickMode { None, Eyedropper, Targeted }

/**
 * The photo canvas: a SurfaceView the native engine renders into on its own render
 * thread (EGL, GLES 3). Gestures are handled here, in the MotionEvent path, and never
 * touch Compose state, so panning, zooming and rotating cause no recomposition (rule R6,
 * 8.3 ms per frame at 120 Hz). Each gesture event hands six floats to the engine.
 *
 * - Two fingers: pan, pinch zoom (0.1×–32× around the focal point) and rotate (snaps to
 *   0/90/180/270° with a haptic tick).
 * - Double tap: fit ↔ 100 %.
 * - Lifting the fingers while panning flings with an exponential decay.
 * - One finger held still for 250 ms: the original is shown until it lifts (compare).
 * - Swipe mode (Light panel option): one finger moving vertically picks the parameter,
 *   horizontally changes it ([CanvasGestureListener]).
 * - Pick modes ([PickMode]): the white-balance eyedropper (the finger's image point, the
 *   pick on lift) and the HSL targeted adjustment (vertical drag on a colour).
 *
 * Other one-finger gestures are left free for the tools of later stages (brushes, handles).
 */

@SuppressLint("ViewConstructor")
@Suppress("TooManyFunctions") // every canvas gesture must stay in this view (no recomposition)
class CanvasView(
    context: Context,
) : SurfaceView(context),
    SurfaceHolder.Callback,
    Choreographer.FrameCallback {
    val transform = CanvasTransform()
    private var renderer: CanvasRenderer? = null
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val tracker = TwoFingerTracker()
    private val fling = FlingDecay()
    private val affine = FloatArray(AFFINE_SIZE)
    private val step = FloatArray(2)
    private val target = FloatArray(3)
    private var velocityTracker: VelocityTracker? = null
    private var twoFingers = false
    private var frameScheduled = false
    private var lastFrameNanos = 0L
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

    /** Called after every change of the transform, on the UI thread (no allocation). */
    var onTransformChanged: (() -> Unit)? = null

    /** Hold-to-compare and swipe-mode callbacks. */
    var gestureListener: CanvasGestureListener? = null

    /** Swipe mode: one finger adjusts the selected parameter instead of doing nothing. */
    var swipeMode = false

    /** Eyedropper / targeted adjustment: one finger picks on the image (no compare, no swipe). */
    var pickMode = PickMode.None
        set(value) {
            if (field != value) endOneFinger()
            field = value
        }

    private val imagePoint = FloatArray(2)
    private var lastY = 0f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeStepPx = SWIPE_STEP_DP * resources.displayMetrics.density
    private var oneFinger = OneFinger.None
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var selectSteps = 0
    private val holdRunnable = Runnable { startHold() }

    // Not View.postDelayed: that queue waits until the view is attached.
    private val mainHandler = Handler(Looper.getMainLooper())

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
        holder.addCallback(this)
        contentDescription = "Photo canvas. Pinch to zoom, two fingers to pan and rotate, double tap to fit."
        isFocusable = false
    }

    /** The engine that draws the image (null: nothing to show yet). Same renderer again is a no-op. */
    fun setRenderer(renderer: CanvasRenderer?) {
        if (renderer === this.renderer) return
        this.renderer?.detach()
        this.renderer = renderer
        if (renderer == null) return
        transform.setImageSize(renderer.imageWidth, renderer.imageHeight)
        if (currentSurround != 0) renderer.setSurround(currentSurround)
        surface?.let { attachTo(renderer, it) }
        publish()
    }

    /** Colour around the photo (Settings → Canvas surround). */
    fun setSurround(argb: Int) {
        if (argb == currentSurround) return
        currentSurround = argb
        renderer?.setSurround(argb)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Ask for the panel's top rate (120 Hz on the POCO F7 Ultra) while the canvas is shown.
        holder.surface.setFrameRate(TARGET_FPS, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        surface = holder.surface
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        surfaceWidth = width
        surfaceHeight = height
        surface = holder.surface
        renderer?.let { attachTo(it, holder.surface) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderer?.detach()
        surface = null
    }

    private fun attachTo(
        renderer: CanvasRenderer,
        surface: Surface,
    ) {
        renderer.attach(surface)
        renderer.resize(surfaceWidth.takeIf { it > 0 } ?: width, surfaceHeight.takeIf { it > 0 } ?: height)
        publish()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        transform.setViewSize(w, h)
        renderer?.resize(w, h)
        publish()
    }

    override fun onDetachedFromWindow() {
        endOneFinger()
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
                beginOneFinger(event.x, event.y)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                endOneFinger()
                if (event.pointerCount == 2) startTwoFingers(event)
            }

            MotionEvent.ACTION_MOVE -> {
                move(event)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2 && twoFingers) endTwoFingers(event, tracker)
            }

            MotionEvent.ACTION_UP -> {
                twoFingers = false
                endOneFinger(lifted = true, x = event.x, y = event.y)
            }

            MotionEvent.ACTION_CANCEL -> {
                twoFingers = false
                endOneFinger()
            }
        }
        return true
    }

    /** The image point under view (x, y), normalised to 0..1 (unclamped). */
    private fun toImage(
        x: Float,
        y: Float,
    ): FloatArray {
        transform.viewToImage(x, y, imagePoint)
        imagePoint[0] /= transform.imageWidth
        imagePoint[1] /= transform.imageHeight
        return imagePoint
    }

    private fun beginOneFinger(
        x: Float,
        y: Float,
    ) {
        when (pickMode) {
            PickMode.Eyedropper -> {
                oneFinger = OneFinger.Eyedropper
                val p = toImage(x, y)
                gestureListener?.onEyedropperMove(x, y, p[0], p[1])
                return
            }

            PickMode.Targeted -> {
                oneFinger = OneFinger.Targeted
                lastY = y
                val p = toImage(x, y)
                gestureListener?.onTargetedStart(p[0], p[1])
                return
            }

            PickMode.None -> {
                Unit
            }
        }
        oneFinger = OneFinger.Pending
        downX = x
        downY = y
        lastX = x
        selectSteps = 0
        mainHandler.postDelayed(holdRunnable, HOLD_MILLIS)
    }

    private fun startHold() {
        if (oneFinger != OneFinger.Pending) return
        oneFinger = OneFinger.Hold
        renderer?.setShowOriginal(true)
        gestureListener?.onHoldChanged(true)
    }

    private fun moveOneFinger(
        x: Float,
        y: Float,
    ) {
        when (oneFinger) {
            OneFinger.Pending -> {
                val dx = x - downX
                val dy = y - downY
                if (hypot(dx, dy) <= touchSlop) return
                mainHandler.removeCallbacks(holdRunnable)
                oneFinger =
                    when {
                        !swipeMode -> OneFinger.Free
                        kotlin.math.abs(dx) >= kotlin.math.abs(dy) -> OneFinger.SwipeAdjust
                        else -> OneFinger.SwipeSelect
                    }
                lastX = x
                if (oneFinger != OneFinger.Free) gestureListener?.onSwipeActive(true)
                // The move that locked the axis already counts for picking.
                if (oneFinger == OneFinger.SwipeSelect) moveOneFinger(x, y)
            }

            OneFinger.SwipeAdjust -> {
                val w = width.takeIf { it > 0 } ?: return
                gestureListener?.onSwipeAdjust((x - lastX) / w)
                lastX = x
            }

            OneFinger.Eyedropper, OneFinger.Targeted -> {
                movePick(x, y)
            }

            OneFinger.SwipeSelect -> {
                val steps = ((y - downY) / swipeStepPx).toInt()
                if (steps != selectSteps) {
                    gestureListener?.onSwipeSelect(steps - selectSteps)
                    selectSteps = steps
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }

            else -> {
                Unit
            }
        }
    }

    private fun movePick(
        x: Float,
        y: Float,
    ) {
        if (oneFinger == OneFinger.Eyedropper) {
            val p = toImage(x, y)
            gestureListener?.onEyedropperMove(x, y, p[0], p[1])
        } else {
            val h = height.takeIf { it > 0 } ?: return
            gestureListener?.onTargetedDrag((lastY - y) / h)
            lastY = y
        }
    }

    /** [lifted]: the finger went up (an eyedropper pick), not a cancel. */
    private fun endOneFinger(
        lifted: Boolean = false,
        x: Float = 0f,
        y: Float = 0f,
    ) {
        mainHandler.removeCallbacks(holdRunnable)
        when (oneFinger) {
            OneFinger.Eyedropper -> {
                if (lifted) {
                    val p = toImage(x, y)
                    gestureListener?.onEyedropperEnd(p[0], p[1])
                } else {
                    gestureListener?.onEyedropperEnd(null, null)
                }
            }

            OneFinger.Targeted -> {
                gestureListener?.onTargetedEnd()
            }

            OneFinger.Hold -> {
                renderer?.setShowOriginal(false)
                gestureListener?.onHoldChanged(false)
            }

            OneFinger.SwipeAdjust, OneFinger.SwipeSelect -> {
                gestureListener?.onSwipeActive(false)
            }

            else -> {
                Unit
            }
        }
        oneFinger = OneFinger.None
    }

    private enum class OneFinger { None, Pending, Hold, Free, SwipeAdjust, SwipeSelect, Eyedropper, Targeted }

    private fun move(event: MotionEvent) {
        if (twoFingers && event.pointerCount >= 2) {
            moveTwoFingers(event)
        } else if (event.pointerCount == 1) {
            moveOneFinger(event.x, event.y)
        }
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

    @Suppress("MagicNumber") // affine element indices
    private fun publish() {
        renderer?.let {
            transform.writeAffine(affine)
            it.setTransform(affine[0], affine[1], affine[2], affine[3], affine[4], affine[5])
        }
        onTransformChanged?.invoke()
    }

    private companion object {
        const val AFFINE_SIZE = 6
        const val TARGET_FPS = 120f
        const val MILLIS_PER_SECOND = 1000
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MIN_FLING_SPEED = 400f
        const val HOLD_MILLIS = 250L
        const val SWIPE_STEP_DP = 48f

        const val DOUBLE_TAP_SECONDS = MotionTokens.STANDARD_MILLIS / 1000f
        val EASING = MotionTokens.StandardEasing
    }
}
