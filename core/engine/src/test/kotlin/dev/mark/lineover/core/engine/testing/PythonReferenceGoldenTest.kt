package dev.mark.lineover.core.engine.testing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * JVM-side checks against the Python reference files in testdata/golden. The
 * native identity op itself is compared on the host by ctest
 * (golden_identity_test) and on a device by IdentityGoldenInstrumentedTest.
 */
class PythonReferenceGoldenTest {
    private val goldenDir = File(checkNotNull(System.getProperty("lineover.goldenDir")) { "lineover.goldenDir is not set" })

    private fun png(path: String) = File(goldenDir, path).inputStream().use(PngIo::read)

    private fun npy(path: String) = File(goldenDir, path).inputStream().use(NpyIo::readF32)

    @Test
    fun pngDecoderMatchesPythonDecoding() {
        val decoded = png("input/pattern_rgba8.png")
        val reference = npy("input/pattern_rgba8_as_f32.npy")

        assertEquals(listOf(64, 48, 4), listOf(decoded.width, decoded.height, decoded.channels))
        GoldenAssert.assertMatches(decoded.toF32(), reference, "kotlin_png_decoder_vs_python", Tolerance(1e-6, 1e-6))
    }

    @Test
    fun identityReferenceEqualsItsInput() {
        GoldenAssert.assertMatches(
            png("input/pattern_rgba8.png"),
            png("identity/expected_rgba8.png"),
            "kotlin_identity_reference_rgba8",
            Tolerance(0.0, 0.0),
        )
        GoldenAssert.assertMatches(
            npy("input/pattern_rec2020_f32.npy"),
            npy("identity/expected_rec2020_f32.npy"),
            "kotlin_identity_reference_f32",
            Tolerance(0.0, 0.0),
        )
    }
}
