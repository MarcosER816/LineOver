package dev.mark.lineover.feature.editor

import android.view.MotionEvent
import dev.mark.lineover.feature.editor.canvas.MultiFingerTap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two- and three-finger taps: quick and still; pinches, pans and slow presses are not taps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MultiFingerTapTest {
    private val slop = 30f
    private val tap = MultiFingerTap(slop)

    /** Fingers at [points] go down one by one, optionally move by [move], then lift; returns the result of the last lift. */
    private fun gesture(
        points: List<Pair<Float, Float>>,
        upAt: Long = 120L,
        move: Float = 0f,
    ): Int {
        var result = 0

        fun event(
            time: Long,
            action: Int,
            count: Int,
            dx: Float = 0f,
        ): MotionEvent {
            val props = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i } }
            val coords =
                Array(count) { i ->
                    MotionEvent.PointerCoords().apply {
                        x = points[i].first + dx
                        y = points[i].second
                    }
                }
            return MotionEvent.obtain(0L, time, action, count, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        }
        result += tap.onTouchEvent(event(0L, MotionEvent.ACTION_DOWN, 1))
        for (n in 2..points.size) {
            result +=
                tap.onTouchEvent(event(10L * n, MotionEvent.ACTION_POINTER_DOWN or ((n - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), n))
        }
        if (move != 0f) result += tap.onTouchEvent(event(60L, MotionEvent.ACTION_MOVE, points.size, move))
        for (n in points.size downTo 2) {
            result +=
                tap.onTouchEvent(event(upAt - 5, MotionEvent.ACTION_POINTER_UP or ((n - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), n))
        }
        return result + tap.onTouchEvent(event(upAt, MotionEvent.ACTION_UP, 1))
    }

    private val two = listOf(100f to 100f, 300f to 120f)
    private val three = two + (200f to 400f)

    @Test
    fun quickStillTapsCountTheirFingers() {
        assertEquals(2, gesture(two))
        assertEquals(3, gesture(three))
    }

    @Test
    fun aPinchAPanASlowPressAndOneFingerAreNotTaps() {
        assertEquals("moved more than the slop", 0, gesture(two, move = slop + 5f))
        assertEquals("held 250 ms or more", 0, gesture(two, upAt = 260L))
        assertEquals("one finger", 0, gesture(listOf(100f to 100f)))
        assertEquals("a small tremor is fine", 2, gesture(two, move = slop - 5f))
    }
}
