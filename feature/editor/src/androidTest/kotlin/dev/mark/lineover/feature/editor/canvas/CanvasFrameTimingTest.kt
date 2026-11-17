package dev.mark.lineover.feature.editor.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pans the real GL canvas with two fingers for two seconds and logs the P95 frame interval
 * and GL draw time (tag `LineOverPerf`). On the CI emulator (SwiftShader) this proves
 * correctness only: frames are rendered while panning and the photo moves. Performance is
 * judged on the phone (rule: never in CI).
 */
@RunWith(AndroidJUnit4::class)
class CanvasFrameTimingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun panningRendersFramesAndLogsP95() {
        lateinit var view: CanvasView
        compose.setContent { AndroidView(factory = { CanvasView(it).also { v -> view = v } }, modifier = Modifier.fillMaxSize()) }
        compose.waitForIdle()
        instrumentation.runOnMainSync { view.setImage(previewBitmap(), ORIGINAL_W, ORIGINAL_H) }
        waitFor { view.frameStats.frames > 0 }

        val startX = view.transform.centerX
        view.frameStats.reset()
        val down = SystemClock.uptimeMillis()
        val cx = view.width / 2f
        val cy = view.height / 2f
        send(view, MotionEvent.ACTION_DOWN, down, down, cx - SPAN, cy, cx + SPAN, cy, 1)
        send(view, pointerAction(MotionEvent.ACTION_POINTER_DOWN), down, down + 1, cx - SPAN, cy, cx + SPAN, cy, 2)
        val steps = PAN_MILLIS / STEP_MILLIS
        for (i in 1..steps) {
            // Back and forth across the screen, so the pan limit never stops the photo.
            val phase = Math.sin(i * Math.PI * 2 / steps).toFloat()
            val dx = phase * view.width * 0.3f
            send(view, MotionEvent.ACTION_MOVE, down, SystemClock.uptimeMillis(), cx - SPAN + dx, cy, cx + SPAN + dx, cy, 2)
            if (i == steps / 4) assertTrue("the photo follows the fingers", view.transform.centerX > startX)
            Thread.sleep(STEP_MILLIS.toLong())
        }
        val now = SystemClock.uptimeMillis()
        send(view, pointerAction(MotionEvent.ACTION_POINTER_UP), down, now, cx - SPAN, cy, cx + SPAN, cy, 2)
        send(view, MotionEvent.ACTION_UP, down, now + 1, cx - SPAN, cy, cx + SPAN, cy, 1)
        Thread.sleep(SETTLE_MILLIS)

        val stats = view.frameStats
        val report =
            "canvas pan $PAN_MILLIS ms: frames=${stats.frames}, " +
                "P95 frame interval=%.2f ms, P95 GL draw=%.2f ms (SwiftShader: correctness only)"
                    .format(stats.p95IntervalMillis(), stats.p95DrawMillis())
        Log.i(TAG, report)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$TAG: $report\n") })
        assertTrue("frames were rendered while panning: $report", stats.frames >= MIN_FRAMES)
    }

    private fun pointerAction(action: Int) = action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    @Suppress("LongParameterList")
    private fun send(
        view: CanvasView,
        action: Int,
        down: Long,
        time: Long,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        count: Int,
    ) {
        val props =
            Array(count) {
                MotionEvent.PointerProperties().apply {
                    id = it
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
        val coords =
            Array(count) { i ->
                MotionEvent.PointerCoords().apply {
                    x = if (i == 0) x0 else x1
                    y = if (i == 0) y0 else y1
                    pressure = 1f
                    size = 1f
                }
            }
        val event = MotionEvent.obtain(down, time, action, count, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        instrumentation.runOnMainSync { view.dispatchTouchEvent(event) }
        event.recycle()
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "timed out" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** A 2048 × 1536 stand-in for the decoded preview of a 50 MP photo. */
    private fun previewBitmap(): Bitmap =
        Bitmap.createBitmap(PREVIEW_W, PREVIEW_H, Bitmap.Config.ARGB_8888).also { bitmap ->
            val paint = Paint()
            paint.shader = LinearGradient(0f, 0f, PREVIEW_W.toFloat(), PREVIEW_H.toFloat(), Color.BLUE, Color.YELLOW, Shader.TileMode.CLAMP)
            Canvas(bitmap).drawRect(0f, 0f, PREVIEW_W.toFloat(), PREVIEW_H.toFloat(), paint)
        }

    private companion object {
        const val TAG = "LineOverPerf"
        const val ORIGINAL_W = 8160
        const val ORIGINAL_H = 6120
        const val PREVIEW_W = 2048
        const val PREVIEW_H = 1536
        const val SPAN = 150f
        const val PAN_MILLIS = 2000
        const val STEP_MILLIS = 8
        const val SETTLE_MILLIS = 300L
        const val MIN_FRAMES = 10
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
    }
}
