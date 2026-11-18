package dev.mark.lineover.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LightAdjustmentsTest {
    private val light =
        LightAdjustments()
            .with(LightParam.Brightness, 5f)
            .with(LightParam.Contrast, 25f)
            .with(LightParam.Blacks, -10f)
            .with(LightParam.Whites, 10f)
            .with(LightParam.Exposure, 1.5f)
            .with(LightParam.Shadows, 30f)
            .with(LightParam.Highlights, -20f)

    /** The fixture the native light_test also builds its op list from. */
    private val fixture: String by lazy {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        File(requireNotNull(dir), "testdata/graph/light_v1.json").readText().trimEnd()
    }

    @Test
    fun opsRunInTheFixedOrderWhateverTheEditOrder() {
        assertEquals(
            listOf(
                OpParams.HighlightsShadows(-20f, 30f),
                OpParams.Exposure(1.5f),
                OpParams.Whites(10f),
                OpParams.Blacks(-10f),
                OpParams.Contrast(25f),
                OpParams.Brightness(5f),
            ),
            light.toOps(),
        )
    }

    @Test
    fun encodingIsTheSharedNativeFixture() {
        val graph = EditGraph().withBackgroundOps(light.toOps())
        assertEquals(fixture, EditGraphJson.encode(graph))
        assertEquals(graph, EditGraphJson.decode(fixture))
    }

    @Test
    fun slidersAtZeroAddNoOps() {
        assertTrue(LightAdjustments().isIdentity)
        assertEquals(emptyList<OpParams>(), LightAdjustments().toOps())
        assertEquals(listOf(OpParams.HighlightsShadows(0f, 40f)), LightAdjustments().with(LightParam.Shadows, 40f).toOps())
    }

    @Test
    fun valuesAreClampedToTheSliderRange() {
        assertEquals(5f, LightAdjustments().with(LightParam.Exposure, 9f)[LightParam.Exposure])
        assertEquals(-100f, LightAdjustments().with(LightParam.Contrast, -300f)[LightParam.Contrast])
    }

    @Test
    fun opsReadBackIntoTheSameValues() {
        assertEquals(light, LightAdjustments.fromOps(light.toOps() + OpParams.Identity))
    }

    @Test
    fun panelOrderIsTheBriefsOrder() {
        assertEquals(
            listOf("Exposure", "Contrast", "Highlights", "Shadows", "Whites", "Blacks", "Brightness"),
            LightParam.entries.map { it.label },
        )
        assertEquals(LightParam.Shadows, LightParam.forTool("light.shadows"))
    }
}
