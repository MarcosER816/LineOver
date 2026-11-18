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

    private companion object {
        const val WIDTH = 1000
        const val HEIGHT = 1600
    }
}
