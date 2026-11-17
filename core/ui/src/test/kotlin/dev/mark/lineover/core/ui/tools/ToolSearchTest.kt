package dev.mark.lineover.core.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSearchTest {
    private fun ids(query: String) = ToolSearch.search(query).map { it.id }

    @Test
    fun healFindsTheHealingTools() {
        val top = ids("heal").take(3)
        assertTrue(top.toString(), "retouch.spot_healing" in top)
        assertTrue(top.toString(), "retouch.healing_brush" in top)
    }

    @Test
    fun synonymEraseObjectFindsRemove() {
        assertEquals("retouch.ai_remove", ids("erase object").first())
        assertEquals("retouch.ai_remove", ids("Erase Object!").first())
    }

    @Test
    fun exactNameWins() {
        assertEquals("light.exposure", ids("exposure").first())
        assertEquals("effects.vignette", ids("vignette").first())
    }

    @Test
    fun spellingVariantsMatch() {
        assertEquals("color.black_and_white", ids("b&w").first())
        assertEquals("color.black_and_white", ids("greyscale").first())
        assertTrue("color.hsl_color_mixer" in ids("colour mixer").take(2))
    }

    @Test
    fun prefixOfAWordMatches() {
        assertTrue("filters.gaussian_blur" in ids("gaus"))
        assertTrue("liquify.puppet_warp" in ids("pupp"))
    }

    @Test
    fun blankAndUnknownGiveNothing() {
        assertTrue(ids("   ").isEmpty())
        assertTrue(ids("zzzqqq").isEmpty())
    }

    @Test
    fun everyToolCanBeFoundByItsName() {
        ToolRegistry.all.forEach { tool ->
            assertTrue(tool.id, tool.id in ids(tool.name).take(3))
        }
    }
}
