package dev.mark.lineover.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsTest {
    private val preset =
        listOf(
            OpParams.Exposure(1f),
            OpParams.WhiteBalance(5000f, 10f),
            OpParams.ColorGrading(50f, 0f, listOf(200f, 40f, 0f), listOf(0f, 0f, 0f), listOf(40f, 20f, 0f), listOf(0f, 0f, 0f)),
            OpParams.Profile("mono"),
        )

    @Test
    fun amountMovesFromThePhotoTowardsThePreset() {
        val base = listOf(OpParams.Exposure(0.5f), OpParams.Contrast(20f))
        val half = PresetMath.apply(base, preset, 0.5f)
        assertEquals(OpParams.Exposure(0.75f), half.first { it is OpParams.Exposure })
        // Ops the preset does not set are kept.
        assertEquals(OpParams.Contrast(20f), half.first { it is OpParams.Contrast })
        // White balance from the neutral 6504 K.
        assertEquals(5752f, (half.first { it is OpParams.WhiteBalance } as OpParams.WhiteBalance).temperature, 1e-3f)
        val full = PresetMath.apply(base, preset, 1f)
        assertEquals(OpParams.Exposure(1f), full.first { it is OpParams.Exposure })
        assertEquals(OpParams.Profile("mono", 100f), full.first { it is OpParams.Profile })
        val double = PresetMath.apply(base, preset, 2f)
        assertEquals(OpParams.Exposure(1.5f), double.first { it is OpParams.Exposure })
        assertEquals(base, PresetMath.apply(base, preset, 0f))
    }

    @Test
    fun aWheelKeepsThePresetHueAndBlendsItsStrength() {
        val g = PresetMath.apply(emptyList(), preset, 0.5f).first { it is OpParams.ColorGrading } as OpParams.ColorGrading
        assertEquals(listOf(200f, 20f, 0f), g.shadows)
        assertEquals(listOf(40f, 10f, 0f), g.highlights)
        assertEquals(50f, g.blending)
    }

    @Test
    fun curvesBlendFromTheBaseCurve() {
        val p = OpParams.Curves(0f, listOf(0f, 0f, 0.5f, 0.7f, 1f, 1f), emptyList(), emptyList(), emptyList(), emptyList())
        val c = PresetMath.apply(emptyList(), listOf(p), 0.5f).single() as OpParams.Curves
        assertEquals(listOf(0f, 0f, 0.5f, 0.6f, 1f, 1f), c.rgb)
    }

    @Test
    fun discreteOpsAreThePresetsOnceAmountIsAboveZero() {
        val bw = OpParams.BlackAndWhite(0f, 0f, List(6) { 10f })
        assertEquals(listOf(bw), PresetMath.apply(emptyList(), listOf(bw), 0.3f))
    }

    @Test
    fun builtInPresetsAreOriginalValidAndUnique() {
        val all = BuiltInPresets.ALL
        assertEquals(20, all.size)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.name }.toSet().size)
        for (p in all) {
            assertTrue(p.id, p.builtIn && p.group in BuiltInPresets.GROUPS && p.ops.isNotEmpty())
            // Every op survives the graph JSON the engine reads.
            assertEquals(p.graph, EditGraphJson.decode(EditGraphJson.encode(p.graph)))
            // No preset sets crop or optics.
            assertTrue(p.id, p.ops.none { SettingsGroup.of(it) == SettingsGroup.Geometry })
        }
    }

    @Test
    fun aBlendedPresetReadsBackIntoThePanels() {
        val ops = PresetMath.apply(emptyList(), BuiltInPresets.byId("builtin.punchy")!!.ops, 1.5f)
        val light = LightAdjustments.fromOps(ops)
        assertEquals(45f, light[LightParam.Contrast], 1e-4f)
        assertEquals(LookAdjustments(profile = "vivid", profileAmount = 100f), LookAdjustments.fromOps(ops))
    }
}
