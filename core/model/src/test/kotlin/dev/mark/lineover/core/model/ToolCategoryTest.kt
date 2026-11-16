package dev.mark.lineover.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCategoryTest {
    @Test
    fun categoryOrderIsFixed() {
        val expected =
            listOf(
                "favorites",
                "presets",
                "auto",
                "light",
                "color",
                "effects",
                "detail",
                "optics",
                "crop",
                "masks",
                "select",
                "retouch",
                "paint",
                "filters",
                "liquify",
                "text",
                "shapes",
                "ai",
            )

        assertEquals(expected, ToolCategory.entries.map { it.id })
    }
}
