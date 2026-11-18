package dev.mark.lineover.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ColorAdjustmentsTest {
    private val color =
        ColorAdjustments()
            .withWhiteBalance(5200f, 12f)
            .copy(vibrance = 25f, saturation = -10f, invert = true, posterize = 6, threshold = 0.5f)
            .withHsl(HslChannel.Hue, HslBand.Orange, 10f)
            .withHsl(HslChannel.Hue, HslBand.Blue, -20f)
            .withHsl(HslChannel.Saturation, HslBand.Orange, 30f)
            .withHsl(HslChannel.Luminance, HslBand.Blue, -15f)

    private val fixture: String by lazy {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        File(requireNotNull(dir), "testdata/graph/color_v1.json").readText().trimEnd()
    }

    @Test
    fun encodingIsTheSharedNativeFixture() {
        val graph = EditGraph().withBackgroundOps(editOps(LightAdjustments(), color))
        assertEquals(fixture, EditGraphJson.encode(graph))
        assertEquals(graph, EditGraphJson.decode(fixture))
    }

    @Test
    fun whiteBalanceRunsBeforeLightAndTheRestAfter() {
        val light = LightAdjustments().with(LightParam.Exposure, 1f)
        val ops = editOps(light, ColorAdjustments(temperature = 4000f, vibrance = 10f))
        assertEquals(listOf(OpParams.WhiteBalance(4000f, 0f), OpParams.Exposure(1f), OpParams.Vibrance(10f)), ops)
    }

    @Test
    fun defaultsAddNoOps() {
        assertTrue(ColorAdjustments().isIdentity)
        assertEquals(emptyList<OpParams>(), editOps(LightAdjustments(), ColorAdjustments()))
    }

    @Test
    fun readsBackFromOps() {
        assertEquals(color, ColorAdjustments.fromOps(editOps(LightAdjustments(), color)))
    }

    @Test
    fun temperatureSliderIsLinearInMireds() {
        assertEquals(0f, ColorAdjustments.temperaturePosition(2000f), 1e-6f)
        assertEquals(1f, ColorAdjustments.temperaturePosition(50000f), 1e-6f)
        assertEquals(6504f, ColorAdjustments.kelvinAt(ColorAdjustments.temperaturePosition(6504f)), 0.1f)
        assertEquals("5600 K", ColorAdjustments.kelvinText(5612f))
        // The default (6504 K) sits about 72 % along the track.
        assertEquals(0.721f, ColorAdjustments.temperaturePosition(6504f), 1e-3f)
    }

    @Test
    fun targetedNudgeFollowsTheWeights() {
        val weights =
            FloatArray(8).also {
                it[1] = 0.75f
                it[2] = 0.25f
            }
        val nudged = ColorAdjustments().nudgeHsl(HslChannel.Saturation, weights, 40f)
        assertEquals(listOf(0f, 30f, 10f, 0f, 0f, 0f, 0f, 0f), nudged.hsl(HslChannel.Saturation))
        assertEquals(100f, ColorAdjustments().nudgeHsl(HslChannel.Hue, weights, 1000f).hsl(HslChannel.Hue)[1])
    }

    @Test
    fun whiteBalanceIsClamped() {
        val wb = ColorAdjustments().withWhiteBalance(100f, 900f)
        assertEquals(2000f, wb.temperature)
        assertEquals(150f, wb.tint)
    }
}
