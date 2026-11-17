package dev.mark.lineover.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

class LoPanelMathTest {
    @Test
    fun heightsAreCappedAtFortyPercentOfTheWindow() {
        assertEquals(160f to 280f, LoPanelMath.heights(windowHeightPx = 2000f, collapsedPx = 160f, expandedPx = 280f))
        // Landscape phone: 40 % of 500 px is 200 px.
        assertEquals(160f to 200f, LoPanelMath.heights(windowHeightPx = 500f, collapsedPx = 160f, expandedPx = 280f))
        // Very short window: both detents shrink to the cap.
        assertEquals(120f to 120f, LoPanelMath.heights(windowHeightPx = 300f, collapsedPx = 160f, expandedPx = 280f))
    }

    @Test
    fun settlesAtNearestDetentUnlessFlung() {
        assertEquals(LoPanelDetent.Collapsed, LoPanelMath.settle(200f, 0f, 160f, 280f))
        assertEquals(LoPanelDetent.Expanded, LoPanelMath.settle(230f, 0f, 160f, 280f))
        assertEquals(LoPanelDetent.Expanded, LoPanelMath.settle(170f, -2000f, 160f, 280f))
        assertEquals(LoPanelDetent.Collapsed, LoPanelMath.settle(270f, 2000f, 160f, 280f))
    }
}
