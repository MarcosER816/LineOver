package dev.mark.lineover.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DetailAdjustmentsTest {
    private val detail =
        DetailAdjustments()
            .with(DetailParam.NoiseLuminance, 40f)
            .with(DetailParam.NoiseContrast, 10f)
            .with(DetailParam.NoiseColor, 30f)
            .with(DetailParam.Clarity, 25f)
            .with(DetailParam.Texture, -15f)
            .with(DetailParam.Dehaze, 35f)
            .with(DetailParam.SharpenAmount, 60f)
            .with(DetailParam.SharpenRadius, 1.2f)
            .with(DetailParam.SharpenMasking, 30f)
            .with(DetailParam.VignetteAmount, -30f)
            .with(DetailParam.VignetteRoundness, 20f)
            .with(DetailParam.VignetteFeather, 60f)
            .with(DetailParam.VignetteHighlights, 10f)
            .with(DetailParam.GrainAmount, 20f)

    private val fixture: String by lazy {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        File(requireNotNull(dir), "testdata/graph/detail_v1.json").readText().trimEnd()
    }

    @Test
    fun encodingIsTheSharedNativeFixture() {
        val graph = EditGraph().withBackgroundOps(editOps(LightAdjustments(), ColorAdjustments(), detail = detail))
        assertEquals(fixture, EditGraphJson.encode(graph))
        assertEquals(graph, EditGraphJson.decode(fixture))
    }

    @Test
    fun opsRunInTheEngineOrder() {
        val geometry =
            OpParams.Geometry(100, 80, 100, 80, vignette = 10f, matrix = listOf(100.0, 0.0, 0.0, 0.0, 80.0, 0.0, 0.0, 0.0, 1.0))
        val light = LightAdjustments().with(LightParam.Exposure, 1f)
        val color = ColorAdjustments(vibrance = 10f, threshold = 0.5f)
        val ops = editOps(light, color, GradeAdjustments(), geometry, detail).map { it::class.simpleName }
        assertEquals(
            listOf(
                "Geometry",
                "NoiseReduction",
                "Clarity",
                "Texture",
                "Dehaze",
                "Exposure",
                "Vibrance",
                "Threshold",
                "Sharpen",
                "PostCropVignette",
                "Grain",
            ),
            ops,
        )
    }

    @Test
    fun defaultsAddNoOps() {
        assertTrue(DetailAdjustments().isIdentity)
        assertEquals(emptyList<OpParams>(), editOps(LightAdjustments(), ColorAdjustments(), detail = DetailAdjustments()))
        // Secondary sliders alone change nothing: the tool is off until its main slider moves.
        assertTrue(DetailAdjustments().with(DetailParam.SharpenRadius, 2f).with(DetailParam.GrainSize, 80f).isIdentity)
    }

    @Test
    fun readsBackFromOps() {
        assertEquals(detail, DetailAdjustments.fromOps(editOps(LightAdjustments(), ColorAdjustments(), detail = detail)))
    }

    @Test
    fun valuesAreCoercedToTheirRanges() {
        val d = DetailAdjustments().with(DetailParam.SharpenRadius, 9f).with(DetailParam.Dehaze, -300f)
        assertEquals(3f, d[DetailParam.SharpenRadius])
        assertEquals(-100f, d[DetailParam.Dehaze])
        assertEquals(1f, DetailAdjustments()[DetailParam.SharpenRadius])
    }

    @Test
    fun theMaskPreviewOnlyChangesShowMask() {
        val plain = detail.sharpenOps().single() as OpParams.Sharpen
        val mask = detail.sharpenOps(showMask = true).single() as OpParams.Sharpen
        assertEquals(0f, plain.showMask)
        assertEquals(plain.copy(showMask = 1f), mask)
        // Masking held with Amount 0 still shows the mask.
        assertEquals(1, DetailAdjustments().sharpenOps(showMask = true).size)
    }

    @Test
    fun everyToolHasSliders() {
        for (tool in DetailTools.ALL) assertTrue(tool, DetailParam.forTool(tool).isNotEmpty())
        assertEquals(listOf("Amount", "Radius", "Detail", "Masking"), DetailParam.forTool(DetailTools.SHARPEN).map { it.label })
    }

    @Test
    fun historyNamesTheTools() {
        val before = EditGraph()
        val after =
            EditGraph().withBackgroundOps(
                editOps(LightAdjustments(), ColorAdjustments(), detail = DetailAdjustments().with(DetailParam.Clarity, 20f)),
            )
        assertEquals("Clarity +20", HistoryLabels.describe(before, after).label)
    }
}
