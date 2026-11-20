package dev.mark.lineover.core.io.masks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Stage 15: saved selections round-trip to 16 bits and are named by content. */
class MaskRastersTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun aRasterRoundTripsAndTheSameOneIsStoredOnce() {
        val values = FloatArray(40 * 30) { i -> (i % 40) / 39f }
        val store = FileMaskRasters(folder.root)
        val name = requireNotNull(store.save(MaskRaster(40, 30, values)))
        assertEquals(name, store.save(MaskRaster(40, 30, values.copyOf())))
        assertEquals(1, folder.root.listFiles()!!.size)
        val back = requireNotNull(store.load(name))
        assertEquals(40, back.width)
        assertEquals(30, back.height)
        for (i in values.indices) assertEquals(values[i], back.values[i], 1f / 65535f)
        assertNull(store.load("../$name"))
        assertNull(store.load("0123456789abcdef0123456789abcdef.lomask"))
    }
}
