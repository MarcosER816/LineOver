package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.io.masks.MaskRaster
import dev.mark.lineover.core.io.masks.MaskRasters
import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.ml.ModelError
import dev.mark.lineover.core.ml.ModelImage
import dev.mark.lineover.core.ml.ModelInfo
import dev.mark.lineover.core.ml.ModelMask
import dev.mark.lineover.core.ml.ModelRun
import dev.mark.lineover.core.ml.ModelState
import dev.mark.lineover.core.ml.SegmentPrompt
import dev.mark.lineover.core.ml.SmartModels
import dev.mark.lineover.core.ml.SmartTool
import dev.mark.lineover.core.model.LayerMath
import dev.mark.lineover.core.model.MaskPaint
import dev.mark.lineover.core.model.SelectionMode
import dev.mark.lineover.core.model.SelectionOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 16 in the ViewModel: the first use of a model shows its sheet, downloads, then selects;
 * offline says so; Object select refines one op per tap; quick selection, the magnetic lasso,
 * the add / subtract brushes and refine edge build the recipe; a mask from a selection saves its
 * colour guide.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmartSelectionTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val canvas = FakeEditorCanvas()

    private class MemoryRasters : MaskRasters {
        val saved = mutableMapOf<String, MaskRaster>()

        override fun save(raster: MaskRaster): String = "0123456789abcdef0123456789abcd${saved.size}0.lomask".also { saved[it] = raster }

        override fun load(name: String): MaskRaster? = saved[name]
    }

    /** Models that answer at once: [prepareResult] for a download, a 2 x 2 mask for a run. */
    private class FakeModels : SmartModels {
        override val states = MutableStateFlow(SmartTool.entries.associateWith<SmartTool, ModelState> { ModelState.Missing })
        override val lastRun = MutableStateFlow<ModelRun?>(null)
        var prepareResult: ModelState = ModelState.Ready
        val prepared = mutableListOf<Pair<SmartTool, Boolean>>()
        val runs = mutableListOf<Pair<SmartTool, List<SegmentPrompt>>>()
        val deleted = mutableListOf<SmartTool>()

        override fun info(tool: SmartTool) =
            ModelInfo(tool, "Model for ${tool.label}", 2_348_952L, "Apache License 2.0", "https://example.org/licence", "example.org")

        override suspend fun prepare(
            tool: SmartTool,
            allowMetered: Boolean,
        ): ModelState {
            prepared += tool to allowMetered
            states.value = states.value + (tool to prepareResult)
            return prepareResult
        }

        override suspend fun segment(
            tool: SmartTool,
            image: ModelImage,
            prompts: List<SegmentPrompt>,
        ): ModelMask {
            runs += tool to prompts
            lastRun.value = ModelRun(tool, null, 12)
            return ModelMask(2, 2, floatArrayOf(1f, 0f, 1f, 0.2f))
        }

        override suspend fun delete(tool: SmartTool) {
            deleted += tool
            states.value = states.value + (tool to ModelState.Missing)
        }
    }

    private val rasters = MemoryRasters()
    private val models = FakeModels()

    private fun viewModel() =
        EditorViewModel(
            1,
            FakeEditorLibrary(),
            InMemoryAppPreferences(),
            FakeCanvasFactory(canvas),
            renderDispatcher = dispatcher,
            saveScope = CoroutineScope(dispatcher),
            maskRasters = rasters,
            smartModels = models,
        )

    private fun EditorViewModel.select(action: SelectAction) = onIntent(EditorIntent.Select(action))

    private val EditorViewModel.ops get() =
        state.value.graph.selection
            ?.ops
            .orEmpty()

    private fun EditorViewModel.drag(vararg points: Pair<Float, Float>) {
        select(SelectAction.Start(points[0].first, points[0].second))
        points.drop(1).forEach { (u, v) -> select(SelectAction.Move(u, v)) }
        select(SelectAction.End)
    }

    @Test
    fun firstUseShowsTheSheetThenDownloadsAndSelects() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.select(SmartAction.Run(SmartTool.Subject))
            advanceUntilIdle()
            val sheet = vm.state.value.smart.sheet!!
            assertEquals(SmartTool.Subject, sheet.tool)
            assertEquals("Model for Select subject", sheet.info.name)
            assertEquals(2_348_952L, sheet.info.sizeBytes)
            assertEquals("Apache License 2.0", sheet.info.license)
            assertTrue(sheet.wifiOnly)
            assertNull(sheet.statusText()) // only the question, before Download
            assertTrue(vm.ops.isEmpty() && models.runs.isEmpty())
            // Download: the model arrives, the sheet closes and the tool runs by itself.
            vm.select(SmartAction.Download())
            advanceUntilIdle()
            assertEquals(listOf(SmartTool.Subject to false), models.prepared)
            assertNull(vm.state.value.smart.sheet)
            val op = vm.ops.single() as SelectionOp.Raster
            assertEquals(SelectionMode.New, op.mode)
            assertEquals(SelectionOp.Raster.TOOL_SUBJECT, op.tool)
            assertEquals(2f, op.width)
            assertEquals(
                "Select subject",
                vm.state.value.history.entries
                    .last()
                    .label,
            )
            // The mask was saved and registered before the recipe named it; the shimmer is gone.
            assertTrue(op.source in rasters.saved && op.source in canvas.maskRasters)
            assertNull(vm.state.value.smart.busy)
            assertTrue(
                vm.state.value.smart.lastRun!!
                    .startsWith("Select subject: "),
            )
            // A second use runs at once.
            vm.select(SmartAction.Run(SmartTool.Sky))
            advanceUntilIdle()
            assertNotNull(vm.state.value.smart.sheet)
            vm.select(SmartAction.DismissSheet)
            vm.select(SmartAction.Run(SmartTool.Subject))
            advanceUntilIdle()
            assertNull(vm.state.value.smart.sheet)
            assertEquals(2, models.runs.count { it.first == SmartTool.Subject })
        }

    @Test
    fun offlineFirstUseSaysSoAndSelectsNothing() =
        runTest(dispatcher) {
            models.prepareResult = ModelState.Failed(ModelError.Offline)
            val vm = viewModel()
            advanceUntilIdle()
            vm.select(SmartAction.Run(SmartTool.Sky))
            vm.select(SmartAction.Download())
            advanceUntilIdle()
            val sheet = vm.state.value.smart.sheet!!
            assertEquals(
                "You're offline. Connect to the internet to download Model for Select sky (2.3 MB); it's needed once.",
                sheet.statusText(),
            )
            assertTrue(vm.ops.isEmpty() && models.runs.isEmpty())
            // On mobile data with Wi-Fi only on, the sheet offers to download anyway.
            models.prepareResult = ModelState.Failed(ModelError.NeedsWifi)
            vm.select(SmartAction.Download())
            advanceUntilIdle()
            assertTrue(
                vm.state.value.smart.sheet!!
                    .needsWifi,
            )
            models.prepareResult = ModelState.Ready
            vm.select(SmartAction.Download(allowMetered = true))
            advanceUntilIdle()
            assertEquals(SmartTool.Sky to true, models.prepared.last())
            assertEquals(SelectionOp.Raster.TOOL_SKY, (vm.ops.single() as SelectionOp.Raster).tool)
        }

    @Test
    fun objectSelectRefinesOneOpPerTapAndScribbles() =
        runTest(dispatcher) {
            models.states.value = SmartTool.entries.associateWith { ModelState.Ready }
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SelectTool(SmartTools.OBJECT))
            vm.drag(0.5f to 0.5f)
            advanceUntilIdle()
            assertEquals(
                listOf(listOf(0.5f to 0.5f)),
                models.runs
                    .single()
                    .second
                    .map { it.points },
            )
            val first = vm.ops.single() as SelectionOp.Raster
            assertEquals(SelectionOp.Raster.TOOL_OBJECT, first.tool)
            // A scribble to remove: the same object, refined (one op), with both prompts.
            vm.select(
                SmartAction.Configure(
                    vm.state.value.smart
                        .copy(objectSubtract = true),
                ),
            )
            vm.drag(0.25f to 0.25f, 0.3f to 0.3f, 0.35f to 0.32f)
            advanceUntilIdle()
            val prompts = models.runs.last().second
            assertEquals(listOf(true, false), prompts.map { it.positive })
            assertEquals(3, prompts.last().points.size)
            assertEquals(1, vm.ops.size)
            assertTrue(vm.ops.single() != first)
        }

    @Test
    fun quickSelectionMagneticLassoBrushesAndRefineBuildTheRecipe() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SelectTool(SmartTools.QUICK))
            vm.drag(0.25f to 0.25f, 0.375f to 0.25f, 0.5f to 0.25f)
            advanceUntilIdle()
            val quick = vm.ops.single() as SelectionOp.Quick
            assertEquals(SelectionMode.New, quick.mode)
            assertEquals(listOf(2040f, 1530f, 3060f, 1530f, 4080f, 1530f), quick.points)
            // The next stroke adds.
            vm.drag(0.625f to 0.625f, 0.75f to 0.625f)
            assertEquals(SelectionMode.Add, (vm.ops.last() as SelectionOp.Quick).mode)
            // The magnetic lasso follows the live-wire (the fake's path bends through a midpoint).
            vm.onIntent(EditorIntent.SelectTool(SmartTools.MAGNETIC))
            vm.select(
                SelectAction.SetSettings(
                    vm.state.value.selectionSettings
                        .copy(mode = SelectionMode.Add),
                ),
            )
            vm.drag(0.125f to 0.125f, 0.25f to 0.125f)
            advanceUntilIdle()
            val wire = vm.ops.last() as SelectionOp.Magnetic
            assertEquals(listOf(1020f, 765f, 1530f, 775f, 2040f, 765f), wire.points)
            assertEquals(
                "Magnetic lasso",
                vm.state.value.history.entries
                    .last()
                    .label,
            )
            // Subject select's panel paints with the subtract brush.
            vm.onIntent(EditorIntent.SelectTool(SmartTools.SUBJECT))
            vm.select(
                SmartAction.Configure(
                    vm.state.value.smart
                        .copy(brushSubtract = true, brushRadius = 50f),
                ),
            )
            vm.drag(0.5f to 0.5f, 0.5625f to 0.5f)
            val brush = vm.ops.last() as SelectionOp.Brush
            assertEquals(SelectionMode.Subtract, brush.mode)
            assertEquals(50f, brush.radius)
            assertEquals(
                "Subtract brush",
                vm.state.value.history.entries
                    .last()
                    .label,
            )
            // Refine edge: Apply adds one, the sliders change it live (one op).
            vm.onIntent(EditorIntent.SelectTool(SmartTools.REFINE))
            vm.select(SmartAction.ApplyRefine)
            vm.select(SmartAction.SetRefine(12f, 35f))
            assertEquals(SelectionOp.Refine(12f, 35f), vm.ops.last())
            assertEquals(1, vm.ops.count { it is SelectionOp.Refine })
            assertEquals(
                "Refine edge",
                vm.state.value.history.entries
                    .last()
                    .label,
            )
        }

    @Test
    fun aMaskFromASelectionSavesItsColourGuide() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SelectTool(SelectTools.MARQUEE))
            vm.drag(0.125f to 0.125f, 0.5f to 0.5f)
            advanceUntilIdle()
            vm.select(SelectAction.ToMask)
            advanceUntilIdle()
            val layer = LayerMath.flatten(vm.state.value.graph).map { it.first }.single { it.mask != null }
            val paint = layer.mask!!.paint.single() as MaskPaint.Raster
            val guide = rasters.saved[paint.guide!!]!!
            assertEquals(4, guide.width)
            assertEquals(9, guide.height) // 3 planes of the 4 x 3 selection
            assertTrue(paint.guide in canvas.maskRasters)
            assertEquals(setOf(paint.source, paint.guide), rasterNames(vm.state.value.graph))
        }
}
