package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.model.CurveChannel
import dev.mark.lineover.core.model.GradeRange
import dev.mark.lineover.core.model.GradeWheel
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

/** Stage 7 in the ViewModel: curves, levels, grading → graph; the histogram follows the panels. */
@OptIn(ExperimentalCoroutinesApi::class)
class GradeEditingTest {
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
    fun anSCurveWithTwoPointsReachesTheEngine() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.EditGrade { g -> requireNotNull(g.addCurvePoint(CurveChannel.Rgb, 0.25f)).first })
        vm.onIntent(EditorIntent.EditGrade { g -> requireNotNull(g.addCurvePoint(CurveChannel.Rgb, 0.75f)).first })
        vm.onIntent(EditorIntent.EditGrade { it.moveCurvePoint(CurveChannel.Rgb, 1, 0.25f, 0.18f) })
        vm.onIntent(EditorIntent.EditGrade { it.moveCurvePoint(CurveChannel.Rgb, 2, 0.75f, 0.84f) })
        val curves = ops().single() as OpParams.Curves
        assertEquals(listOf(0f, 0f, 0.25f, 0.18f, 0.75f, 0.84f, 1f, 1f), curves.rgb)
        assertEquals(emptyList<Float>(), curves.red)
        // Dragging a point off the graph deletes it; with one point left on the diagonal-free curve it stays.
        vm.onIntent(EditorIntent.EditGrade { it.deleteCurvePoint(CurveChannel.Rgb, 1) })
        assertEquals(listOf(0f, 0f, 0.75f, 0.84f, 1f, 1f), (ops().single() as OpParams.Curves).rgb)
    }

    @Test
    fun curvesComeAfterLightAndLevelsBeforeCurves() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.SetLight(LightParam.Contrast, 20f))
        vm.onIntent(
            EditorIntent.EditGrade { g ->
                requireNotNull(g.addCurvePoint(CurveChannel.Luma, 0.5f)).first.moveCurvePoint(CurveChannel.Luma, 1, 0.5f, 0.6f)
            },
        )
        vm.onIntent(EditorIntent.AutoLevels)
        assertEquals(listOf("Contrast", "Levels", "Curves"), ops().map { it::class.simpleName })
        val levels = ops()[1] as OpParams.Levels
        assertEquals(0.04f, levels.values[5])
        assertEquals(0.92f, levels.values[7])
    }

    @Test
    fun tealShadowsOrangeHighlights() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.EditGrade { it.withWheel(GradeRange.Shadows, GradeWheel(194.8f, 60f, 0f)) })
        vm.onIntent(EditorIntent.EditGrade { it.withWheel(GradeRange.Highlights, GradeWheel(52.8f, 50f, 0f)) })
        val grading = ops().single() as OpParams.ColorGrading
        assertEquals(listOf(194.8f, 60f, 0f), grading.shadows)
        assertEquals(listOf(52.8f, 50f, 0f), grading.highlights)
        assertEquals(50f, grading.blending)
    }

    @Test
    fun curvesAndLevelsPanelsSwitchTheHistogramOn() {
        val vm = viewModel()
        assertFalse(canvas.histogramEnabled)
        vm.onIntent(EditorIntent.SelectTool("light.curves_point"))
        assertTrue(canvas.histogramEnabled)
        vm.onIntent(EditorIntent.SelectTool("light.levels"))
        assertTrue(canvas.histogramEnabled)
        vm.onIntent(EditorIntent.ClosePanel)
        assertFalse(canvas.histogramEnabled)
    }

    @Test
    fun blackAndWhiteAndGradientMapAreOptIn() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.EditGrade { it.copy(bwMix = listOf(10f, 0f, 0f, 0f, 0f, 0f)) })
        assertTrue(
            canvas.graphs
                .last()
                .layers
                .isEmpty() || ops().isEmpty(),
        )
        vm.onIntent(EditorIntent.EditGrade { it.copy(blackAndWhite = true, gradientMap = true) })
        assertEquals(listOf("BlackAndWhite", "GradientMap"), ops().map { it::class.simpleName })
    }
}
