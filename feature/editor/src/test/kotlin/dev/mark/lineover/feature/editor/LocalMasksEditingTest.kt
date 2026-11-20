package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.io.masks.MaskRaster
import dev.mark.lineover.core.io.masks.MaskRasters
import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.ml.ModelImage
import dev.mark.lineover.core.ml.ModelInfo
import dev.mark.lineover.core.ml.ModelMask
import dev.mark.lineover.core.ml.ModelRun
import dev.mark.lineover.core.ml.ModelState
import dev.mark.lineover.core.ml.SegmentPrompt
import dev.mark.lineover.core.ml.SmartModels
import dev.mark.lineover.core.ml.SmartTool
import dev.mark.lineover.core.model.AiMaskKind
import dev.mark.lineover.core.model.CombineMode
import dev.mark.lineover.core.model.LayerType
import dev.mark.lineover.core.model.LocalAdjustments
import dev.mark.lineover.core.model.LocalParam
import dev.mark.lineover.core.model.MaskComponent
import dev.mark.lineover.core.model.OpParams
import dev.mark.lineover.core.model.ToolCategory
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 17 in the ViewModel: masks from the strip, the brief's phone checks (brighten the
 * subject; darken the sky with a linear gradient intersected with Sky), pins, the overlay,
 * Duplicate and invert, the amount, the local adjustment set, gestures and History steps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalMasksEditingTest {
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

    private class ReadyModels : SmartModels {
        override val states = MutableStateFlow(SmartTool.entries.associateWith<SmartTool, ModelState> { ModelState.Ready })
        override val lastRun = MutableStateFlow<ModelRun?>(null)
        val runs = mutableListOf<String>()

        override fun info(tool: SmartTool) = ModelInfo(tool, "Model", 1L, "Apache License 2.0", "", "example.org")

        override suspend fun prepare(
            tool: SmartTool,
            allowMetered: Boolean,
        ) = ModelState.Ready

        override suspend fun segment(
            tool: SmartTool,
            image: ModelImage,
            prompts: List<SegmentPrompt>,
        ): ModelMask {
            runs += tool.name
            return ModelMask(2, 2, floatArrayOf(1f, 0f, 1f, 0.5f))
        }

        override suspend fun people(image: ModelImage): ModelMask {
            runs += "People"
            return ModelMask(2, 2, floatArrayOf(0f, 1f, 0f, 1f))
        }

        override suspend fun delete(tool: SmartTool) = Unit
    }

    private val rasters = MemoryRasters()
    private val models = ReadyModels()

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

    private fun EditorViewModel.mask(action: MaskAction) = onIntent(EditorIntent.Mask(action))

    private val EditorViewModel.masks get() = state.value.localMasks

    private val EditorViewModel.selected get() = requireNotNull(state.value.selectedLocalMask)

    private fun EditorViewModel.drag(vararg points: Pair<Float, Float>) {
        mask(MaskAction.Start(points[0].first, points[0].second))
        points.drop(1).forEach { (u, v) -> mask(MaskAction.Move(u, v)) }
        mask(MaskAction.End)
    }

    @Test
    fun brightenTheSubjectOnly() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SelectCategory(ToolCategory.Masks))
            vm.onIntent(EditorIntent.SelectTool(MaskTools.SUBJECT))
            advanceUntilIdle()
            assertEquals(listOf("Subject"), models.runs)
            val layer = vm.selected
            assertEquals(LayerType.Adjustment, layer.type)
            val subject = layer.mask!!.components.single() as MaskComponent.Raster
            assertEquals(AiMaskKind.Subject, subject.tool)
            // Saved upsampled with its colour guide, registered with the engine.
            assertEquals(8f, subject.width)
            assertEquals(canvas.imageWidth.toFloat(), subject.docWidth)
            assertNotNull(subject.guide)
            assertTrue(canvas.maskRasters.keys.containsAll(listOfNotNull(subject.source, subject.guide)))
            vm.mask(MaskAction.SetParam(LocalParam.Exposure, 0.7f))
            vm.onIntent(EditorIntent.EndEdit)
            assertEquals(listOf(OpParams.Exposure(0.7f)), vm.selected.ops)
            assertEquals(EditorPanel.Tool(MaskTools.SUBJECT), vm.state.value.panel)
            // The canvas gets the graph: the photo plus this one adjustment layer.
            advanceUntilIdle()
            val sent = canvas.graphs.last()
            assertEquals(2, sent.layers.size)
            assertEquals(listOf(OpParams.Exposure(0.7f)), sent.layers[1].ops)
        }

    @Test
    fun darkenTheSkyWithALinearGradientIntersectedWithSky() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SelectCategory(ToolCategory.Masks))
            vm.onIntent(EditorIntent.SelectTool(MaskTools.LINEAR))
            advanceUntilIdle()
            // Drag from the top (full) to the middle (none).
            vm.drag(0.5f to 0f, 0.5f to 0.3f, 0.5f to 0.5f)
            val linear =
                vm.selected.mask!!
                    .components
                    .single() as MaskComponent.Linear
            assertEquals(0f, linear.y0, 1e-3f)
            assertEquals(canvas.imageHeight * 0.5f, linear.y1, 1f)
            // Intersect: the next tool joins this mask.
            vm.mask(MaskAction.Join(CombineMode.Intersect))
            vm.onIntent(EditorIntent.SelectTool(MaskTools.SKY))
            advanceUntilIdle()
            val components = vm.selected.mask!!.components
            assertEquals(2, components.size)
            assertEquals(CombineMode.Intersect, components[1].mode)
            assertEquals(AiMaskKind.Sky, (components[1] as MaskComponent.Raster).tool)
            assertEquals(1, vm.masks.size)
            vm.mask(MaskAction.SetParam(LocalParam.Exposure, -1f))
            vm.onIntent(EditorIntent.EndEdit)
            assertEquals(-1f, LocalAdjustments.fromOps(vm.selected.ops)[LocalParam.Exposure])
            // The History names the steps.
            val labels =
                vm.state.value.history.entries
                    .map { it.label }
            assertTrue(labels.any { it.contains("Add Linear gradient mask") })
            assertTrue(labels.any { it.contains("Add Sky") })
        }

    @Test
    fun pinsOverlayDuplicateAndInvertAndAmount() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SelectCategory(ToolCategory.Masks))
            vm.onIntent(EditorIntent.SelectTool(MaskTools.RADIAL))
            advanceUntilIdle()
            val first = vm.selected.id
            // One pin per mask, at the radial's centre; the selected one is marked.
            val pin =
                vm.state.value.maskPins
                    .single()
            assertEquals(0.5f, pin.u, 1e-3f)
            assertTrue(pin.selected)
            // Move the centre, then size it.
            vm.drag(0.5f to 0.5f, 0.3f to 0.4f)
            assertEquals(canvas.imageWidth * 0.3f, (vm.selected.mask!!.components[0] as MaskComponent.Radial).cx, 1f)
            vm.drag(0.6f to 0.6f, 0.7f to 0.6f)
            assertEquals(canvas.imageWidth * 0.4f, (vm.selected.mask!!.components[0] as MaskComponent.Radial).rx, 1f)
            // Overlay: the engine shows this mask in red.
            vm.mask(MaskAction.ToggleOverlay)
            assertEquals(first to MaskView.Overlay, vm.state.value.engineMaskView)
            // Amount = the layer's opacity.
            vm.mask(MaskAction.SetAmount(60f))
            vm.onIntent(EditorIntent.EndEdit)
            assertEquals(60f, vm.selected.opacity)
            // Duplicate and invert: a second mask, the same components, the mask inverted.
            vm.mask(MaskAction.DuplicateAndInvert)
            assertEquals(2, vm.masks.size)
            val copy = vm.selected
            assertTrue(copy.id != first)
            assertTrue(copy.mask!!.inverted)
            assertEquals(vm.masks[0].mask!!.components, copy.mask!!.components)
            assertEquals(60f, copy.opacity)
            // Two pins at the same place: tapping a pin of another mask selects it.
            vm.mask(MaskAction.Select(null))
            assertNull(vm.state.value.selectedLocalMask)
            vm.onIntent(EditorIntent.ClosePanel)
            vm.mask(MaskAction.Start(0.3f, 0.4f))
            vm.mask(MaskAction.End)
            assertNotNull(vm.state.value.selectedLocalMask)
        }

    @Test
    fun brushWithAutoMaskColourRangeAndComponents() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SelectCategory(ToolCategory.Masks))
            vm.onIntent(EditorIntent.SelectTool(MaskTools.BRUSH))
            advanceUntilIdle()
            vm.mask(MaskAction.SetBrush(LocalBrush(size = 100f, feather = 30f, flow = 80f, autoMask = true)))
            vm.drag(0.2f to 0.2f, 0.4f to 0.2f, 0.6f to 0.2f)
            advanceUntilIdle()
            val brush =
                vm.selected.mask!!
                    .components
                    .single() as MaskComponent.Brush
            assertTrue(brush.autoMask)
            val stroke = brush.strokes.single()
            assertEquals(100f, stroke.radius)
            assertEquals(0.7f, stroke.hardness, 1e-6f)
            assertEquals(80f, stroke.opacity)
            assertEquals(6, stroke.points.size)
            assertEquals(canvas.labAt.toList(), stroke.color)
            // Erase strokes paint 0 and carry no colour.
            vm.mask(MaskAction.SetBrush(LocalBrush(size = 50f, erase = true, autoMask = true)))
            vm.drag(0.3f to 0.2f, 0.35f to 0.2f)
            advanceUntilIdle()
            val erase =
                (
                    vm.selected.mask!!
                        .components
                        .single() as MaskComponent.Brush
                ).strokes.last()
            assertEquals(0f, erase.value)
            assertNull(erase.color)
            // Subtract a colour range: tapping picks the photo's colour.
            vm.mask(MaskAction.Join(CombineMode.Subtract))
            vm.onIntent(EditorIntent.SelectTool(MaskTools.COLOR))
            advanceUntilIdle()
            vm.mask(MaskAction.Start(0.5f, 0.5f))
            vm.mask(MaskAction.End)
            advanceUntilIdle()
            val range = vm.selected.mask!!.components[1] as MaskComponent.ColorRange
            assertEquals(CombineMode.Subtract, range.mode)
            assertEquals(canvas.labAt.toList(), range.colors)
            // Invert and delete a component; the first always adds.
            vm.mask(MaskAction.InvertComponent(1))
            assertTrue(
                vm.selected.mask!!
                    .components[1]
                    .invert,
            )
            vm.mask(MaskAction.DeleteComponent(0))
            val left =
                vm.selected.mask!!
                    .components
                    .single()
            assertEquals(CombineMode.Add, left.mode)
            assertTrue(left is MaskComponent.ColorRange)
            // Deleting the last component deletes the mask.
            vm.mask(MaskAction.DeleteComponent(0))
            assertTrue(vm.masks.isEmpty())
        }

    @Test
    fun peopleBackgroundAndTheFullAdjustmentSet() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(EditorIntent.SelectCategory(ToolCategory.Masks))
            vm.onIntent(EditorIntent.SelectTool(MaskTools.PEOPLE))
            advanceUntilIdle()
            assertEquals(
                AiMaskKind.People,
                (
                    vm.selected.mask!!
                        .components
                        .single() as MaskComponent.Raster
                ).tool,
            )
            vm.onIntent(EditorIntent.SelectTool(MaskTools.BACKGROUND))
            advanceUntilIdle()
            // Background is the subject inverted (a second mask: nothing was joined).
            assertEquals(2, vm.masks.size)
            val background =
                vm.selected.mask!!
                    .components
                    .single() as MaskComponent.Raster
            assertEquals(AiMaskKind.Background, background.tool)
            assertTrue(background.invert)
            assertEquals(listOf("People", "Subject"), models.runs)
            // Every slider reaches its op; neutral ones add nothing.
            for (p in LocalParam.entries) vm.mask(MaskAction.SetParam(p, if (p == LocalParam.ColorHue) 200f else p.max / 2f))
            vm.onIntent(EditorIntent.EndEdit)
            val ops = vm.selected.ops
            val kinds = ops.map { it::class.simpleName }.toSet()
            assertEquals(
                setOf(
                    "WhiteBalance",
                    "HighlightsShadows",
                    "Exposure",
                    "Contrast",
                    "Whites",
                    "Blacks",
                    "Saturation",
                    "PhotoFilter",
                    "Texture",
                    "Clarity",
                    "Dehaze",
                    "NoiseReduction",
                    "Sharpen",
                ),
                kinds,
            )
            val back = LocalAdjustments.fromOps(ops)
            for (p in LocalParam.entries) assertEquals(p.name, if (p == LocalParam.ColorHue) 200f else p.max / 2f, back[p], 1e-3f)
            for (p in LocalParam.entries) vm.mask(MaskAction.SetParam(p, LocalAdjustments.default(p)))
            assertTrue(vm.selected.ops.isEmpty())
            assertFalse(vm.state.value.localPainting && vm.state.value.category != ToolCategory.Masks)
        }
}
