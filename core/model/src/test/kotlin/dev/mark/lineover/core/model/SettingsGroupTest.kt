package dev.mark.lineover.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsGroupTest {
    private val photoA =
        listOf(
            OpParams.Exposure(1f),
            OpParams.WhiteBalance(5000f, 0f),
            OpParams.Vibrance(20f),
            OpParams.Clarity(30f),
            OpParams.Lut("abc", "Warm", 80f),
            OpParams.Sharpen(50f),
        )

    @Test
    fun pasteReplacesOnlyTheCheckedGroups() {
        val photoB = listOf(OpParams.Exposure(-1f), OpParams.Contrast(10f), OpParams.Grain(30f), OpParams.Texture(5f))
        val pasted = SettingsGroups.paste(photoB, photoA, setOf(SettingsGroup.Light, SettingsGroup.Effects))
        // Light: B's exposure and contrast go, A's exposure comes; Effects: B's grain and texture
        // go, A's clarity comes. Nothing else of A.
        assertEquals(setOf(OpParams.Exposure(1f), OpParams.Clarity(30f)), pasted.toSet())
    }

    @Test
    fun cropAndOpticsIsOffByDefault() {
        assertFalse(SettingsGroup.Geometry in SettingsGroup.DEFAULTS)
        assertEquals(SettingsGroup.entries.size - 1, SettingsGroup.DEFAULTS.size)
    }

    @Test
    fun everyOpHasAGroup() {
        val ops = photoA + BuiltInPresets.ALL.flatMap { it.ops } + editOps(LightAdjustments(), ColorAdjustments(invert = true))
        for (op in ops) assertEquals(op.toString(), true, SettingsGroup.of(op) != null)
        assertEquals(SettingsGroup.Looks, SettingsGroup.of(OpParams.Profile("flat")))
    }

    private object SettingsGroups {
        fun paste(
            target: List<OpParams>,
            source: List<OpParams>,
            groups: Set<SettingsGroup>,
        ) = SettingsGroup.paste(target, source, groups)
    }
}
