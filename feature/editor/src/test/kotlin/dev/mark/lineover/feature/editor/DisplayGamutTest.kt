package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.engine.testing.F32Image
import dev.mark.lineover.core.engine.testing.GoldenAssert
import dev.mark.lineover.core.engine.testing.NpyIo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The UI's display transform (loupe, gradient stops) is the canvas's: reference.working_to_display. */
class DisplayGamutTest {
    private val golden = File(requireNotNull(System.getProperty("lineover.goldenDir")))

    private fun npy(path: String) = File(golden, path).inputStream().use(NpyIo::readF32)

    @Test
    fun matchesTheReferenceDisplayTransformIncludingOutOfGamutColours() {
        val input = npy("input/pattern_rec2020_f32.npy")
        val expected = npy("color/working_to_srgb_display_f32.npy")
        val out = FloatArray(input.data.size)
        for (i in 0 until input.data.size / 4) {
            val e = DisplayGamut.workingToSrgb(input.data[4 * i], input.data[4 * i + 1], input.data[4 * i + 2])
            for (c in 0 until 3) out[4 * i + c] = e[c]
            out[4 * i + 3] = expected.data[4 * i + 3]
        }
        GoldenAssert.assertMatches(F32Image(input.width, input.height, 4, out), expected, "ui_working_to_srgb_display")
    }

    @Test
    fun whiteAndGreysStayNeutral() {
        for (v in listOf(0f, 0.18f, 1f, 4f)) {
            val e = DisplayGamut.workingToSrgb(v, v, v)
            assertEquals(e[0], e[1], 1e-3f)
            assertEquals(e[1], e[2], 1e-3f)
        }
    }
}
