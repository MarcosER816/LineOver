package dev.mark.lineover.core.io.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 13: stored layer names can never reach outside the store. */
class LayerSourcesTest {
    @Test
    fun onlyHashNamesAreAccepted() {
        assertTrue(FileLayerSources.NAME.matches("0123456789abcdef0123456789abcdef.jpg"))
        assertFalse(FileLayerSources.NAME.matches("../secret.jpg"))
        assertFalse(FileLayerSources.NAME.matches("0123456789abcdef0123456789abcdef.jpg/../x"))
        assertFalse(FileLayerSources.NAME.matches("abc.jpg"))
    }

    @Test
    fun extensionsFollowTheMimeType() {
        assertEquals("png", FileLayerSources.extensionOf("image/png"))
        assertEquals("heic", FileLayerSources.extensionOf("image/heif"))
        assertEquals("jpg", FileLayerSources.extensionOf(null))
    }
}
