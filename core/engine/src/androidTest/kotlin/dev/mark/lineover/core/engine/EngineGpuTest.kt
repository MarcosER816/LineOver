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
        inputPath: String = PATTERN,
        outputWidth: Int? = null,
        outputHeight: Int? = null,
    ): U8Image {
        val input = npy(inputPath)
        val width = outputWidth ?: input.width
        val height = outputHeight ?: input.height
        val out = ByteArray(width * height * 4)
        assertNull(engine.gpuSelfTest(input.data, input.width, input.height, graph, out))
        Log.i("LineOverEngineTest", "GPU path: halfFloat=${engine.stats().halfFloat}")
        return U8Image(width, height, 4, out)
    }

    /**
     * Stage 9: the geometry op through the canvas's own display pass (one bicubic resample of
     * the f16 texture: matrix, lens, chromatic aberration) vs OpenCV warpPerspective / remap
     * (tools/golden reference.geometry_warp), max 3/255.
     */
    private fun checkGeometry(
        name: String,
        input: String,
    ) {
        val expected = premultiplied8(npy("geometry/${name}_gpu_srgb_display_f32.npy"))
        val graph =
            testContext.assets
                .open("geometry/$name.graph.json")
                .bufferedReader()
                .use { it.readText() }
        val actual = gpu(graph, input, expected.width, expected.height)
        GoldenAssert.assertMatches(actual, expected, "gpu_geometry_${name}_srgb8", Tolerance.GPU_VS_CPU, diffDir)
    }

    @Test
    fun gpuRotationMatchesOpenCv() = checkGeometry("rotate", PATTERN)

    @Test
    fun gpuPerspectiveMatchesOpenCvWarpPerspective() = checkGeometry("perspective", SCENE)

    @Test
    fun gpuFlipRotateCropMatchesOpenCv() = checkGeometry("flip_rotate_crop", SCENE)

    @Test
    fun gpuLensDistortionMatchesOpenCvRemap() = checkGeometry("lens", SCENE)

    @Test
    fun gpuChromaticAberrationMatchesOpenCvRemap() = checkGeometry("chromatic", CHART)

    /** Lens vignetting correction (first setting: +50, midpoint 50) in source space. */
    @Test
    fun gpuVignettingMatchesTheReference() {
        val input = npy(SCENE)
        val w = input.width.toDouble()
        val h = input.height.toDouble()
        val op =
            """{"op":"geometry","sourceWidth":${input.width},"sourceHeight":${input.height},"outputWidth":${input.width},""" +
                """"outputHeight":${input.height},"k1":0.0,"k2":0.0,"caRed":0.0,"caBlue":0.0,"vignette":50.0,"vignetteMid":50.0,""" +
                """"matrix":[$w,0.0,0.0,0.0,$h,0.0,0.0,0.0,1.0]}"""
        val graph = """{"schemaVersion":1,"layers":[{"id":"background","ops":[$op]}]}"""
        val expected = premultiplied8(npy("geometry/vignetting_gpu_srgb_display_f32.npy"))
        GoldenAssert.assertMatches(gpu(graph, SCENE), expected, "gpu_geometry_vignetting_srgb8", Tolerance.GPU_VS_CPU, diffDir)
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

    /** Stage 6: each Color op (middle golden setting), GPU vs the colour-science / OKLab reference. */
    private fun checkColor(
        name: String,
        op: String,
        input: String,
    ) {
        val expected = premultiplied8(npy("colorops/${name}_gpu_srgb_display_f32.npy"))
        val graph = """{"schemaVersion":1,"layers":[{"id":"background","ops":[$op]}]}"""
        GoldenAssert.assertMatches(gpu(graph, input), expected, "gpu_color_${name}_srgb8", Tolerance.GPU_VS_CPU, diffDir)
    }

    @Test
    fun gpuWhiteBalanceMatchesTheReference() =
        checkColor("white_balance", """{"op":"white_balance","temperature":9000.0,"tint":40.0}""", SCENE)

    @Test
    fun gpuVibranceMatchesTheReference() = checkColor("vibrance", """{"op":"vibrance","amount":40.0}""", CHART)

    @Test
    fun gpuSaturationMatchesTheReference() = checkColor("saturation", """{"op":"saturation","amount":-30.0}""", CHART)

    @Test
    fun gpuHslMixerMatchesTheReference() =
        checkColor(
            "hsl_mixer",
            """{"op":"hsl_mixer","hue":[0,0,0,0,0,0,0,0],"saturation":[-100,-100,-100,-100,-100,100,0,0],""" +
                """"luminance":[0,0,0,0,50,-60,0,0]}""",
            CHART,
        )

    @Test
    fun gpuInvertMatchesTheReference() = checkColor("invert", """{"op":"invert"}""", SCENE)

    @Test
    fun gpuPosterizeMatchesTheReference() = checkColor("posterize", """{"op":"posterize","levels":12.0}""", CHART)

    @Test
    fun gpuThresholdMatchesTheReference() = checkColor("threshold", """{"op":"threshold","level":0.5}""", SCENE)

    /** Stage 7: each curves / grading op (middle golden setting), GPU vs the reference. */
    private fun checkGrade(
        name: String,
        op: String,
        input: String,
    ) {
        val expected = premultiplied8(npy("grade/${name}_gpu_srgb_display_f32.npy"))
        val graph = """{"schemaVersion":1,"layers":[{"id":"background","ops":[$op]}]}"""
        GoldenAssert.assertMatches(gpu(graph, input), expected, "gpu_grade_${name}_srgb8", Tolerance.GPU_VS_CPU, diffDir)
    }

    @Test
    fun gpuCurvesMatchTheReference() =
        checkGrade(
            "curves",
            """{"op":"curves","free":0.0,"rgb":[],"red":[0,0.05,0.5,0.56,1,1],"green":[],"blue":[0,0,0.5,0.44,1,0.95],""" +
                """"luma":[0,0,0.4,0.5,1,1]}""",
            SCENE,
        )

    @Test
    fun gpuParametricCurveMatchesTheReference() =
        checkGrade(
            "parametric_curve",
            """{"op":"parametric_curve","highlights":-60.0,"lights":-20.0,"darks":30.0,"shadows":50.0,""" +
                """"split1":0.25,"split2":0.5,"split3":0.75}""",
            SCENE,
        )

    @Test
    fun gpuLevelsMatchTheReference() =
        checkGrade(
            "levels",
            """{"op":"levels","values":[0,1,1,0,1,0.1,1,1,0,1,0,1,1,0,1,0,0.8,0.85,0.05,1]}""",
            SCENE,
        )

    @Test
    fun gpuColorGradingMatchesTheReference() =
        checkGrade(
            "color_grading",
            """{"op":"color_grading","blending":80.0,"balance":30.0,"shadows":[180,80,-20],"midtones":[300,30,10],""" +
                """"highlights":[30,70,20],"global":[90,20,-10]}""",
            CHART,
        )

    @Test
    fun gpuColorBalanceMatchesTheReference() =
        checkGrade(
            "color_balance",
            """{"op":"color_balance","preserve":0.0,"shadows":[0,50,0],"midtones":[60,-40,20],"highlights":[0,0,0]}""",
            SCENE,
        )

    @Test
    fun gpuSelectiveColorMatchesTheReference() =
        checkGrade(
            "selective_color",
            """{"op":"selective_color","relative":1.0,"values":[-40,10,20,0,0,0,-30,0,0,20,0,10,30,0,0,0,0,0,40,-10,""" +
                """0,0,0,0,0,0,0,-20,10,0,0,0,0,0,0,20]}""",
            CHART,
        )

    @Test
    fun gpuChannelMixerMatchesTheReference() =
        checkGrade(
            "channel_mixer",
            """{"op":"channel_mixer","monochrome":0.0,"matrix":[80,30,-10,10,90,0,0,-20,120],"constant":[5,0,-5]}""",
            CHART,
        )

    @Test
    fun gpuBlackAndWhiteMatchesTheReference() =
        checkGrade("black_and_white", """{"op":"black_and_white","tintHue":0.0,"tintSaturation":0.0,"mix":[40,60,-20,-30,-60,20]}""", CHART)

    @Test
    fun gpuPhotoFilterMatchesTheReference() =
        checkGrade("photo_filter", """{"op":"photo_filter","hue":250.0,"saturation":60.0,"density":60.0,"preserve":0.0}""", CHART)

    @Test
    fun gpuGradientMapMatchesTheReference() =
        checkGrade("gradient_map", """{"op":"gradient_map","stops":[0,0.2,0.02,-0.08,0.5,0.6,0.08,0.02,1,0.97,-0.01,0.06]}""", CHART)

    @Test
    fun gpuCalibrationMatchesTheReference() =
        checkGrade("calibration", """{"op":"calibration","hue":[0,-30,40],"saturation":[30,20,-20]}""", CHART)

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
        val path = if (engine.stats().histogramSupported) "GLES 3.1 compute" else "GLES 3.0 float readback + CPU"
        Log.i("LineOverEngineTest", "histogram ($path) vs numpy: max |diff| $worst count, $differing of 1024 bins differ")
        assertTrue("max |diff| per bin $worst > 1", worst <= 1)
    }

    private companion object {
        const val PATTERN = "input/pattern_rec2020_f32.npy"
        const val SCENE = "input/scene_rec2020_f32.npy"
        const val CHART = "input/colors_rec2020_f32.npy"
        const val IDENTITY = """{"schemaVersion":1,"layers":[{"id":"background","ops":[{"op":"identity"}]}]}"""
        const val EXPOSURE_MINUS_1 = """{"schemaVersion":1,"layers":[{"id":"background","ops":[{"op":"exposure_test","stops":-1.0}]}]}"""
    }
}
