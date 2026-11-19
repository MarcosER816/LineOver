package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.model.DetailParam
import dev.mark.lineover.core.model.DetailTools
import dev.mark.lineover.core.model.LightParam
import dev.mark.lineover.core.model.OpParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Stage 10 in the ViewModel: Detail / Effects sliders → graph, the Masking preview, 1:1, History. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailEditingTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val canvas = FakeEditorCanvas()

    private fun viewModel() =
        EditorViewModel(1, FakeEditorLibrary(), InMemoryAppPreferences(), FakeCanvasFactory(canvas), renderDispatcher = Dispatchers.Main)

    private fun ops() =
        canvas.graphs
            .last()
            .layers
            .first()
            .ops

    @Test
    fun slidersReachTheEngineInOrder() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.SetDetail(DetailParam.SharpenAmount, 50f))
        vm.onIntent(EditorIntent.SetLight(LightParam.Exposure, 0.5f))
        vm.onIntent(EditorIntent.SetDetail(DetailParam.Dehaze, 30f))
        vm.onIntent(EditorIntent.SetDetail(DetailParam.GrainAmount, 20f))
        vm.onIntent(EditorIntent.SetDetail(DetailParam.NoiseLuminance, 40f))
        assertEquals(listOf("NoiseReduction", "Dehaze", "Exposure", "Sharpen", "Grain"), ops().map { it::class.simpleName })
        assertEquals(OpParams.Sharpen(50f), ops()[3])
    }

    @Test
    fun aDragIsOneHistoryStep() {
        val vm = viewModel()
        val before = vm.state.value.history.entries.size
        for (v in listOf(10f, 20f, 30f)) vm.onIntent(EditorIntent.SetDetail(DetailParam.Clarity, v))
        vm.onIntent(EditorIntent.EndEdit)
        val history = vm.state.value.history
        assertEquals(before + 1, history.entries.size)
        assertEquals("Clarity +30", history.entries.last().label)
        vm.onIntent(EditorIntent.Undo)
        assertEquals(0f, vm.state.value.detail[DetailParam.Clarity])
    }

    @Test
    fun theMaskingFingerShowsTheMaskOnTheCanvasOnly() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.SelectTool(DetailTools.SHARPEN))
        vm.onIntent(EditorIntent.SetDetail(DetailParam.SharpenAmount, 60f))
        vm.onIntent(EditorIntent.SetDetail(DetailParam.SharpenMasking, 40f))
        vm.onIntent(EditorIntent.EndEdit)
        vm.onIntent(EditorIntent.SharpenMaskPreview(true))
        assertEquals(1f, (ops().last() as OpParams.Sharpen).showMask)
        // The edit itself (saved, in History) never carries the mask.
        val edit =
            vm.state.value.graph.layers
                .first()
                .ops
        assertEquals(0f, (edit.last() as OpParams.Sharpen).showMask)
        vm.onIntent(EditorIntent.SharpenMaskPreview(false))
        assertEquals(0f, (ops().last() as OpParams.Sharpen).showMask)
    }

    @Test
    fun theOneToOneButtonAsksTheCanvas() {
        val vm = viewModel()
        val before = vm.state.value.oneToOneRequest
        vm.onIntent(EditorIntent.ToggleOneToOne)
        vm.onIntent(EditorIntent.ToggleOneToOne)
        assertEquals(before + 2, vm.state.value.oneToOneRequest)
    }

    @Test
    fun theDetailToolsAreBuilt() {
        assertTrue(DetailTools.ALL.all { it in DETAIL_TOOLS })
    }
}
