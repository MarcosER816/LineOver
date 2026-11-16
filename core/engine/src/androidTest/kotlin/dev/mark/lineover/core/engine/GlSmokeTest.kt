package dev.mark.lineover.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * GL smoke test: the native engine creates a GLES 3 context, clears it to a
 * colour and reads the pixel back. On the CI emulator this runs on SwiftShader,
 * which proves correctness only, never performance.
 */
@RunWith(AndroidJUnit4::class)
class GlSmokeTest {
    @Test
    fun clearColorIsReadBack() {
        val pixel = LoEngine.glClearReadback(red = 1f, green = 0.5f, blue = 0.25f, alpha = 1f)

        assertPixel(intArrayOf(255, 128, 64, 255), pixel)
    }

    @Test
    fun differentClearColorGivesDifferentPixel() {
        val pixel = LoEngine.glClearReadback(red = 0f, green = 0f, blue = 1f, alpha = 1f)

        assertPixel(intArrayOf(0, 0, 255, 255), pixel)
    }

    private fun assertPixel(
        expected: IntArray,
        actual: IntArray,
    ) {
        assertEquals(4, actual.size)
        for (channel in expected.indices) {
            assertTrue(
                "channel $channel: expected ${expected[channel]} +/- $GPU_TOLERANCE_LEVELS, got ${actual.toList()}",
                abs(expected[channel] - actual[channel]) <= GPU_TOLERANCE_LEVELS,
            )
        }
    }

    private companion object {
        /** GPU vs CPU reference tolerance: 3/255. */
        const val GPU_TOLERANCE_LEVELS = 3
    }
}
