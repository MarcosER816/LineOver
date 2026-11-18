package dev.mark.lineover.core.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mark.lineover.core.engine.testing.F32Image
import dev.mark.lineover.core.engine.testing.GoldenAssert
import dev.mark.lineover.core.engine.testing.NpyIo
import dev.mark.lineover.core.engine.testing.Tolerance
import dev.mark.lineover.core.engine.testing.U8Image
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

/**
 * The GPU path (op shader passes + the display transform, the same shaders the canvas
 * uses) against the reference (Python / colour-science, which ctest checks against the
 * C++ cpuRef within 1e-4): max |diff| <= 3/255.
 */
@RunWith(AndroidJUnit4::class)
class EngineGpuTest {
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val diffDir = File(appContext.cacheDir, "golden-diffs")
    private val cacheDir = File(appContext.cacheDir, "engine-gpu-test").apply { mkdirs() }
    private val engine = NativeEngine(cacheDir.path, 64L shl 20, 64L shl 20)

    @After
    fun close() {
        engine.close()
        cacheDir.deleteRecursively()
    }

    private fun npy(path: String) = testContext.assets.open(path).use(NpyIo::readF32)

    /** Straight 0..1 display values -> the premultiplied RGBA8 the GPU writes. */
    private fun premultiplied8(display: F32Image): U8Image {
        val out = ByteArray(display.data.size)
        for (i in 0 until display.data.size / 4) {
            val a = display.data[4 * i + 3]
            for (c in 0 until 3) out[4 * i + c] = (display.data[4 * i + c] * a * 255f).roundToInt().toByte()
            out[4 * i + 3] = (a * 255f).roundToInt().toByte()
        }
        return U8Image(display.width, display.height, 4, out)
    }

    private fun gpu(
        graph: String,
        inputPath: String = "input/pattern_rec2020_f32.npy",
    ): U8Image {
        val input = npy(inputPath)
        val out = ByteArray(input.width * input.height * 4)
        assertNull(engine.gpuSelfTest(input.data, input.width, input.height, graph, out))
        Log.i("LineOverEngineTest", "GPU path: halfFloat=${engine.stats().halfFloat}")
        return U8Image(input.width, input.height, 4, out)
    }

    @Test
    fun gpuIdentityMatchesTheCpuReference() {
        val expected = premultiplied8(npy("color/working_to_srgb_display_f32.npy"))
        GoldenAssert.assertMatches(gpu(IDENTITY), expected, "gpu_identity_srgb8", Tolerance.GPU_VS_CPU, diffDir)
    }

    @Test
    fun gpuExposureTestMatchesTheCpuReference() {
        val expected = premultiplied8(npy("exposure_test/minus1_srgb_display_f32.npy"))
        GoldenAssert.assertMatches(gpu(EXPOSURE_MINUS_1), expected, "gpu_exposure_minus1_srgb8", Tolerance.GPU_VS_CPU, diffDir)
    }

    /** Stage 5: each Light op (middle golden setting) on the HDR scene, GPU vs reference. */
    private fun checkLight(
        name: String,
        op: String,
    ) {
        val expected = premultiplied8(npy("light/${name}_2_srgb_display_f32.npy"))
        val graph = """{"schemaVersion":1,"layers":[{"id":"background","ops":[$op]}]}"""
        GoldenAssert.assertMatches(gpu(graph, SCENE), expected, "gpu_${name}_srgb8", Tolerance.GPU_VS_CPU, diffDir)
    }

    @Test
    fun gpuExposureMatchesTheReference() = checkLight("exposure", """{"op":"exposure","ev":1.0}""")

    @Test
    fun gpuContrastMatchesTheReference() = checkLight("contrast", """{"op":"contrast","amount":40.0}""")

    @Test
    fun gpuBrightnessMatchesTheReference() = checkLight("brightness", """{"op":"brightness","amount":30.0}""")

    @Test
    fun gpuWhitesMatchesTheReference() = checkLight("whites", """{"op":"whites","amount":50.0}""")

    @Test
    fun gpuBlacksMatchesTheReference() = checkLight("blacks", """{"op":"blacks","amount":-30.0}""")

    @Test
    fun gpuHighlightsShadowsMatchesTheReference() =
        checkLight("highlights_shadows", """{"op":"highlights_shadows","highlights":0.0,"shadows":100.0}""")

    /** The compute-shader histogram against numpy (reference.histogram_srgb): within 1 count per bin. */
    @Test
    fun gpuHistogramMatchesNumpy() {
        val input = npy(SCENE)
        val bins = IntArray(Histogram.SIZE)
        assertNull(engine.gpuHistogramSelfTest(input.data, input.width, input.height, bins))
        val expected = npy("histogram/scene_srgb_hist_f32.npy") // (1, 256, 4): [bin][channel]
        var worst = 0
        var differing = 0
        for (c in 0 until Histogram.CHANNELS) {
            var total = 0L
            for (i in 0 until Histogram.BINS) {
                val diff = kotlin.math.abs(bins[c * Histogram.BINS + i] - expected.data[i * 4 + c].roundToInt())
                worst = maxOf(worst, diff)
                if (diff != 0) differing++
                total += bins[c * Histogram.BINS + i]
            }
            assertEquals("channel $c counts every pixel", (input.width * input.height).toLong(), total)
        }
        Log.i("LineOverEngineTest", "histogram vs numpy: max |diff| $worst count, $differing of 1024 bins differ")
        assertTrue("max |diff| per bin $worst > 1", worst <= 1)
    }

    private companion object {
        const val SCENE = "input/scene_rec2020_f32.npy"
        const val IDENTITY = """{"schemaVersion":1,"layers":[{"id":"background","ops":[{"op":"identity"}]}]}"""
        const val EXPOSURE_MINUS_1 = """{"schemaVersion":1,"layers":[{"id":"background","ops":[{"op":"exposure_test","stops":-1.0}]}]}"""
    }
}
