package dev.mark.lineover.feature.editor.canvas

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.mark.lineover.feature.editor.FakeEditorCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** One-finger canvas gestures of Stage 5: hold to compare and swipe mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CanvasGestureTest {
    private val renderer = FakeEditorCanvas()
    private val events = mutableListOf<String>()
    private val view =
        CanvasView(ApplicationProvider.getApplicationContext()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WIDTH, HEIGHT)
            setRenderer(renderer)
            gestureListener =
                object : CanvasGestureListener {
                    override fun onHoldChanged(holding: Boolean) {
                        events += "hold $holding"
                    }

                    override fun onSwipeSelect(steps: Int) {
                        events += "select $steps"
                    }

                    override fun onSwipeAdjust(fraction: Float) {
                        events += "adjust %.2f".format(java.util.Locale.ROOT, fraction)
                    }

                    override fun onSwipeActive(active: Boolean) {
                        events += "swipe $active"
                    }

                    override fun onEyedropperMove(
                        viewX: Float,
                        viewY: Float,
                        u: Float,
                        v: Float,
                    ) {
                        events += "loupe %.0f %.2f %.2f".format(java.util.Locale.ROOT, viewX, u, v)
                    }

                    override fun onEyedropperEnd(
                        u: Float?,
                        v: Float?,
                    ) {
                        events += if (u == null || v == null) "pick cancelled" else "pick %.2f %.2f".format(java.util.Locale.ROOT, u, v)
                    }

                    override fun onTargetedStart(
                        u: Float,
                        v: Float,
                    ) {
                        events += "target %.2f %.2f".format(java.util.Locale.ROOT, u, v)
                    }

                    override fun onTargetedDrag(fraction: Float) {
                        events += "drag %.3f".format(java.util.Locale.ROOT, fraction)
                    }

                    override fun onTargetedEnd() {
                        events += "target end"
                    }
                }
        }
    private val down = SystemClock.uptimeMillis()

    private fun touch(
        action: Int,
        x: Float,
        y: Float,
    ) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun idle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    @Test
    fun holdingStillShowsTheOriginalUntilTheFingerLifts() {
        touch(MotionEvent.ACTION_DOWN, 200f, 300f)
        idle(100)
        assertFalse("not before 250 ms", renderer.showingOriginal)
        idle(200)
        assertTrue(renderer.showingOriginal)
        touch(MotionEvent.ACTION_UP, 200f, 300f)
        assertFalse(renderer.showingOriginal)
        assertEquals(listOf("hold true", "hold false"), events)
    }

    @Test
    fun movingBeforeTheHoldDoesNotCompare() {
        touch(MotionEvent.ACTION_DOWN, 200f, 300f)
        touch(MotionEvent.ACTION_MOVE, 260f, 300f)
        idle(400)
        touch(MotionEvent.ACTION_UP, 260f, 300f)
        assertFalse(renderer.showingOriginal)
        assertTrue("swipe mode is off: nothing reported", events.isEmpty())
    }

    @Test
    fun swipeModeHorizontalChangesTheValue() {
        view.swipeMode = true
        touch(MotionEvent.ACTION_DOWN, 100f, 300f)
        touch(MotionEvent.ACTION_MOVE, 160f, 300f) // past the slop: horizontal
        touch(MotionEvent.ACTION_MOVE, 260f, 300f) // +100 px of 1000 px
        touch(MotionEvent.ACTION_UP, 260f, 300f)
        assertEquals(listOf("swipe true", "adjust 0.10", "swipe false"), events)
    }

    @Test
    fun swipeModeVerticalPicksTheParameter() {
        view.swipeMode = true
        val step = 48f * view.resources.displayMetrics.density
        touch(MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(MotionEvent.ACTION_MOVE, 100f, 100f + step * 1.2f)
        touch(MotionEvent.ACTION_MOVE, 100f, 100f + step * 2.5f)
        touch(MotionEvent.ACTION_UP, 100f, 100f + step * 2.5f)
        assertEquals(listOf("swipe true", "select 1", "select 1", "swipe false"), events)
        assertFalse(renderer.showingOriginal)
    }

    /** The fake image is 8160 x 6120, fitted into the 1000 x 1600 view: its centre is (0.5, 0.5). */
    @Test
    fun eyedropperReportsTheImagePointAndPicksOnLift() {
        view.pickMode = PickMode.Eyedropper
        touch(MotionEvent.ACTION_DOWN, 500f, 800f)
        touch(MotionEvent.ACTION_MOVE, 600f, 800f)
        idle(400)
        touch(MotionEvent.ACTION_UP, 600f, 800f)
        assertEquals(3, events.size)
        assertEquals("loupe 500 0.50 0.50", events[0])
        assertTrue(events[1].startsWith("loupe 600 0.6"))
        assertTrue(events[2].startsWith("pick 0.6"))
        assertFalse("no compare while picking", renderer.showingOriginal)
    }

    @Test
    fun targetedDragReportsVerticalMovement() {
        view.pickMode = PickMode.Targeted
        touch(MotionEvent.ACTION_DOWN, 500f, 800f)
        touch(MotionEvent.ACTION_MOVE, 500f, 640f) // up 160 px of 1600
        touch(MotionEvent.ACTION_UP, 500f, 640f)
        assertEquals(listOf("target 0.50 0.50", "drag 0.100", "target end"), events)
    }

    private companion object {
        const val WIDTH = 1000
        const val HEIGHT = 1600
    }
}
