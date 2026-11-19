package dev.mark.lineover.core.io.export

import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mark.lineover.core.model.ExportColorSpace
import dev.mark.lineover.core.model.ExportSettings
import dev.mark.lineover.core.model.MetadataMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Stage 12: metadata of exported files (EXIF stripping), JPEG segment and WebP chunk surgery. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ExportFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A camera photo's EXIF: make, model, exposure, artist, copyright, a comment and a location. */
    private fun cameraJpeg(): ByteArray {
        val file = java.io.File.createTempFile("camera", ".jpg", folder.root)
        file.writeBytes(ExportMetadata.CARRIER)
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_MAKE, "Xiaomi")
            setAttribute(ExifInterface.TAG_MODEL, "POCO F7 Ultra")
            setAttribute(ExifInterface.TAG_F_NUMBER, "1.6")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:19 10:00:00")
            setAttribute(ExifInterface.TAG_ARTIST, "Mark")
            setAttribute(ExifInterface.TAG_USER_COMMENT, "harbour")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setLatLong(41.39, 2.17)
            saveAttributes()
        }
        return file.readBytes()
    }

    private fun exifOf(jpeg: ByteArray) = ExifInterface(ByteArrayInputStream(jpeg))

    /** What an exported JPEG carries: [settings]' block written into a JPEG that had the camera's EXIF. */
    private fun exported(settings: ExportSettings): ExifInterface {
        val source = exifOf(cameraJpeg())
        val tags = ExportMetadata.tags(source, settings, 4000, 3000, "LineOver 0.12.0")
        val block = ExportMetadata.exifBlock(tags, folder.newFolder())
        // The camera's own EXIF is in the file being rewritten: it must be replaced, not merged.
        return exifOf(JpegSegments.rewrite(cameraJpeg(), block, null))
    }

    @Test
    fun keepAllRemovesTheLocationByDefault() {
        val exif = exported(ExportSettings())
        assertNull(exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertEquals("Xiaomi", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("POCO F7 Ultra", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("1.6", exif.getAttribute(ExifInterface.TAG_F_NUMBER))
        assertEquals("Mark", exif.getAttribute(ExifInterface.TAG_ARTIST))
        // The pixels are upright: orientation is normal whatever the camera said.
        assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals("4000", exif.getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION))
        assertEquals("65535", exif.getAttribute(ExifInterface.TAG_COLOR_SPACE)) // Display P3 = uncalibrated
        assertEquals("LineOver 0.12.0", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        // No copyright in the photo: the setting's text, in ASCII as EXIF requires ("©" -> "(C)").
        assertEquals("(C) Mark", exif.getAttribute(ExifInterface.TAG_COPYRIGHT))
    }

    @Test
    fun keepAllWithLocationKeepsIt() {
        val exif = exported(ExportSettings(removeLocation = false, colorSpace = ExportColorSpace.Srgb))
        val latLong = exif.latLong
        assertNotNull(latLong)
        assertEquals(41.39, latLong!![0], 1e-4)
        assertEquals(2.17, latLong[1], 1e-4)
        assertEquals("1", exif.getAttribute(ExifInterface.TAG_COLOR_SPACE))
    }

    @Test
    fun copyrightOnlyStripsEverythingElse() {
        val exif = exported(ExportSettings(metadata = MetadataMode.CopyrightOnly, removeLocation = false))
        assertEquals("Mark", exif.getAttribute(ExifInterface.TAG_ARTIST))
        assertEquals("(C) Mark", exif.getAttribute(ExifInterface.TAG_COPYRIGHT))
        for (tag in ExportMetadata.CAMERA_TAGS + ExportMetadata.GPS_TAGS) {
            // ExifInterface always writes LightSource = 0 ("unknown") as a compatibility default.
            if (tag ==
                ExifInterface.TAG_LIGHT_SOURCE
            ) {
                assertEquals("0", exif.getAttribute(tag) ?: "0")
            } else {
                assertNull(tag, exif.getAttribute(tag))
            }
        }
        assertNull(exif.latLong)
    }

    @Test
    fun exifTextIsAscii() {
        assertEquals("(C) Jose Muller 2026", ExportMetadata.ascii("© José Müller 2026"))
        assertEquals("Mark (R)", ExportMetadata.ascii("Mark ®"))
        assertEquals("ab", ExportMetadata.ascii("a\u4e2db"))
    }

    @Test
    fun noneWritesNoExifAtAll() {
        val source = exifOf(cameraJpeg())
        val tags = ExportMetadata.tags(source, ExportSettings(metadata = MetadataMode.None, removeLocation = false), 10, 10, "LineOver")
        assertTrue(tags.isEmpty())
        assertNull(ExportMetadata.exifBlock(tags, folder.newFolder()))
        val rewritten = JpegSegments.rewrite(cameraJpeg(), null, null)
        assertNull(JpegSegments.exifOf(rewritten))
        val exif = exifOf(rewritten)
        assertNull(exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
    }

    /** SOI, APP0, DQT-ish segment, SOS, scan bytes, EOI, then a second JPEG an MPF entry points to. */
    private fun ultraHdrLike(withIcc: Boolean): Pair<ByteArray, Int> {
        val out = ByteArrayOutputStream()

        fun seg(
            marker: Int,
            payload: ByteArray,
        ) {
            out.write(0xFF)
            out.write(marker)
            out.write((payload.size + 2) shr 8)
            out.write((payload.size + 2) and 0xFF)
            out.write(payload)
        }
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        seg(0xE0, "JFIF\u0000\u0001\u0001\u0000\u0000\u0001\u0000\u0001\u0000\u0000".toByteArray(Charsets.ISO_8859_1))
        if (withIcc) seg(0xE2, "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(1, 1) + ByteArray(20) { 7 })
        // MPF: "MPF\0" + big-endian TIFF header; one value (the second image's offset) relative to the header.
        val mpfHeaderAt = out.size() + 4 + 4 // after marker+length, after "MPF\0"
        val mpf = ByteArrayOutputStream()
        mpf.write("MPF\u0000".toByteArray(Charsets.ISO_8859_1))
        mpf.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42))
        val offsetField = mpf.size()
        mpf.write(ByteArray(4)) // filled below
        seg(0xE2, mpf.toByteArray())
        seg(0xDB, ByteArray(65) { it.toByte() })
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2))
        out.write(ByteArray(300) { (it * 7).toByte() })
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        val second = out.size()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        val bytes = out.toByteArray()
        val rel = second - mpfHeaderAt
        val at = mpfHeaderAt - 4 + offsetField
        bytes[at] = (rel shr 24).toByte()
        bytes[at + 1] = (rel shr 16).toByte()
        bytes[at + 2] = (rel shr 8).toByte()
        bytes[at + 3] = rel.toByte()
        return bytes to second
    }

    @Test
    fun jpegRewriteKeepsTheScanTheGainMapImageAndTheMpfOffsets() {
        val (jpeg, second) = ultraHdrLike(withIcc = false)
        val exif = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0)
        val icc = ByteArray(300) { (it % 251).toByte() }
        val out = JpegSegments.rewrite(jpeg, exif, icc)
        val (segments, _) = JpegSegments.parse(out)
        assertEquals(0xE0, segments[0].marker) // JFIF stays first
        assertTrue(segments[1].isExif)
        assertTrue(segments[2].isIcc)
        assertArrayEquals(exif, JpegSegments.exifOf(out))
        assertArrayEquals(icc, JpegSegments.iccOf(out))
        // Everything from the MPF segment on is the original bytes shifted by the inserted length.
        val shift = out.size - jpeg.size
        val mpfAt =
            (0 until jpeg.size - 3).first {
                jpeg[it] == 'M'.code.toByte() && jpeg[it + 1] == 'P'.code.toByte() &&
                    jpeg[it + 2] == 'F'.code.toByte()
            }
        assertArrayEquals(jpeg.copyOfRange(mpfAt, jpeg.size), out.copyOfRange(mpfAt + shift, out.size))
        // The MPF offset (relative to its own header) still lands on the second image's SOI.
        val header = mpfAt + shift + 4
        val rel =
            ((out[header + 4].toInt() and 0xFF) shl 24) or ((out[header + 5].toInt() and 0xFF) shl 16) or
                ((out[header + 6].toInt() and 0xFF) shl 8) or (out[header + 7].toInt() and 0xFF)
        assertEquals(second + shift, header + rel)
        assertEquals(0xD8, out[header + rel + 1].toInt() and 0xFF)
    }

    @Test
    fun jpegRewriteKeepsTheEncodersIccProfile() {
        val (jpeg, _) = ultraHdrLike(withIcc = true)
        val out = JpegSegments.rewrite(jpeg, null, ByteArray(40) { 1 })
        assertEquals(1, JpegSegments.parse(out).first.count { it.isIcc })
        assertArrayEquals(ByteArray(20) { 7 }, JpegSegments.iccOf(out))
    }

    private fun vp8l(
        width: Int,
        height: Int,
    ): ByteArray {
        // VP8L: signature 0x2f, then 14-bit width-1, 14-bit height-1, alpha bit, version; payload odd-sized on purpose.
        val bits = (width - 1) or ((height - 1) shl 14)
        val data = byteArrayOf(0x2f, bits.toByte(), (bits shr 8).toByte(), (bits shr 16).toByte(), (bits shr 24).toByte(), 9, 9)
        val body = "WEBP".toByteArray() + "VP8L".toByteArray() + le32(data.size) + data + byteArrayOf(0)
        return "RIFF".toByteArray() + le32(body.size) + body
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    @Test
    fun webpRewriteMakesAnExtendedFileWithIccAndExif() {
        val icc = ByteArray(33) { 3 } // odd: needs a pad byte
        val exif = byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0, 8, 0, 0, 0)
        val out = WebpChunks.rewrite(vp8l(300, 200), 300, 200, icc, exif)
        val chunks = WebpChunks.parse(out)
        assertEquals(listOf("VP8X", "ICCP", "VP8L", "EXIF"), chunks.map { it.fourcc })
        val vp8x = chunks[0].data
        assertEquals(0x20 or 0x08, vp8x[0].toInt() and 0xFF)
        assertEquals(299, (vp8x[4].toInt() and 0xFF) or ((vp8x[5].toInt() and 0xFF) shl 8))
        assertEquals(199, (vp8x[7].toInt() and 0xFF) or ((vp8x[8].toInt() and 0xFF) shl 8))
        assertArrayEquals(icc, chunks[1].data)
        assertArrayEquals(exif, chunks[3].data)
        val riffSize = (out[4].toInt() and 0xFF) or ((out[5].toInt() and 0xFF) shl 8) or ((out[6].toInt() and 0xFF) shl 16)
        assertEquals(out.size - 8, riffSize)
        // Without either: still a valid extended file, no flags.
        val plain = WebpChunks.parse(WebpChunks.rewrite(vp8l(2, 2), 2, 2, null, null))
        assertEquals(listOf("VP8X", "VP8L"), plain.map { it.fourcc })
        assertFalse(plain[0].data[0].toInt() != 0)
    }
}
