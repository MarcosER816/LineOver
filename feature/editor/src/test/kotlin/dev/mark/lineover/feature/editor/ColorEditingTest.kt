package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.model.HslBand
import dev.mark.lineover.core.model.HslChannel
import dev.mark.lineover.core.model.LightParam
import dev.mark.lineover.core.model.OpParams
import dev.mark.lineover.feature.editor.canvas.PickMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Stage 6 in the ViewModel: Color controls → graph, eyedropper, targeted adjustment. */
@OptIn(ExperimentalCoroutinesApi::class)
class ColorEditingTest {
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
    fun whiteBalanceRunsFirstAndTheRestAfterLight() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.SetVibrance(30f))
        vm.onIntent(EditorIntent.SetLight(LightParam.Exposure, 1f))
        vm.onIntent(EditorIntent.SetWhiteBalance(3800f, -10f))
        vm.onIntent(EditorIntent.SetSaturation(-20f))
        assertEquals(
            listOf(OpParams.WhiteBalance(3800f, -10f), OpParams.Exposure(1f), OpParams.Vibrance(30f), OpParams.Saturation(-20f)),
            ops(),
        )
        vm.onIntent(EditorIntent.AsShot)
        assertEquals(listOf(OpParams.Exposure(1f), OpParams.Vibrance(30f), OpParams.Saturation(-20f)), ops())
    }

    @Test
    fun hslSlidersChangeTheirBandAndChannel() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.SelectHslChannel(HslChannel.Saturation))
        vm.onIntent(EditorIntent.SetHsl(vm.state.value.hslChannel, HslBand.Orange, 50f))
        val mixer = ops().single() as OpParams.HslMixer
        assertEquals(50f, mixer.saturation[1])
        assertEquals(List(8) { 0f }, mixer.hue)
    }

    @Test
    fun eyedropperShowsTheLoupeThenNeutralisesAndPutsItselfAway() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.SelectTool("color.white_balance"))
        vm.onIntent(EditorIntent.ToggleEyedropper)
        assertEquals(PickMode.Eyedropper, vm.state.value.pickMode)
        vm.gestures.onEyedropperMove(200f, 400f, 0.4f, 0.6f)
        val loupe = requireNotNull(vm.state.value.loupe)
        assertEquals(200f, loupe.viewX)
        assertNotNull(loupe.pixels)
        vm.gestures.onEyedropperEnd(0.4f, 0.6f)
        assertNull(vm.state.value.loupe)
        assertEquals(PickMode.None, vm.state.value.pickMode)
        assertEquals(listOf(OpParams.WhiteBalance(4500f, 12f)), ops())
        assertEquals(0.4f to 0.6f, canvas.samples.last())
    }

    @Test
    fun eyedropperCancelOrOutsideTheImageChangesNothing() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.ToggleEyedropper)
        vm.gestures.onEyedropperEnd(null, null)
        vm.gestures.onEyedropperEnd(1.4f, 0.5f)
        assertTrue(
            canvas.graphs
                .last()
                .layers
                .isEmpty() || ops().isEmpty(),
        )
        assertEquals(PickMode.Eyedropper, vm.state.value.pickMode)
    }

    @Test
    fun targetedDragMovesTheBandsUnderTheFinger() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.SelectHslChannel(HslChannel.Saturation))
        vm.onIntent(EditorIntent.ToggleTargeted)
        vm.gestures.onTargetedStart(0.5f, 0.5f)
        // Up a tenth of the canvas: +20 split 75 / 25 between orange and yellow.
        vm.gestures.onTargetedDrag(0.1f)
        val mixer = ops().single() as OpParams.HslMixer
        assertEquals(15f, mixer.saturation[1], 1e-4f)
        assertEquals(5f, mixer.saturation[2], 1e-4f)
        val hud = requireNotNull(vm.state.value.targeted)
        assertEquals(HslBand.Orange, hud.band)
        assertEquals(15f, hud.value, 1e-4f)
        vm.gestures.onTargetedEnd()
        assertNull(vm.state.value.targeted)
    }

    @Test
    fun greyUnderTheFingerAdjustsNothing() {
        canvas.weights = FloatArray(8)
        val vm = viewModel()
        vm.onIntent(EditorIntent.ToggleTargeted)
        vm.gestures.onTargetedStart(0.5f, 0.5f)
        vm.gestures.onTargetedDrag(0.3f)
        assertTrue(
            canvas.graphs
                .last()
                .layers
                .isEmpty() || ops().isEmpty(),
        )
        assertNull(vm.state.value.targeted)
    }

    @Test
    fun invertPosterizeThresholdComeLast() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.SetThreshold(0.4f))
        vm.onIntent(EditorIntent.SetPosterize(4))
        vm.onIntent(EditorIntent.SetInvert(true))
        vm.onIntent(EditorIntent.SetLight(LightParam.Contrast, 10f))
        assertEquals(
            listOf(OpParams.Contrast(10f), OpParams.Invert, OpParams.Posterize(4f), OpParams.Threshold(0.4f)),
            ops(),
        )
        vm.onIntent(EditorIntent.SetThreshold(null))
        vm.onIntent(EditorIntent.SetPosterize(0))
        assertEquals(listOf(OpParams.Contrast(10f), OpParams.Invert), ops())
    }

    @Test
    fun closingThePanelDisarmsThePicker() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.SelectTool("color.hsl_color_mixer"))
        vm.onIntent(EditorIntent.ToggleTargeted)
        vm.onIntent(EditorIntent.ClosePanel)
        assertEquals(PickMode.None, vm.state.value.pickMode)
    }
}
