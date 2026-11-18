package dev.mark.lineover.feature.editor.canvas

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.feature.editor.EditorScreen
import dev.mark.lineover.feature.editor.EditorViewModel
import dev.mark.lineover.feature.editor.FakeCanvasFactory
import dev.mark.lineover.feature.editor.FakeEditorCanvas
import dev.mark.lineover.feature.editor.FakeEditorLibrary
import dev.mark.lineover.feature.editor.PhotoCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Canvas gestures must never cause recomposition (rule R6; Stage 3 "IF WRONG: jank").
 * The real editor is composed around the real CanvasView; a two-finger pinch, pan and
 * rotate is dispatched to the view and the recompositions are counted.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class CanvasRecompositionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pinchPanAndRotateDoNotRecompose() {
        val engine = FakeEditorCanvas()
        var screenCompositions = 0
        var canvasCompositions = 0
        compose.setContent {
            LineOverTheme {
                val vm = remember { EditorViewModel(1, FakeEditorLibrary(), InMemoryAppPreferences(), FakeCanvasFactory(engine)) }
                val state by vm.state.collectAsState()
                SideEffect { screenCompositions++ }
                EditorScreen(
                    state,
                    vm::onIntent,
                    onBack = {},
                    onExport = {},
                    canvas = { modifier ->
                        SideEffect { canvasCompositions++ }
                        PhotoCanvas(state, modifier)
                    },
                )
            }
        }
        compose.waitForIdle()
        val view = findCanvas(compose.activity.window.decorView)
        val before = Triple(view.transform.scale, view.transform.centerX, view.transform.rotation)
        val transformsBefore = engine.transforms
        val screenBefore = screenCompositions
        val canvasBefore = canvasCompositions

        compose.runOnUiThread { twoFingerGesture(view) }
        compose.waitForIdle()

        val after = Triple(view.transform.scale, view.transform.centerX, view.transform.rotation)
        assertNotEquals("the gesture moved the photo", before, after)
        assertTrue("zoomed in", view.transform.scale > before.first)
        assertTrue("every move reached the engine", engine.transforms - transformsBefore >= 60)
        assertEquals("editor recompositions during the gesture", screenBefore, screenCompositions)
        assertEquals("canvas recompositions during the gesture", canvasBefore, canvasCompositions)
    }

    /** Two fingers spread apart, move right and turn 30°, over 60 events. */
    private fun twoFingerGesture(view: View) {
        val down = SystemClock.uptimeMillis()
        val cx = view.width / 2f
        val cy = view.height / 2f

        fun event(
            action: Int,
            t: Long,
            step: Int,
            count: Int,
        ): MotionEvent {
            val f = step / 60f
            val spread = 100f + 150f * f
            val angle = Math.toRadians(30.0 * f)
            val dx = (spread * Math.cos(angle)).toFloat()
            val dy = (spread * Math.sin(angle)).toFloat()
            val shift = 80f * f
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
                        x = cx + shift + if (i == 0) -dx else dx
                        y = cy + if (i == 0) -dy else dy
                        pressure = 1f
                        size = 1f
                    }
                }
            return MotionEvent.obtain(down, t, action, count, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        }
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, down, 0, 1))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down + 8, 0, 2))
        for (step in 1..60) view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, down + 8 + step * 8L, step, 2))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down + 600, 60, 2))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, down + 610, 60, 1))
    }

    private fun findCanvas(root: View): CanvasView {
        if (root is CanvasView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                runCatching { return findCanvas(root.getChildAt(i)) }
            }
        }
        error("no CanvasView under $root")
    }
}
