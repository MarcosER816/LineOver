package dev.mark.lineover.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatSizeTest {
    @Test
    fun sizesAreHumanReadable() {
        assertEquals("1 KB", formatSize(10))
        assertEquals("717 KB", formatSize(734_003))
        assertEquals("4.5 MB", formatSize(4_718_592))
    }
}
