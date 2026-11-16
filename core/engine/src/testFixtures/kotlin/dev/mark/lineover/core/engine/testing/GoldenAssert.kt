package dev.mark.lineover.core.engine.testing

import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Limits on the absolute per-sample difference, in normalised units (1.0 == 255/255).
 * Mirrors `lo::testing::Tolerance` of the C++ helpers.
 */
data class Tolerance(
    val maxAbs: Double,
    val meanAbs: Double = Double.POSITIVE_INFINITY,
) {
    companion object {
        private const val U8_LEVELS = 255.0

        /** 8-bit results: max |diff| <= 2/255 and mean |diff| <= 0.5/255. */
        val U8 = Tolerance(maxAbs = 2.0 / U8_LEVELS, meanAbs = 0.5 / U8_LEVELS)

        /** Float operations: max |diff| <= 1e-3. */
        val FLOAT = Tolerance(maxAbs = 1e-3)

        /** GPU result against the CPU reference: max |diff| <= 3/255. */
        val GPU_VS_CPU = Tolerance(maxAbs = 3.0 / U8_LEVELS)
    }
}

/** Outcome of a comparison; [heatmap] is set only when it failed. */
data class GoldenResult(
    val passed: Boolean,
    val maxAbs: Double,
    val meanAbs: Double,
    val message: String,
    val heatmap: File? = null,
)

/**
 * Compares an image with a golden reference within a [Tolerance]. A failed
 * comparison writes `<name>_diff.png`, a heatmap: black = identical, blue =
 * within tolerance, red -> yellow -> white = 1x .. 4x the limit and beyond.
 */
object GoldenAssert {
    private const val U8_SCALE = 1.0 / 255.0
    private const val BYTE_MASK = 0xFF
    private const val HEAT_CHANNELS = 3
    private const val HEAT_MAX = 255.0
    private const val HEAT_FAIL_SPAN = 3.0

    /**
     * Where heatmaps go: the `lineover.goldenDiffDir` system property (set by the
     * Gradle build for unit tests) or a directory below java.io.tmpdir.
     */
    fun defaultDiffDir(): File =
        File(System.getProperty("lineover.goldenDiffDir") ?: File(System.getProperty("java.io.tmpdir"), "golden-diffs").path)

    fun compare(
        actual: U8Image,
        expected: U8Image,
        name: String,
        tolerance: Tolerance = Tolerance.U8,
        diffDir: File = defaultDiffDir(),
    ): GoldenResult {
        shapeMismatch(name, actual.width, actual.height, actual.channels, expected.width, expected.height, expected.channels)
            ?.let { return it }
        return compareSamples(name, actual.width, actual.height, actual.channels, tolerance, diffDir) { i ->
            abs((actual.data[i].toInt() and BYTE_MASK) - (expected.data[i].toInt() and BYTE_MASK)) * U8_SCALE
        }
    }

    fun compare(
        actual: F32Image,
        expected: F32Image,
        name: String,
        tolerance: Tolerance = Tolerance.FLOAT,
        diffDir: File = defaultDiffDir(),
    ): GoldenResult {
        shapeMismatch(name, actual.width, actual.height, actual.channels, expected.width, expected.height, expected.channels)
            ?.let { return it }
        return compareSamples(name, actual.width, actual.height, actual.channels, tolerance, diffDir) { i ->
            val diff = abs(actual.data[i].toDouble() - expected.data[i].toDouble())
            // A NaN on either side must fail, never pass silently.
            if (diff.isNaN()) Double.POSITIVE_INFINITY else diff
        }
    }

    fun assertMatches(
        actual: U8Image,
        expected: U8Image,
        name: String,
        tolerance: Tolerance = Tolerance.U8,
        diffDir: File = defaultDiffDir(),
    ) = failUnlessPassed(compare(actual, expected, name, tolerance, diffDir))

    fun assertMatches(
        actual: F32Image,
        expected: F32Image,
        name: String,
        tolerance: Tolerance = Tolerance.FLOAT,
        diffDir: File = defaultDiffDir(),
    ) = failUnlessPassed(compare(actual, expected, name, tolerance, diffDir))

    private fun failUnlessPassed(result: GoldenResult) {
        if (!result.passed) throw AssertionError("Golden mismatch: ${result.message}")
    }

    @Suppress("LongParameterList")
    private fun shapeMismatch(
        name: String,
        actualWidth: Int,
        actualHeight: Int,
        actualChannels: Int,
        expectedWidth: Int,
        expectedHeight: Int,
        expectedChannels: Int,
    ): GoldenResult? {
        if (actualWidth == expectedWidth && actualHeight == expectedHeight && actualChannels == expectedChannels) return null
        return GoldenResult(
            passed = false,
            maxAbs = Double.POSITIVE_INFINITY,
            meanAbs = Double.POSITIVE_INFINITY,
            message =
                "$name: shape mismatch, actual ${actualWidth}x${actualHeight}x$actualChannels " +
                    "vs expected ${expectedWidth}x${expectedHeight}x$expectedChannels",
        )
    }

    @Suppress("LongParameterList")
    private fun compareSamples(
        name: String,
        width: Int,
        height: Int,
        channels: Int,
        tolerance: Tolerance,
        diffDir: File,
        sampleError: (Int) -> Double,
    ): GoldenResult {
        val pixels = width * height
        val pixelError = DoubleArray(pixels)
        var sum = 0.0
        var maximum = 0.0
        for (p in 0 until pixels) {
            for (c in 0 until channels) {
                val error = sampleError(p * channels + c)
                sum += error
                pixelError[p] = max(pixelError[p], error)
            }
            maximum = max(maximum, pixelError[p])
        }
        val mean = if (pixels * channels > 0) sum / (pixels * channels) else 0.0
        val passed = maximum <= tolerance.maxAbs && mean <= tolerance.meanAbs
        val summary =
            "$name: max |diff| $maximum (limit ${tolerance.maxAbs}), mean |diff| $mean (limit ${tolerance.meanAbs})"
        if (passed) return GoldenResult(true, maximum, mean, summary)

        val heat = ByteArray(pixels * HEAT_CHANNELS)
        for (p in 0 until pixels) {
            heatColor(pixelError[p] / tolerance.maxAbs, heat, p * HEAT_CHANNELS)
        }
        diffDir.mkdirs()
        val heatmap = File(diffDir, "${name}_diff.png")
        heatmap.outputStream().use { PngIo.write(U8Image(width, height, HEAT_CHANNELS, heat), it) }
        return GoldenResult(false, maximum, mean, "$summary; heatmap: ${heatmap.path}", heatmap)
    }

    /** Same colour ramp as the C++ helper; [ratio] is the error as a multiple of the limit. */
    @Suppress("MagicNumber")
    private fun heatColor(
        ratio: Double,
        out: ByteArray,
        offset: Int,
    ) {
        fun channel(value: Double) = (value.coerceIn(0.0, 1.0) * HEAT_MAX).roundToInt().toByte()
        if (ratio <= 0.0) return // identical pixels stay black
        if (ratio <= 1.0) {
            out[offset + 1] = channel(0.3 * ratio)
            out[offset + 2] = channel(0.35 + 0.65 * ratio)
        } else {
            val t = min((ratio - 1.0) / HEAT_FAIL_SPAN, 1.0)
            out[offset] = channel(1.0)
            out[offset + 1] = channel(t * 2.0)
            out[offset + 2] = channel(t * 2.0 - 1.0)
        }
    }
}
