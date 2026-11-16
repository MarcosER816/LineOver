package dev.mark.lineover.core.engine.testing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Tests of the test helper itself: tolerances, heatmap output, PNG round trip. */
class GoldenAssertTest {
    // Failing comparisons made on purpose write their heatmaps here, not into the
    // directory that CI uploads as real golden diffs.
    @get:Rule
    val scratch = TemporaryFolder()

    private fun gradient(
        width: Int = 16,
        height: Int = 8,
    ): U8Image {
        val data = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = (y * width + x) * 4
                data[i] = (x * 255 / (width - 1)).toByte()
                data[i + 1] = (y * 255 / (height - 1)).toByte()
                data[i + 2] = ((x * 7 + y * 13) % 256).toByte()
                data[i + 3] = (255 - x).toByte()
            }
        }
        return U8Image(width, height, 4, data)
    }

    private fun U8Image.mapSamples(transform: (Int) -> Int) =
        U8Image(width, height, channels, ByteArray(data.size) { transform(data[it].toInt() and 0xFF).toByte() })

    private fun U8Image.withSample(
        index: Int,
        value: Int,
    ) = U8Image(width, height, channels, data.copyOf().also { it[index] = value.toByte() })

    @Test
    fun identicalImagesPass() {
        val result = GoldenAssert.compare(gradient(), gradient(), "identical", diffDir = scratch.root)

        assertTrue(result.passed)
        assertEquals(0.0, result.maxAbs, 0.0)
        assertNull(result.heatmap)
    }

    @Test
    fun u8MaxToleranceIsTwoLevels() {
        val expected = gradient()
        val sample = expected.data[5].toInt() and 0xFF

        assertTrue(GoldenAssert.compare(expected.withSample(5, sample + 2), expected, "two", diffDir = scratch.root).passed)
        val result = GoldenAssert.compare(expected.withSample(5, sample + 3), expected, "three", diffDir = scratch.root)
        assertFalse(result.passed)
        assertEquals(3.0 / 255.0, result.maxAbs, 1e-12)
    }

    @Test
    fun u8MeanToleranceIsHalfALevel() {
        val expected = gradient()
        val actual = expected.mapSamples { if (it < 255) it + 1 else it - 1 }

        val result = GoldenAssert.compare(actual, expected, "mean", diffDir = scratch.root)

        assertFalse(result.passed)
        assertTrue(result.maxAbs <= Tolerance.U8.maxAbs)
    }

    @Test
    fun gpuToleranceIsThreeLevels() {
        val expected = gradient()
        val offByThree = expected.mapSamples { if (it < 250) it + 3 else it - 3 }
        val first = expected.data[0].toInt() and 0xFF

        assertTrue(GoldenAssert.compare(offByThree, expected, "gpu3", Tolerance.GPU_VS_CPU, scratch.root).passed)
        assertFalse(
            GoldenAssert.compare(expected.withSample(0, first + 4), expected, "gpu4", Tolerance.GPU_VS_CPU, scratch.root).passed,
        )
    }

    @Test
    fun floatToleranceAndNan() {
        val expected = F32Image(2, 1, 3, floatArrayOf(0f, 0.5f, 1f, 2f, 4f, 8f))

        fun with(
            index: Int,
            value: Float,
        ) = F32Image(2, 1, 3, expected.data.copyOf().also { it[index] = value })

        assertTrue(GoldenAssert.compare(with(4, 4.0005f), expected, "close", diffDir = scratch.root).passed)
        assertFalse(GoldenAssert.compare(with(4, 4.002f), expected, "far", diffDir = scratch.root).passed)
        assertFalse(GoldenAssert.compare(with(1, Float.NaN), expected, "nan", diffDir = scratch.root).passed)
    }

    @Test
    fun shapeMismatchFails() {
        assertFalse(GoldenAssert.compare(gradient(16, 8), gradient(8, 16), "shape", diffDir = scratch.root).passed)
    }

    @Test
    fun failureWritesHeatmapPng() {
        val expected = gradient()
        val actual = expected.withSample(0, (expected.data[0].toInt() and 0xFF) + 40)

        val result = GoldenAssert.compare(actual, expected, "heatmap", diffDir = scratch.root)

        assertFalse(result.passed)
        val file = assertNotNull(result.heatmap).let { result.heatmap!! }
        assertTrue(file.isFile)
        val heatmap = file.inputStream().use(PngIo::read)
        assertEquals(listOf(16, 8, 3), listOf(heatmap.width, heatmap.height, heatmap.channels))
        assertEquals("failing pixel is hot", 255, heatmap.data[0].toInt() and 0xFF)
        assertArrayEquals("identical pixel is black", byteArrayOf(0, 0, 0), heatmap.data.copyOfRange(3, 6))
    }

    @Test
    fun assertMatchesThrowsWithSummary() {
        val expected = gradient()
        val actual = expected.withSample(0, (expected.data[0].toInt() and 0xFF) + 40)

        val error =
            assertThrows(AssertionError::class.java) {
                GoldenAssert.assertMatches(actual, expected, "throws", diffDir = scratch.root)
            }

        assertTrue(error.message, error.message!!.contains("throws: max |diff|"))
    }

    @Test
    fun pngRoundTripIsLossless() {
        val image = gradient(33, 17)

        val bytes = ByteArrayOutputStream().also { PngIo.write(image, it) }.toByteArray()
        val decoded = PngIo.read(ByteArrayInputStream(bytes))

        assertEquals(listOf(33, 17, 4), listOf(decoded.width, decoded.height, decoded.channels))
        assertArrayEquals(image.data, decoded.data)
    }
}
