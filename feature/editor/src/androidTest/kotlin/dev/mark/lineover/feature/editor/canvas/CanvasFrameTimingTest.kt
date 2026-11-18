package dev.mark.lineover.feature.editor.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mark.lineover.core.engine.EngineSession
import dev.mark.lineover.core.engine.Histogram
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.LightAdjustments
import dev.mark.lineover.core.model.LightParam
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The canvas side of a real engine session (what EngineCanvas does in the app). */
private class SessionRenderer(
    private val session: EngineSession,
) : CanvasRenderer {
    override val imageWidth get() = session.info.width
    override val imageHeight get() = session.info.height

    override fun attach(surface: Surface) {
        session.attachSurface(surface)
    }

    override fun resize(
        width: Int,
        height: Int,
    ) = session.setViewport(width, height)

    override fun detach() = session.detachSurface()

    override fun setTransform(
        a: Float,
        b: Float,
        c: Float,
        d: Float,
        e: Float,
        f: Float,
    ) = session.setTransform(a, b, c, d, e, f)

    override fun setSurround(argb: Int) = session.setSurround(0.07f, 0.07f, 0.07f)
}

/**
 * Pans the real canvas (SurfaceView + native engine render thread) with two fingers for two
 * seconds over a 12 MP photo and logs the engine's P95 frame interval and GL draw time (tag
 * `LineOverPerf`). On the CI emulator (SwiftShader) this proves correctness only: frames are
 * drawn while panning and the photo moves. Performance is judged on the phone.
 */
@RunWith(AndroidJUnit4::class)
class CanvasFrameTimingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun photo(): File {
        val file = File(instrumentation.targetContext.cacheDir, "canvas_timing.jpg")
        val bitmap = Bitmap.createBitmap(PHOTO_W, PHOTO_H, Bitmap.Config.ARGB_8888)
        val paint = Paint()
        paint.shader = LinearGradient(0f, 0f, PHOTO_W.toFloat(), PHOTO_H.toFloat(), Color.BLUE, Color.YELLOW, Shader.TileMode.CLAMP)
        Canvas(bitmap).drawRect(0f, 0f, PHOTO_W.toFloat(), PHOTO_H.toFloat(), paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return file
    }

    @Test
    fun panningRendersFramesAndLogsP95() {
        val file = photo()
        val session = requireNotNull(runBlocking { EngineSession.open(instrumentation.targetContext, Uri.fromFile(file)) })
        try {
            lateinit var view: CanvasView
            compose.setContent { AndroidView(factory = { CanvasView(it).also { v -> view = v } }, modifier = Modifier.fillMaxSize()) }
            compose.waitForIdle()
            instrumentation.runOnMainSync { view.setRenderer(SessionRenderer(session)) }
            waitFor { session.stats().frames > 0 }

            val startX = view.transform.centerX
            val framesBefore = session.stats().frames
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

            val stats = session.stats()
            val frames = stats.frames - framesBefore
            val report =
                (
                    "canvas pan $PAN_MILLIS ms: frames=$frames, P95 frame interval=%.2f ms, " +
                        "P95 GL draw=%.2f ms, %s (SwiftShader: correctness only)"
                ).format(stats.p95FrameIntervalUs / 1000.0, stats.p95DrawUs / 1000.0, if (stats.displayP3) "Display P3" else "sRGB")
            Log.i(TAG, report)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$TAG: $report\n") })
            assertTrue("frames were drawn while panning: $report", frames >= MIN_FRAMES)
        } finally {
            instrumentation.runOnMainSync { }
            session.close()
            file.delete()
        }
    }

    /**
     * Stage 5: an Exposure drag (a graph every 8 ms, all seven Light ops on) and the engine's
     * preview update latency, setGraph -> the new preview presented (target <= 16 ms on the
     * phone; logged here for SwiftShader, correctness only), plus a GPU histogram.
     */
    @Test
    fun lightEditsUpdateThePreviewAndLogTheLatency() {
        val file = photo()
        val session = requireNotNull(runBlocking { EngineSession.open(instrumentation.targetContext, Uri.fromFile(file)) })
        try {
            lateinit var view: CanvasView
            compose.setContent { AndroidView(factory = { CanvasView(it).also { v -> view = v } }, modifier = Modifier.fillMaxSize()) }
            compose.waitForIdle()
            instrumentation.runOnMainSync { view.setRenderer(SessionRenderer(session)) }
            waitFor { session.stats().frames > 0 }
            session.setHistogramEnabled(true)
            var light = LightAdjustments().with(LightParam.Highlights, -40f).with(LightParam.Shadows, 40f)
            light = light.with(LightParam.Contrast, 20f).with(LightParam.Whites, 10f).with(LightParam.Blacks, -10f)
            light = light.with(LightParam.Brightness, 15f)
            for (i in 1..EDITS) {
                val ev = Math.sin(i * Math.PI * 2 / EDITS).toFloat() * 2f
                val graph = EditGraph().withBackgroundOps(light.with(LightParam.Exposure, ev).toOps())
                assertNull(session.setGraph(graph))
                Thread.sleep(STEP_MILLIS.toLong())
            }
            // SwiftShader takes ~0.4 s per frame of seven ops on a 3 MP preview: wait for the
            // edited frames to be presented (and timed) rather than for a fixed time.
            waitFor { session.stats().updates > 0 }
            waitFor { session.histogram.value != null }
            val stats = session.stats()
            val histogram = requireNotNull(session.histogram.value)
            val report =
                (
                    "light edits: $EDITS graphs, ${stats.updates} preview updates measured, last %.2f ms, " +
                        "P95 preview update=%.2f ms (target 16 ms on the phone; SwiftShader: correctness only), " +
                        "histogram L pixels=${histogram.total(Histogram.LUMA)}"
                ).format(stats.lastUpdateUs / 1000.0, stats.p95UpdateUs / 1000.0)
            Log.i(TAG, report)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$TAG: $report\n") })
            assertTrue("preview updates were measured: $report", stats.updates > 0)
            val preview = session.preview.bitmap
            assertEquals((preview.width * preview.height).toLong(), histogram.total(Histogram.LUMA))
        } finally {
            session.close()
            file.delete()
        }
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

    private companion object {
        const val TAG = "LineOverPerf"
        const val PHOTO_W = 4000
        const val PHOTO_H = 3000
        const val SPAN = 150f
        const val PAN_MILLIS = 2000
        const val STEP_MILLIS = 8
        const val SETTLE_MILLIS = 300L
        const val MIN_FRAMES = 10
        const val TIMEOUT_MILLIS = 20_000L
        const val POLL_MILLIS = 20L
        const val EDITS = 120
    }
}
