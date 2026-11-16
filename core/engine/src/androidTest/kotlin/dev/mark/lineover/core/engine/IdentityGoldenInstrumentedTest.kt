package dev.mark.lineover.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mark.lineover.core.engine.testing.F32Image
import dev.mark.lineover.core.engine.testing.GoldenAssert
import dev.mark.lineover.core.engine.testing.NpyIo
import dev.mark.lineover.core.engine.testing.PngIo
import dev.mark.lineover.core.engine.testing.Tolerance
import dev.mark.lineover.core.engine.testing.U8Image
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The native identity op, called through JNI, against the Python reference (testdata/golden). */
@RunWith(AndroidJUnit4::class)
class IdentityGoldenInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val diffDir = File(context.cacheDir, "golden-diffs")

    private fun png(path: String) = context.assets.open(path).use(PngIo::read)

    private fun npy(path: String) = context.assets.open(path).use(NpyIo::readF32)

    @Test
    fun engineReportsVersion() {
        assertTrue(LoEngine.version().matches(Regex("""\d+\.\d+\.\d+""")))
    }

    @Test
    fun identityU8MatchesPythonReference() {
        val input = png("input/pattern_rgba8.png")

        val actual = U8Image(input.width, input.height, input.channels, LoEngine.identity(input.data))

        GoldenAssert.assertMatches(actual, png("identity/expected_rgba8.png"), "identity_rgba8", Tolerance.U8, diffDir)
    }

    @Test
    fun identityF32MatchesPythonReference() {
        val input = npy("input/pattern_rec2020_f32.npy")

        val actual = F32Image(input.width, input.height, input.channels, LoEngine.identity(input.data))

        GoldenAssert.assertMatches(
            actual,
            npy("identity/expected_rec2020_f32.npy"),
            "identity_rec2020_f32",
            Tolerance.FLOAT,
            diffDir,
        )
    }
}
