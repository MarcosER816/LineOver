package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.model.CropAspect
import dev.mark.lineover.core.model.GeometryMath
import dev.mark.lineover.core.model.GeometryParam
import dev.mark.lineover.core.model.LightParam
import dev.mark.lineover.core.model.LineSegment
import dev.mark.lineover.core.model.OpParams
import dev.mark.lineover.core.model.UprightMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/** Stage 9 in the ViewModel: Crop and Optics build one geometry op, first in the graph. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeometryEditingTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val canvas = FakeEditorCanvas()

    private fun TestScope.viewModel(): EditorViewModel =
        EditorViewModel(
            1,
            FakeEditorLibrary(),
            InMemoryAppPreferences(),
            FakeCanvasFactory(canvas),
            renderDispatcher = dispatcher,
            saveScope = CoroutineScope(dispatcher),
            clock = { 42L },
        ).also { runCurrent() }

    private fun EditorViewModel.act(action: GeometryAction) = onIntent(EditorIntent.Geometry(action))

    private val EditorViewModel.op: OpParams.Geometry?
        get() =
            state.value.graph.layers
                .firstOrNull()
                ?.ops
                ?.filterIsInstance<OpParams.Geometry>()
                ?.firstOrNull()

    private fun EditorViewModel.labels() =
        state.value.history.entries
            .map { it.label }

    @Test
    fun cropFourFiveCropsTheEditAndTheCanvasShowsTheFrameWhileCropping() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onIntent(EditorIntent.SelectTool(CROP_TOOL))
            vm.act(GeometryAction.Aspect(CropAspect.FourFive))
            runCurrent()
            val op = requireNotNull(vm.op)
            assertEquals(0.8, op.outputWidth.toDouble() / op.outputHeight, 0.01)
            assertEquals(6120, op.outputHeight)
            // While the Crop panel is open the canvas draws the whole (uncropped) frame...
            assertEquals(
                null,
                canvas.graphs
                    .last()
                    .layers
                    .first()
                    .ops
                    .filterIsInstance<OpParams.Geometry>()
                    .firstOrNull(),
            )
            assertEquals(8160 to 6120, vm.state.value.canvasSize)
            // ... and the crop once it closes.
            vm.onIntent(EditorIntent.ClosePanel)
            runCurrent()
            assertEquals(
                op,
                canvas.graphs
                    .last()
                    .layers
                    .first()
                    .ops
                    .first(),
            )
            assertEquals(op.outputWidth to op.outputHeight, vm.state.value.canvasSize)
            assertEquals("Aspect 4:5", vm.labels().last())
        }

    @Test
    fun aCropDragOnTheCanvasIsOneStep() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onIntent(EditorIntent.SelectTool(CROP_TOOL))
            val gestures = vm.cropGestures
            gestures.onCropDragging(true)
            assertTrue(vm.state.value.cropDragging)
            for (left in listOf(0.05f, 0.1f, 0.2f)) gestures.onCropRect(floatArrayOf(left, 0f, 1f, 1f), false)
            gestures.onCropRect(floatArrayOf(0.25f, 0f, 1f, 1f), true)
            gestures.onCropDragging(false)
            assertEquals(listOf("Opened", "Crop"), vm.labels())
            assertEquals(6120, vm.op!!.outputWidth)
            // Double tap on the frame resets it.
            gestures.onCropReset()
            assertNull(vm.op)
        }

    @Test
    fun aStraightenDragIsOneStepAndUndoRestoresIt() =
        runTest(dispatcher) {
            val vm = viewModel()
            for (v in listOf(1f, 2f, 3.5f)) vm.act(GeometryAction.Set(GeometryParam.Straighten, v))
            vm.onIntent(EditorIntent.EndEdit)
            assertEquals(listOf("Opened", "Straighten +3.5"), vm.labels())
            // Constrained: the output shrinks so no wedge shows.
            assertTrue(vm.op!!.outputWidth < 8160)
            vm.onIntent(EditorIntent.Undo)
            runCurrent()
            assertEquals(0f, vm.state.value.geometry.straighten)
            assertNull(vm.op)
            vm.onIntent(EditorIntent.Redo)
            assertEquals(3.5f, vm.state.value.geometry.straighten)
        }

    @Test
    fun otherEditsKeepTheGeometryFirst() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.act(GeometryAction.Rotate(clockwise = true))
            vm.onIntent(EditorIntent.SetLight(LightParam.Exposure, 0.5f))
            val ops =
                vm.state.value.graph.layers
                    .first()
                    .ops
            assertTrue(ops.first() is OpParams.Geometry)
            assertEquals(6120 to 8160, vm.op!!.outputWidth to vm.op!!.outputHeight)
            assertEquals(2, ops.size)
        }

    @Test
    fun rotateAndFlipAreNamedSteps() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.act(GeometryAction.Rotate(clockwise = false))
            vm.act(GeometryAction.Flip(horizontal = true))
            assertEquals(listOf("Opened", "Rotate left", "Flip horizontal"), vm.labels())
            assertEquals(3, vm.state.value.geometry.quarterTurns)
            assertTrue(vm.state.value.geometry.flipHorizontal)
        }

    @Test
    fun uprightAutoUsesThePreviewSegments() =
        runTest(dispatcher) {
            canvas.segments = facade(8160, 6120, pitch = 9.0)
            val vm = viewModel()
            vm.act(GeometryAction.Upright(UprightMode.Auto))
            runCurrent()
            val g = vm.state.value.geometry
            assertEquals(UprightMode.Auto, g.upright)
            assertEquals(9.0, g.uprightPitch.toDouble(), 0.3)
            assertEquals("Upright Auto", vm.labels().last())
        }

    /** IF WRONG rule: beyond ±25° Upright levels instead and says so. */
    @Test
    fun uprightOutOfRangeFallsBackToLevel() =
        runTest(dispatcher) {
            canvas.segments = facade(8160, 6120, pitch = 40.0, roll = 2.0)
            val vm = viewModel()
            vm.act(GeometryAction.Upright(UprightMode.Full))
            runCurrent()
            val g = vm.state.value.geometry
            assertEquals(0f, g.uprightPitch)
            assertTrue(abs(g.uprightRoll) <= 25f)
        }

    @Test
    fun guidedUprightSolvesTheDrawnLines() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.act(GeometryAction.Upright(UprightMode.Guided))
            vm.onIntent(EditorIntent.SelectTool(UPRIGHT_TOOL))
            assertEquals(dev.mark.lineover.feature.editor.canvas.CropMode.Guides, vm.state.value.cropMode)
            val verticals = facade(8160, 6120, pitch = 8.0).take(5)
            vm.cropGestures.onGuideLine(floatArrayOf(verticals[0].x1, verticals[0].y1, verticals[0].x2, verticals[0].y2))
            vm.cropGestures.onGuideLine(floatArrayOf(verticals[4].x1, verticals[4].y1, verticals[4].x2, verticals[4].y2))
            assertEquals(2, vm.state.value.geometry.guides.size)
            assertEquals(
                8.0,
                vm.state.value.geometry.uprightPitch
                    .toDouble(),
                0.3,
            )
        }

    @Test
    fun autoStraightenAndAutoChromaticUseTheAnalysis() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.act(GeometryAction.AutoStraighten)
            vm.act(GeometryAction.AutoChromatic)
            runCurrent()
            val g = vm.state.value.geometry
            assertEquals(-2.5f, g.straighten)
            assertEquals(40f, g.chromaticRed, 0.01f)
            assertEquals(-20f, g.chromaticBlue, 0.01f)
        }

    // Five verticals and five horizontals of a facade seen with the camera tilted.
    private fun facade(
        w: Int,
        h: Int,
        pitch: Double,
        roll: Double = 0.0,
    ): List<LineSegment> {
        val f = GeometryMath.DEFAULT_FOCAL * maxOf(w, h)
        val tilt = GeometryMath.cameraRotation(pitch, 0.0, roll, f).inverse()!!

        fun p(
            x: Double,
            y: Double,
        ): Pair<Float, Float> {
            val (px, py) = tilt.apply(x * f / 10, y * f / 10)!!
            return ((px + w / 2.0) / w).toFloat() to ((py + h / 2.0) / h).toFloat()
        }
        val verticals = (-2..2).map { i -> p(i * 1.0, -2.5) to p(i * 1.0, 2.5) }
        val horizontals = (-2..2).map { j -> p(-2.5, j * 1.0) to p(2.5, j * 1.0) }
        return (verticals + horizontals).map { (a, b) -> LineSegment(a.first, a.second, b.first, b.second) }
    }
}
