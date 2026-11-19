package dev.mark.lineover.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ExportSettingsTest {
    @Test
    fun validatedMakesEveryCombinationWritable() {
        // Rec. 2020 needs 16 bits: an 8-bit JPEG falls back to Display P3.
        assertEquals(ExportColorSpace.DisplayP3, ExportSettings(colorSpace = ExportColorSpace.Rec2020).validated().colorSpace)
        // PNG 16-bit keeps it.
        val png = ExportSettings(format = ExportFormat.Png, bits = 16, colorSpace = ExportColorSpace.Rec2020).validated()
        assertEquals(ExportColorSpace.Rec2020, png.colorSpace)
        assertEquals(16, png.bits)
        // PNG 8-bit cannot be Rec. 2020.
        assertEquals(ExportColorSpace.DisplayP3, png.copy(bits = 8).validated().colorSpace)
        // JPEG is 8-bit only; HEIF / AVIF are sRGB only.
        assertEquals(8, ExportSettings(bits = 16).validated().bits)
        assertEquals(ExportColorSpace.Srgb, ExportSettings(format = ExportFormat.Heif).validated().colorSpace)
        assertEquals(ExportColorSpace.Srgb, ExportSettings(format = ExportFormat.Avif).validated().colorSpace)
        assertEquals(1, ExportSettings(quality = -5).validated().quality)
        assertEquals(100, ExportSettings(quality = 400).validated().quality)
    }

    @Test
    fun outputSizeFollowsTheResizeModeAndNeverEnlarges() {
        val w = 8160
        val h = 6144
        assertEquals(w to h, ExportSettings().outputSize(w, h))
        assertEquals(2048 to 1542, ExportSettings(resizeMode = ResizeMode.LongEdge, resizeValue = 2048f).outputSize(w, h))
        assertEquals(1542 to 2048, ExportSettings(resizeMode = ResizeMode.LongEdge, resizeValue = 2048f).outputSize(h, w))
        assertEquals(4080 to 3072, ExportSettings(resizeMode = ResizeMode.Percent, resizeValue = 50f).outputSize(w, h))
        val (mw, mh) = ExportSettings(resizeMode = ResizeMode.Megapixels, resizeValue = 12f).outputSize(w, h)
        assertTrue(abs(mw.toLong() * mh - 12_000_000L) < 12_000)
        // Larger than the photo: the photo's size.
        assertEquals(800 to 600, ExportSettings(resizeMode = ResizeMode.LongEdge, resizeValue = 4096f).outputSize(800, 600))
        assertEquals(800 to 600, ExportSettings(resizeMode = ResizeMode.Megapixels, resizeValue = 50f).outputSize(800, 600))
    }

    @Test
    fun settingsSurviveARoundTripAndBadTextGivesTheDefaults() {
        val s =
            ExportSettings(
                format = ExportFormat.Tiff,
                bits = 16,
                colorSpace = ExportColorSpace.Rec2020,
                resizeMode = ResizeMode.Percent,
                resizeValue = 25f,
                sharpen = OutputSharpen.High,
                metadata = MetadataMode.CopyrightOnly,
                removeLocation = false,
                watermark = WatermarkSettings(enabled = true, text = "Hi", position = WatermarkPosition.TopLeft, opacity = 40f),
            )
        assertEquals(s, ExportSettings.decode(ExportSettings.encode(s)))
        assertEquals(ExportSettings(), ExportSettings.decode("{not json"))
        assertEquals(ExportSettings(), ExportSettings.decode(null))
        // Defaults (the brief's questions, R12): JPEG 90, Display P3, location removed.
        assertEquals(ExportFormat.Jpeg, ExportSettings.QUICK.format)
        assertEquals(90, ExportSettings.QUICK.quality)
        assertTrue(ExportSettings.QUICK.removeLocation)
    }

    @Test
    fun fileNamesUseThePhotoNameAndTheFormatExtension() {
        assertEquals("IMG_1234_LineOver.jpg", ExportSettings().fileName("IMG_1234.jpg"))
        assertEquals("a_b_LineOver.tif", ExportSettings(format = ExportFormat.Tiff).fileName("a/b.heic"))
        assertEquals("LineOver_LineOver.png", ExportSettings(format = ExportFormat.Png).fileName(""))
    }
}

class ExportEstimateTest {
    private val sample = ExportEstimate.Sample(jpeg = 0.6f, png = 2f, webp = 0.4f, webpLossless = 1.6f)

    @Test
    fun tiffIsExactAndSixteenBitsDoubleIt() {
        val tiff8 = ExportEstimate.bytes(ExportSettings(format = ExportFormat.Tiff), 4000, 3000, sample)
        val tiff16 = ExportEstimate.bytes(ExportSettings(format = ExportFormat.Tiff, bits = 16).validated(), 4000, 3000, sample)
        assertEquals(36_000_000L + 8_192, tiff8)
        assertEquals(72_000_000L + 8_192, tiff16)
        val png8 = ExportEstimate.bytes(ExportSettings(format = ExportFormat.Png), 4000, 3000, sample)
        val png16 = ExportEstimate.bytes(ExportSettings(format = ExportFormat.Png, bits = 16).validated(), 4000, 3000, sample)
        assertEquals(2 * png8, png16)
    }

    @Test
    fun lossyFormatsAreSmallerThanJpegAndScaleWithPixels() {
        val jpeg = ExportEstimate.bytes(ExportSettings(), 4000, 3000, sample)
        assertTrue(ExportEstimate.bytes(ExportSettings(format = ExportFormat.Heif), 4000, 3000, sample) < jpeg)
        assertTrue(ExportEstimate.bytes(ExportSettings(format = ExportFormat.Avif), 4000, 3000, sample) < jpeg)
        assertTrue(ExportEstimate.bytes(ExportSettings(format = ExportFormat.UltraHdr), 4000, 3000, sample) > jpeg)
        assertEquals(jpeg / 4, ExportEstimate.bytes(ExportSettings(), 2000, 1500, sample), 2)
        assertEquals("4.0 MB", ExportEstimate.label(jpeg)) // 12 MP x 0.6 B/px x 0.55
        assertEquals("640 KB", ExportEstimate.label(640_000))
    }

    private fun assertEquals(
        expected: Long,
        actual: Long,
        slack: Long,
    ) = assertTrue("$actual vs $expected", abs(actual - expected) <= slack)
}
