package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.io.masks.MaskRaster
import dev.mark.lineover.core.io.masks.MaskRasters
import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.model.EditGraphJson
import dev.mark.lineover.core.model.LayerMath
import dev.mark.lineover.core.model.LayerType
import dev.mark.lineover.core.model.MaskPaint
import dev.mark.lineover.core.model.Selection
import dev.mark.lineover.core.model.SelectionMode
import dev.mark.lineover.core.model.SelectionOp
import dev.mark.lineover.feature.editor.canvas.PickMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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

/** Stage 15 in the ViewModel: the Select tools on the canvas, modes, ops, deselect with Undo, selection to mask. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SelectionEditingTest {
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

    private val rasters = MemoryRasters()

    private fun viewModel() =
        EditorViewModel(
            1,
            FakeEditorLibrary(),
            InMemoryAppPreferences(),
            FakeCanvasFactory(canvas),
            renderDispatcher = dispatcher,
            saveScope = CoroutineScope(dispatcher),
            maskRasters = rasters,
        )

    private fun EditorViewModel.select(action: SelectAction) = onIntent(EditorIntent.Select(action))

    private fun EditorViewModel.drag(
        from: Pair<Float, Float>,
        to: Pair<Float, Float>,
    ) {
        select(SelectAction.Start(from.first, from.second))
        select(SelectAction.Move((from.first + to.first) / 2, (from.second + to.second) / 2))
        select(SelectAction.Move(to.first, to.second))
        select(SelectAction.End)
    }

    private fun EditorViewModel.tap(
        u: Float,
        v: Float,
    ) {
        select(SelectAction.Start(u, v))
        select(SelectAction.End)
    }

    private val EditorViewModel.ops get() =
        state.value.graph.selection
            ?.ops
            .orEmpty()

    @Test
    fun aMarqueeDragIsOneStepAndReachesTheEngineAlone() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SelectTool(SelectTools.MARQUEE))
            assertEquals(PickMode.Paint, vm.state.value.canvasPickMode)
            val graphs = canvas.graphs.size
            val steps = vm.state.value.history.entries.size
            vm.drag(0.125f to 0.125f, 0.5f to 0.625f)
            advanceUntilIdle()
            val rect = vm.ops.single() as SelectionOp.Rect
            assertEquals(SelectionOp.Rect(SelectionMode.New, true, 1020f, 765f, 4080f, 3825f), rect)
            assertEquals(steps + 1, vm.state.value.history.entries.size)
            assertEquals(
                "Rectangle selection",
                vm.state.value.history.entries
                    .last()
                    .label,
            )
            // The image is not re-rendered for a selection; the engine gets the recipe on its own.
            assertEquals(graphs, canvas.graphs.size)
            assertEquals(EditGraphJson.encodeSelection(vm.state.value.graph.selection), canvas.selections.last())
            // A fixed 1:1 ratio keeps the box square.
            vm.select(
                SelectAction.SetSettings(
                    vm.state.value.selectionSettings
                        .copy(ratio = 1f, mode = SelectionMode.Add),
                ),
            )
            vm.drag(0.6f to 0.6f, 0.9f to 0.7f)
            val square = vm.ops.last() as SelectionOp.Rect
            assertEquals(square.x1 - square.x0, square.y1 - square.y0, 0.01f)
            assertEquals(SelectionMode.Add, square.mode)
            assertEquals(2, vm.ops.size)
        }

    @Test
    fun theWandTapsAndItsToleranceSliderReRunsTheLastClick() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SelectTool(SelectTools.WAND))
            vm.tap(0.5f, 0.25f)
            assertEquals(SelectionOp.Wand(SelectionMode.New, 4080f, 1530f, 15f, true, true), vm.ops.single())
            val steps = vm.state.value.history.entries.size
            vm.select(SelectAction.SetTolerance(30f))
            vm.select(SelectAction.SetTolerance(42f))
            vm.onIntent(EditorIntent.EndEdit)
            assertEquals(42f, (vm.ops.single() as SelectionOp.Wand).tolerance)
            assertEquals(steps + 1, vm.state.value.history.entries.size) // one step for the slider drag
            advanceUntilIdle()
            assertTrue(canvas.selections.last().contains("\"tolerance\":42.0"))
        }

    @Test
    fun lassoAddsPolygonClosesAndOpsApply() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SelectTool(SelectTools.LASSO))
            vm.drag(0.1f to 0.1f, 0.3f to 0.4f)
            assertTrue((vm.ops.single() as SelectionOp.Lasso).points.size >= 6)
            vm.onIntent(EditorIntent.SelectTool(SelectTools.POLYGON))
            vm.select(
                SelectAction.SetSettings(
                    vm.state.value.selectionSettings
                        .copy(mode = SelectionMode.Add),
                ),
            )
            listOf(0.5f to 0.5f, 0.8f to 0.5f, 0.7f to 0.9f).forEach { (u, v) -> vm.tap(u, v) }
            assertTrue(vm.state.value.selectionSettings.polygonOpen)
            vm.tap(0.5005f, 0.5005f) // near the first corner: closes
            assertTrue(!vm.state.value.selectionSettings.polygonOpen)
            val polygon = vm.ops.last() as SelectionOp.Polygon
            assertEquals(6, polygon.points.size)
            vm.select(SelectAction.Apply(SelectionOp.Feather(10f)))
            vm.select(SelectAction.Apply(SelectionOp.Grow(-4f)))
            assertEquals(listOf<SelectionOp>(SelectionOp.Feather(10f), SelectionOp.Grow(-4f)), vm.ops.takeLast(2))
            assertEquals(
                "Shrink selection 4 px",
                vm.state.value.history.entries
                    .last()
                    .label,
            )
        }

    @Test
    fun tappingOutsideDeselectsWithUndo() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SelectTool(SelectTools.MARQUEE))
            vm.drag(0.1f to 0.1f, 0.4f to 0.4f)
            val effects = mutableListOf<EditorEffect>()
            val job = launch { vm.effects.toList(effects) }
            canvas.selectionValue = 1f
            vm.tap(0.2f, 0.2f) // inside: nothing happens
            advanceUntilIdle()
            assertTrue(vm.state.value.graph.selection != null)
            canvas.selectionValue = 0f
            vm.tap(0.9f, 0.9f)
            advanceUntilIdle()
            assertNull(vm.state.value.graph.selection)
            assertEquals(
                "Deselect",
                vm.state.value.history.entries
                    .last()
                    .label,
            )
            assertEquals("", canvas.selections.last())
            val undo = effects.filterIsInstance<EditorEffect.Undoable>().single()
            vm.onIntent(undo.undo)
            assertEquals(1, vm.ops.size)
            job.cancel()
        }

    @Test
    fun aSelectionBecomesAMaskOfItsOwnPixels() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SelectTool(SelectTools.COLOR_RANGE))
            vm.tap(0.25f, 0.25f)
            vm.select(
                SelectAction.SetSettings(
                    vm.state.value.selectionSettings
                        .copy(pickSubtract = true),
                ),
            )
            vm.tap(0.75f, 0.75f)
            val range = vm.ops.single() as SelectionOp.ColorRange
            assertEquals(listOf(2040f, 1530f), range.add)
            assertEquals(listOf(6120f, 4590f), range.subtract)
            advanceUntilIdle()
            vm.select(SelectAction.ToMask)
            advanceUntilIdle()
            val layer = LayerMath.flatten(vm.state.value.graph).single().first
            assertEquals(LayerType.Adjustment, layer.type)
            val raster = layer.mask!!.paint.single() as MaskPaint.Raster
            // Stage 16: the mask also names the selection's colour guide (for exports), saved beside it.
            assertEquals(MaskPaint.Raster(raster.source, 4f, 3f, 8160f, 6120f, raster.guide), raster)
            assertTrue(raster.guide != null && raster.guide in rasters.saved)
            assertTrue(raster.source in rasters.saved && raster.source in canvas.maskRasters)
            assertNull(vm.state.value.graph.selection) // the selection became the mask
            assertTrue(raster.source in vm.state.value.maskThumbnails)
        }

    @Test
    fun selectionsSurviveTheJsonOfTheEdit() {
        val selection =
            Selection(
                listOf(
                    SelectionOp.Ellipse(SelectionMode.Add, false, 1f, 2f, 3f, 4f),
                    SelectionOp.Wand(SelectionMode.Subtract, 5f, 6f, 20f, false, false),
                    SelectionOp.Invert,
                    SelectionOp.Border(3f),
                ),
            )
        val graph =
            dev.mark.lineover.core.model
                .EditGraph(selection = selection)
        assertEquals(graph, EditGraphJson.decode(EditGraphJson.encode(graph)))
        assertEquals(
            """{"ops":[{"kind":"ellipse","mode":"add","antiAlias":false,"x0":1.0,"y0":2.0,"x1":3.0,"y1":4.0},""" +
                """{"kind":"wand","mode":"subtract","x":5.0,"y":6.0,"tolerance":20.0,"contiguous":false,"allLayers":false},""" +
                """{"kind":"invert"},{"kind":"border","px":3.0}]}""",
            EditGraphJson.encodeSelection(selection),
        )
    }
}
