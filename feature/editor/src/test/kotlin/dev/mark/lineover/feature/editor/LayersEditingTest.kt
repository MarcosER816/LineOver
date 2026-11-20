package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.io.layers.LayerSource
import dev.mark.lineover.core.io.layers.LayerSources
import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.model.AdjustmentKind
import dev.mark.lineover.core.model.BlendMode
import dev.mark.lineover.core.model.BlendSpace
import dev.mark.lineover.core.model.LayerMask
import dev.mark.lineover.core.model.LayerMath
import dev.mark.lineover.core.model.LayerPlacement
import dev.mark.lineover.core.model.LayerType
import dev.mark.lineover.core.model.LightParam
import dev.mark.lineover.core.model.MaskPaint
import dev.mark.lineover.core.model.OpParams
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Stage 13 in the ViewModel: import a photo layer, blend preview and commit, opacity drags, delete with Undo, merge and flatten. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LayersEditingTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule val folder = TemporaryFolder()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val canvas = FakeEditorCanvas()

    /** Imports by "copying" a file named after the URI. */
    private inner class FakeSources : LayerSources {
        override fun import(uri: String): LayerSource? {
            if (uri.contains("broken")) return null
            val name = "0123456789abcdef0123456789abcdef.jpg"
            File(folder.root, name).writeText("pixels")
            return LayerSource(name, 4000, 3000)
        }

        override fun file(name: String): File? = File(folder.root, name).takeIf { it.isFile }
    }

    private fun viewModel() =
        EditorViewModel(
            1,
            FakeEditorLibrary(),
            InMemoryAppPreferences(),
            FakeCanvasFactory(canvas),
            renderDispatcher = dispatcher,
            saveScope = CoroutineScope(dispatcher),
            layers = FakeSources(),
        )

    private fun EditorViewModel.layers(action: LayerAction) = onIntent(EditorIntent.Layers(action))

    private val EditorViewModel.graph get() = state.value.graph

    private val EditorViewModel.lastLabel get() =
        state.value.history.entries
            .last()
            .label

    @Test
    fun aSecondPhotoBecomesAFittedLayerRegisteredBeforeTheEngineSeesIt() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.layers(LayerAction.Import("content://media/picker/1", smart = false))
            advanceUntilIdle()
            val layer = LayerMath.flatten(vm.graph).single().first
            assertEquals(LayerType.Raster, layer.type)
            assertEquals("Photo 2", layer.name)
            assertEquals(listOf(4000f, 3000f), layer.sourceSize)
            // Fitted into the 8160 x 6120 photo: 2.04 x, centred.
            val p = LayerPlacement.of(layer.transform, 4000f, 3000f)
            assertEquals(2.04f, p.scale, 1e-4f)
            assertEquals(4080f, p.centerX, 1e-2f)
            assertEquals(layer.id, vm.state.value.selectedLayerId)
            assertEquals("Add photo layer", vm.lastLabel)
            assertTrue(layer.source in canvas.registeredLayers)
            assertEquals(layer.source, LayerMath.sources(canvas.graphs.last()).single())
        }

    @Test
    fun anUnreadablePhotoIsRefused() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            val effects = mutableListOf<EditorEffect>()
            val job = launch { vm.effects.toList(effects) }
            vm.layers(LayerAction.Import("content://broken", smart = true))
            advanceUntilIdle()
            assertTrue(LayerMath.flatten(vm.graph).isEmpty())
            assertEquals("This photo cannot be used as a layer", (effects.single() as EditorEffect.Message).text)
            job.cancel()
        }

    @Test
    fun theBlendListPreviewsOnTheCanvasAndATapCommits() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.layers(LayerAction.Import("content://media/picker/1", smart = false))
            advanceUntilIdle()
            val id = vm.state.value.selectedLayerId!!
            val steps = vm.state.value.history.entries.size
            for (mode in listOf(BlendMode.Multiply, BlendMode.Screen, BlendMode.Overlay)) {
                vm.layers(LayerAction.PreviewBlend(id, mode))
                advanceUntilIdle()
                assertEquals(mode, LayerMath.find(canvas.graphs.last(), id)!!.blend)
            }
            // Previews never become History steps or change the edit.
            assertEquals(steps, vm.state.value.history.entries.size)
            assertEquals(BlendMode.Normal, LayerMath.find(vm.graph, id)!!.blend)
            vm.layers(LayerAction.PreviewBlend(id, null))
            advanceUntilIdle()
            assertEquals(BlendMode.Normal, LayerMath.find(canvas.graphs.last(), id)!!.blend)
            vm.layers(LayerAction.SetBlend(id, BlendMode.Difference))
            advanceUntilIdle()
            assertEquals(BlendMode.Difference, LayerMath.find(vm.graph, id)!!.blend)
            assertEquals("Photo 2: Difference", vm.lastLabel)
            assertNull(vm.state.value.compareGraph)
        }

    @Test
    fun anOpacityDragIsOneStepAndLockedLayersRefuseIt() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.layers(LayerAction.Import("content://media/picker/1", smart = false))
            advanceUntilIdle()
            val id = vm.state.value.selectedLayerId!!
            val steps = vm.state.value.history.entries.size
            listOf(90f, 70f, 50f).forEach { vm.layers(LayerAction.SetOpacity(id, it)) }
            vm.onIntent(EditorIntent.EndEdit)
            assertEquals(steps + 1, vm.state.value.history.entries.size)
            assertEquals("Photo 2: Opacity 50 %", vm.lastLabel)
            assertEquals(50f, LayerMath.find(vm.graph, id)!!.opacity)
            vm.layers(LayerAction.SetLocked(id, true))
            vm.layers(LayerAction.SetOpacity(id, 10f))
            assertEquals(50f, LayerMath.find(vm.graph, id)!!.opacity)
            vm.layers(LayerAction.Delete(id))
            assertNotNull(LayerMath.find(vm.graph, id))
        }

    @Test
    fun aSwipedAwayLayerComesBackWithUndo() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.layers(LayerAction.AddAdjustment(AdjustmentKind.Saturation))
            vm.layers(LayerAction.AddGroup)
            val group = vm.state.value.selectedLayerId!!
            vm.layers(LayerAction.AddAdjustment(AdjustmentKind.Exposure))
            val before = vm.graph
            val effects = mutableListOf<EditorEffect>()
            val job = launch { vm.effects.toList(effects) }
            val sat =
                LayerMath
                    .flatten(before)
                    .first { it.first.name == "Saturation" }
                    .first.id
            vm.layers(LayerAction.Delete(sat))
            advanceUntilIdle()
            assertNull(LayerMath.find(vm.graph, sat))
            val undo = effects.filterIsInstance<EditorEffect.Undoable>().single()
            assertEquals("Deleted Saturation", undo.text)
            vm.onIntent(undo.undo)
            assertEquals(LayerMath.flatten(before).map { it.first.id }, LayerMath.flatten(vm.graph).map { it.first.id })
            assertTrue(LayerMath.find(vm.graph, group) != null)
            job.cancel()
        }

    @Test
    fun adjustmentLayersCarryTheirOpsAndFromCurrentEditCopiesPointOps() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.layers(LayerAction.AddAdjustment(null))
            assertTrue(LayerMath.flatten(vm.graph).isEmpty()) // nothing edited yet
            vm.onIntent(EditorIntent.SetLight(LightParam.Exposure, 0.7f))
            vm.onIntent(EditorIntent.EndEdit)
            vm.layers(LayerAction.AddAdjustment(null))
            val copied = vm.state.value.selectedLayer!!
            assertEquals(LayerType.Adjustment, copied.type)
            assertTrue(copied.ops.any { it is OpParams.Exposure })
            assertTrue(copied.ops.none { it is OpParams.HighlightsShadows || it is OpParams.Geometry })
            vm.layers(LayerAction.AddAdjustment(AdjustmentKind.Posterize))
            val id = vm.state.value.selectedLayerId!!
            vm.layers(LayerAction.SetAdjustment(id, AdjustmentKind.Posterize, 4.4f))
            assertEquals(
                listOf<OpParams>(OpParams.Posterize(4f)),
                vm.state.value.selectedLayer!!
                    .ops,
            )
        }

    @Test
    fun mergeDownAndFlattenGroupWithoutTouchingPixels() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.layers(LayerAction.AddAdjustment(AdjustmentKind.Contrast))
            val first = vm.state.value.selectedLayerId!!
            vm.layers(LayerAction.MergeDown(first))
            assertEquals(1, LayerMath.flatten(vm.graph).size) // the photo is never merged into
            vm.layers(LayerAction.AddAdjustment(AdjustmentKind.Vibrance))
            val second = vm.state.value.selectedLayerId!!
            vm.layers(LayerAction.MergeDown(second))
            val group = vm.graph.layers.last()
            assertEquals(LayerType.Group, group.type)
            assertFalse(group.isolated)
            assertEquals(listOf(first, second), group.children.map { it.id })
            assertEquals(group.id, vm.state.value.selectedLayerId)
            vm.layers(LayerAction.Flatten)
            assertEquals(
                "Flattened",
                vm.graph.layers
                    .last()
                    .name,
            )
            vm.layers(LayerAction.SetBlendSpace(BlendSpace.Linear))
            advanceUntilIdle()
            assertEquals(BlendSpace.Linear, canvas.graphs.last().blendSpace)
            assertEquals("Blend in linear light", vm.lastLabel)
            vm.onIntent(EditorIntent.Undo)
            assertEquals(BlendSpace.Gamma, vm.graph.blendSpace)
        }

    @Test
    fun dragReorderMovesAmongSiblings() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            AdjustmentKind.entries.take(3).forEach { vm.layers(LayerAction.AddAdjustment(it)) }
            val ids =
                vm.graph.layers
                    .drop(1)
                    .map { it.id }
            vm.layers(LayerAction.Move(ids[2], 1))
            assertEquals(
                listOf(ids[2], ids[0], ids[1]),
                vm.graph.layers
                    .drop(1)
                    .map { it.id },
            )
            assertTrue(vm.lastLabel.startsWith("Move "))
        }

    // --- Stage 14: masks and clipping ---

    private fun EditorViewModel.adjustmentWithMask(): String {
        layers(LayerAction.AddAdjustment(AdjustmentKind.Exposure))
        val id = state.value.selectedLayerId!!
        layers(LayerAction.AddMask(id, black = false))
        onIntent(EditorIntent.OpenPanel(EditorPanel.Layers))
        return id
    }

    @Test
    fun aStrokeOnTheTargetedMaskIsOneStepInDocumentPixels() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            val id = vm.adjustmentWithMask()
            assertTrue(vm.state.value.maskTarget)
            assertEquals(dev.mark.lineover.feature.editor.canvas.PickMode.Paint, vm.state.value.canvasPickMode)
            val steps = vm.state.value.history.entries.size
            vm.layers(LayerAction.PaintStart(0.5f, 0.5f))
            vm.layers(LayerAction.PaintMove(0.6f, 0.5f))
            vm.layers(LayerAction.PaintMove(0.7f, 0.55f))
            vm.layers(LayerAction.PaintEnd)
            val mask = LayerMath.find(vm.graph, id)!!.mask!!
            val stroke = mask.paint.single() as MaskPaint.Stroke
            // 8160 x 6120 photo: image point (0.5, 0.5) is (4080, 3060); black by default; 4 % of the long edge.
            assertEquals(listOf(4080f, 3060f, 4896f, 3060f, 5712f, 3366f), stroke.points)
            assertEquals(0f, stroke.value)
            assertEquals(8160 * 0.04f, stroke.radius, 1e-3f)
            assertEquals(steps + 1, vm.state.value.history.entries.size)
            assertEquals("Exposure: Paint mask", vm.lastLabel)
            advanceUntilIdle()
            assertEquals(mask, LayerMath.find(canvas.graphs.last(), id)!!.mask)
            // A second stroke, painting white, is a second step.
            vm.layers(LayerAction.SwapPaint)
            vm.layers(LayerAction.PaintStart(0.1f, 0.1f))
            vm.layers(LayerAction.PaintEnd)
            assertEquals(steps + 2, vm.state.value.history.entries.size)
            assertEquals(1f, (LayerMath.find(vm.graph, id)!!.mask!!.paint[1] as MaskPaint.Stroke).value)
            vm.onIntent(EditorIntent.Undo)
            assertEquals(
                1,
                LayerMath
                    .find(vm.graph, id)!!
                    .mask!!
                    .paint.size,
            )
        }

    @Test
    fun aGradientReplacesTheMaskAndFollowsTheFinger() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            val id = vm.adjustmentWithMask()
            vm.layers(
                LayerAction.SetBrush(
                    vm.state.value.maskBrush
                        .copy(tool = MaskTool.Gradient, white = true),
                ),
            )
            vm.layers(LayerAction.PaintStart(0f, 0.25f))
            vm.layers(LayerAction.PaintMove(0f, 0.75f))
            vm.layers(LayerAction.PaintEnd)
            val g =
                LayerMath
                    .find(vm.graph, id)!!
                    .mask!!
                    .paint
                    .single() as MaskPaint.Gradient
            assertEquals(MaskPaint.Gradient(0f, 1530f, 0f, 4590f, 1f, 0f), g)
            assertEquals("Exposure: Mask gradient", vm.lastLabel)
        }

    @Test
    fun maskSettingsDeleteWithUndoAndLockedLayersRefusePaint() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            val id = vm.adjustmentWithMask()
            vm.layers(LayerAction.SetMaskFeather(id, 30f))
            vm.layers(LayerAction.SetMaskDensity(id, 60f))
            vm.layers(LayerAction.InvertMask(id))
            vm.layers(LayerAction.SetMaskEnabled(id, false))
            val mask = LayerMath.find(vm.graph, id)!!.mask!!
            assertEquals(LayerMask(enabled = false, inverted = true, density = 60f, feather = 30f), mask)
            vm.layers(LayerAction.FillMask(id, white = false))
            assertEquals(0f, LayerMath.find(vm.graph, id)!!.mask!!.base)
            val effects = mutableListOf<EditorEffect>()
            val job = launch { vm.effects.toList(effects) }
            vm.layers(LayerAction.DeleteMask(id))
            advanceUntilIdle()
            assertNull(LayerMath.find(vm.graph, id)!!.mask)
            vm.onIntent(effects.filterIsInstance<EditorEffect.Undoable>().single().undo)
            assertEquals(0f, LayerMath.find(vm.graph, id)!!.mask!!.base)
            job.cancel()
            vm.layers(LayerAction.TargetMask(id, true))
            vm.layers(LayerAction.SetLocked(id, true))
            vm.layers(LayerAction.PaintStart(0.5f, 0.5f))
            assertTrue(
                LayerMath
                    .find(vm.graph, id)!!
                    .mask!!
                    .paint
                    .isEmpty(),
            )
            // Selecting the layer row targets the layer again: one finger is back to the canvas tools.
            vm.layers(LayerAction.Select(id))
            assertFalse(vm.state.value.maskTarget)
        }

    @Test
    fun clippingNeedsALayerBelowAndTheMaskViewReachesTheEngine() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            val id = vm.adjustmentWithMask()
            vm.layers(LayerAction.SetClip(id, true))
            assertFalse(LayerMath.find(vm.graph, id)!!.clip) // nothing below but the photo
            vm.layers(LayerAction.AddAdjustment(AdjustmentKind.Saturation))
            val top = vm.state.value.selectedLayerId!!
            vm.layers(LayerAction.SetClip(top, true))
            assertTrue(LayerMath.find(vm.graph, top)!!.clip)
            assertEquals("Clip Saturation", vm.lastLabel)
            vm.layers(LayerAction.TargetMask(id, true))
            vm.layers(LayerAction.SetMaskView(MaskView.Overlay))
            advanceUntilIdle()
            assertEquals(id to MaskView.Overlay.engine, canvas.maskViews.last())
            vm.layers(LayerAction.TargetMask(id, false))
            advanceUntilIdle()
            assertEquals(null to 0, canvas.maskViews.last())
        }
}
