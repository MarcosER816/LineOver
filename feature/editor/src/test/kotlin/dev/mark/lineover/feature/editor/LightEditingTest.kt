package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.engine.CompareMode
import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.model.LightParam
import dev.mark.lineover.core.model.OpParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Stage 5 in the ViewModel: Light sliders → graph → engine, Auto, clipping, compare, swipe mode. */
@OptIn(ExperimentalCoroutinesApi::class)
class LightEditingTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val canvas = FakeEditorCanvas()

    private fun viewModel(debuggable: Boolean = false) =
        EditorViewModel(
            1,
            FakeEditorLibrary(),
            InMemoryAppPreferences(),
            FakeCanvasFactory(canvas),
            renderDispatcher = Dispatchers.Main,
            debuggable = debuggable,
        )

    private fun backgroundOps() =
        canvas.graphs
            .last()
            .layers
            .first()
            .ops

    @Test
    fun slidersReachTheEngineInTheFixedOpOrder() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.SetLight(LightParam.Brightness, 20f))
        vm.onIntent(EditorIntent.SetLight(LightParam.Exposure, 1.2f))
        vm.onIntent(EditorIntent.SetLight(LightParam.Shadows, 50f))
        assertEquals(
            listOf(OpParams.HighlightsShadows(0f, 50f), OpParams.Exposure(1.2f), OpParams.Brightness(20f)),
            backgroundOps(),
        )
        assertEquals(1.2f, vm.state.value.light[LightParam.Exposure])
        // Back to 0 (double tap reset) removes the op.
        vm.onIntent(EditorIntent.SetLight(LightParam.Brightness, 0f))
        assertEquals(listOf(OpParams.HighlightsShadows(0f, 50f), OpParams.Exposure(1.2f)), backgroundOps())
    }

    @Test
    fun selectingALightToolPicksItsSlider() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.SelectTool("light.whites"))
        assertEquals(LightParam.Whites, vm.state.value.lightParam)
        assertEquals(EditorPanel.Tool("light.whites"), vm.state.value.panel)
    }

    @Test
    fun autoSetsExposureAndBlacksFromTheEngine() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.AutoLight)
        assertEquals(1.25f, vm.state.value.light[LightParam.Exposure])
        assertEquals(12f, vm.state.value.light[LightParam.Blacks])
        assertEquals(listOf(OpParams.Exposure(1.25f), OpParams.Blacks(12f)), backgroundOps())
    }

    @Test
    fun clippingShowsForTheOverlayOrASecondFinger() {
        val vm = viewModel()
        assertFalse(canvas.clipping)
        vm.onIntent(EditorIntent.ClippingPreview(true))
        assertTrue(canvas.clipping)
        vm.onIntent(EditorIntent.ClippingPreview(false))
        assertFalse(canvas.clipping)
        vm.onIntent(EditorIntent.ToggleOverlay(EditorState.CLIPPING_TOOL))
        assertTrue(canvas.clipping)
        vm.onIntent(EditorIntent.ClippingPreview(true))
        vm.onIntent(EditorIntent.ClippingPreview(false))
        assertTrue("the toggle keeps it on", canvas.clipping)
    }

    @Test
    fun theHistogramRunsOnlyWhileShown() {
        val vm = viewModel()
        assertFalse(canvas.histogramEnabled)
        vm.onIntent(EditorIntent.ToggleOverlay(EditorState.HISTOGRAM_TOOL))
        assertTrue(canvas.histogramEnabled)
        vm.onIntent(EditorIntent.ToggleOverlay(EditorState.HISTOGRAM_TOOL))
        assertFalse(canvas.histogramEnabled)
    }

    @Test
    fun compareCyclesSplitSideBySideOff() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.CycleCompare)
        assertEquals(CompareMode.Split to 0.5f, canvas.compare)
        vm.onIntent(EditorIntent.SetSplit(0.3f))
        assertEquals(CompareMode.Split to 0.3f, canvas.compare)
        vm.onIntent(EditorIntent.CycleCompare)
        assertEquals(CompareMode.SideBySide, canvas.compare.first)
        vm.onIntent(EditorIntent.CycleCompare)
        assertEquals(CompareMode.Off, canvas.compare.first)
    }

    @Test
    fun holdingTheCanvasShowsTheBeforeLabel() {
        val vm = viewModel()
        vm.gestures.onHoldChanged(true)
        assertTrue(vm.state.value.showingOriginal)
        vm.gestures.onHoldChanged(false)
        assertFalse(vm.state.value.showingOriginal)
    }

    @Test
    fun swipeModePicksAndChangesTheParameter() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.SelectTool("light.exposure"))
        vm.onIntent(EditorIntent.ToggleSwipeMode)
        vm.gestures.onSwipeActive(true)
        assertTrue(vm.state.value.swiping)
        // Down two parameters: Exposure -> Contrast -> Highlights; the open panel follows.
        vm.gestures.onSwipeSelect(2)
        assertEquals(LightParam.Highlights, vm.state.value.lightParam)
        assertEquals(EditorPanel.Tool("light.highlights"), vm.state.value.panel)
        // A tenth of the width = a tenth of the range (200): +20.
        vm.gestures.onSwipeAdjust(0.1f)
        assertEquals(20f, vm.state.value.light[LightParam.Highlights], 1e-3f)
        assertEquals(listOf(OpParams.HighlightsShadows(20f, 0f)), backgroundOps())
        // Up past the first wraps around to the last.
        vm.gestures.onSwipeSelect(-3)
        assertEquals(LightParam.Brightness, vm.state.value.lightParam)
        vm.gestures.onSwipeActive(false)
        assertFalse(vm.state.value.swiping)
    }

    @Test
    fun renderStatsAreForDebugBuildsOnly() {
        val release = viewModel()
        release.onIntent(EditorIntent.ToggleRenderStats)
        assertFalse(release.state.value.renderStats)
        val debug = viewModel(debuggable = true)
        debug.onIntent(EditorIntent.ToggleRenderStats)
        assertTrue(debug.state.value.renderStats)
    }
}
