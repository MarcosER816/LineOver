package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.io.looks.InMemoryLooksStore
import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.model.ColorAdjustments
import dev.mark.lineover.core.model.LightParam
import dev.mark.lineover.core.model.OpParams
import dev.mark.lineover.core.model.SettingsGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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

/** Stage 11 in the ViewModel: presets with Amount, compare, profiles, LUTs, copy / paste, Auto. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LooksEditingTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val canvas = FakeEditorCanvas()
    private val store = InMemoryLooksStore()

    private fun viewModel() =
        EditorViewModel(
            1,
            FakeEditorLibrary(),
            InMemoryAppPreferences(),
            FakeCanvasFactory(canvas),
            renderDispatcher = dispatcher,
            saveScope = CoroutineScope(dispatcher),
            looks = store,
        )

    private fun EditorViewModel.looks(action: LooksAction) = onIntent(EditorIntent.Looks(action))

    private fun ops() =
        canvas.graphs
            .last()
            .layers
            .firstOrNull()
            ?.ops
            .orEmpty()

    private val cube =
        ("TITLE \"Swap\"\nLUT_3D_SIZE 2\n" + (0 until 8).joinToString("") { i -> "${(i shr 2) and 1} ${(i shr 1) and 1} ${i and 1}\n" })
            .toByteArray()

    @Test
    fun aPresetAppliesAndItsAmountScrubsFromThePhoto() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SetLight(LightParam.Exposure, 0.5f))
            vm.onIntent(EditorIntent.EndEdit)
            vm.onIntent(EditorIntent.SelectTool(PRESETS_TOOL))
            vm.looks(LooksAction.Apply(LookTarget.Preset("builtin.bright_airy")))
            advanceUntilIdle()
            assertEquals(0.4f, vm.state.value.light[LightParam.Exposure], 1e-5f)
            assertEquals(
                "Preset: Bright & airy",
                vm.state.value.history.entries
                    .last()
                    .label,
            )
            vm.looks(LooksAction.PresetAmount(0.5f))
            assertEquals(0.45f, vm.state.value.light[LightParam.Exposure], 1e-5f)
            vm.looks(LooksAction.PresetAmount(2f))
            assertEquals(0.3f, vm.state.value.light[LightParam.Exposure], 1e-5f)
            vm.onIntent(EditorIntent.EndEdit)
            // Amount is one step with the preset.
            assertEquals(
                "Preset: Bright & airy",
                vm.state.value.history.entries
                    .last()
                    .label,
            )
            vm.onIntent(EditorIntent.Undo)
            assertEquals(0.5f, vm.state.value.light[LightParam.Exposure], 1e-5f)
            assertNull(vm.state.value.appliedPreset)
        }

    @Test
    fun holdingAThumbnailShowsItsLookOnTheCanvasOnly() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SelectTool(PROFILES_TOOL))
            vm.looks(LooksAction.Compare(LookTarget.Profile("mono")))
            advanceUntilIdle()
            assertEquals(OpParams.Profile("mono", 100f), ops().single { it is OpParams.Profile })
            assertTrue(
                vm.state.value.backgroundOps
                    .none { it is OpParams.Profile },
            )
            vm.looks(LooksAction.Compare(null))
            advanceUntilIdle()
            assertTrue(ops().none { it is OpParams.Profile })
        }

    @Test
    fun profilesAndTheirAmount() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.looks(LooksAction.Apply(LookTarget.Profile("vivid")))
            vm.looks(LooksAction.ProfileAmount(40f))
            advanceUntilIdle()
            assertEquals(OpParams.Profile("vivid", 40f), ops().single { it is OpParams.Profile })
            vm.looks(LooksAction.Apply(LookTarget.Profile("neutral")))
            advanceUntilIdle()
            assertTrue(ops().none { it is OpParams.Profile })
        }

    @Test
    fun anImportedLutIsRegisteredBeforeTheGraphNamesIt() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.looks(LooksAction.ImportLut("swap.cube", cube))
            advanceUntilIdle()
            val entry =
                store.state.value.luts
                    .single()
            assertEquals("swap", entry.name)
            assertTrue(entry.id in canvas.registeredLuts)
            assertEquals(OpParams.Lut(entry.id, "swap", 100f), ops().single { it is OpParams.Lut })
            vm.looks(LooksAction.LutAmount(30f))
            advanceUntilIdle()
            assertEquals(30f, (ops().single { it is OpParams.Lut } as OpParams.Lut).amount)
            vm.looks(LooksAction.DeleteLut(entry.id))
            advanceUntilIdle()
            assertTrue(ops().none { it is OpParams.Lut })
            assertTrue(
                store.state.value.luts
                    .isEmpty(),
            )
        }

    @Test
    fun aBrokenCubeIsReportedAndChangesNothing() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            val graph = vm.state.value.graph
            vm.looks(LooksAction.ImportLut("bad.cube", "LUT_3D_SIZE 2\n0 0 0\n".toByteArray()))
            advanceUntilIdle()
            assertEquals(graph, vm.state.value.graph)
            assertTrue(
                store.state.value.luts
                    .isEmpty(),
            )
        }

    @Test
    fun copyAndPasteTheCheckedGroups() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SetLight(LightParam.Contrast, 30f))
            vm.onIntent(EditorIntent.SetVibrance(25f))
            vm.onIntent(EditorIntent.EndEdit)
            vm.looks(LooksAction.ToggleGroup(SettingsGroup.Color)) // copy Light only
            vm.looks(LooksAction.Copy)
            assertEquals(
                listOf(OpParams.Contrast(30f)),
                store.state.value.copied
                    ?.ops,
            )
            // Another photo: paste replaces its Light.
            val other = viewModel()
            advanceUntilIdle()
            other.onIntent(EditorIntent.SetLight(LightParam.Exposure, 1f))
            other.onIntent(EditorIntent.EndEdit)
            other.looks(LooksAction.Paste)
            advanceUntilIdle()
            assertEquals(30f, other.state.value.light[LightParam.Contrast])
            assertEquals(0f, other.state.value.light[LightParam.Exposure])
            assertEquals(
                "Paste settings",
                other.state.value.history.entries
                    .last()
                    .label,
            )
        }

    @Test
    fun autoAppliesTheEnginesProposalAsOneStep() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.looks(LooksAction.Auto)
            advanceUntilIdle()
            val s = vm.state.value
            assertEquals(0.6f, s.light[LightParam.Exposure])
            assertEquals(15f, s.light[LightParam.Contrast])
            assertEquals(5600f, s.color.temperature)
            assertEquals(18f, s.color.vibrance)
            assertEquals(
                "Auto",
                s.history.entries
                    .last()
                    .label,
            )
            assertTrue(ops().any { it is OpParams.WhiteBalance })
            assertTrue(ColorAdjustments.DEFAULT_KELVIN != s.color.temperature)
        }

    @Test
    fun saveCurrentAsPresetAndFavourite() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SetLight(LightParam.Contrast, 20f))
            vm.onIntent(EditorIntent.EndEdit)
            vm.onIntent(EditorIntent.OpenNameDialog(NameDialog.NewPreset("x")))
            vm.onIntent(EditorIntent.ConfirmName("Evening"))
            advanceUntilIdle()
            val saved = store.userPresets.value.single()
            assertEquals("Evening", saved.name)
            assertEquals(listOf(OpParams.Contrast(20f)), saved.ops)
            assertTrue(
                vm.state.value.allPresets
                    .any { it.id == saved.id },
            )
            vm.looks(LooksAction.ToggleFavorite(saved.id))
            advanceUntilIdle()
            assertEquals(listOf(saved.id), vm.state.value.looksLibrary.favoritePresets)
        }

    @Test
    fun theStripRendersThumbnailsFromTheSmallProxy() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SelectTool(PROFILES_TOOL))
            advanceUntilIdle()
            assertEquals(setOf("profile:neutral", "profile:vivid", "profile:mono", "profile:flat"), vm.state.value.lookThumbnails.keys)
        }
}
