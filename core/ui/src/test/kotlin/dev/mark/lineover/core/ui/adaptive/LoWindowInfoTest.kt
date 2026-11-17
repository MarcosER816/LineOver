package dev.mark.lineover.core.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoWindowInfoTest {
    @Test
    fun widthClassesFollowTheMaterialBreakpoints() {
        assertEquals(LoWidthClass.Compact, LoWindowInfo.of(411f, 891f).widthClass) // phone portrait
        assertEquals(LoWidthClass.Medium, LoWindowInfo.of(673f, 841f).widthClass) // unfolded foldable
        assertEquals(LoWidthClass.Expanded, LoWindowInfo.of(891f, 411f).widthClass) // phone landscape
        assertEquals(LoWidthClass.Expanded, LoWindowInfo.of(1280f, 800f).widthClass) // tablet
    }

    @Test
    fun twoPanesOnlyWhenExpanded() {
        assertFalse(LoWindowInfo.of(700f, 900f).showsTwoPanes)
        assertTrue(LoWindowInfo.of(1024f, 768f).showsTwoPanes)
        assertTrue(LoWindowInfo.of(891f, 411f).isLandscape)
    }
}
