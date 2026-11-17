package dev.mark.lineover.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

class LoSliderDefaultsTest {
    @Test
    fun decimalsFollowTheStep() {
        assertEquals(0, LoSliderDefaults.decimalsFor(1f))
        assertEquals(0, LoSliderDefaults.decimalsFor(5f))
        assertEquals(1, LoSliderDefaults.decimalsFor(0.1f))
        assertEquals(2, LoSliderDefaults.decimalsFor(0.01f))
        assertEquals(2, LoSliderDefaults.decimalsFor(0.05f))
        assertEquals(2, LoSliderDefaults.decimalsFor(0f))
    }

    @Test
    fun signedValuesShowPlusAndNeverMinusZero() {
        assertEquals("+12", LoSliderDefaults.formatValue(12f, 1f, signed = true))
        assertEquals("-12", LoSliderDefaults.formatValue(-12f, 1f, signed = true))
        assertEquals("0", LoSliderDefaults.formatValue(-0f, 1f, signed = true))
        assertEquals("0.00", LoSliderDefaults.formatValue(-0.001f, 0.01f, signed = true))
        assertEquals("+1.25", LoSliderDefaults.formatValue(1.25f, 0.01f, signed = true))
        assertEquals("40", LoSliderDefaults.formatValue(40f, 1f, signed = false))
    }
}
