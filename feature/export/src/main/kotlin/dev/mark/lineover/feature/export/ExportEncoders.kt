package dev.mark.lineover.feature.export

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.ParcelFileDescriptor
import androidx.heifwriter.AvifWriter
import androidx.heifwriter.HeifWriter
import dev.mark.lineover.core.engine.ExportImage
import dev.mark.lineover.core.io.export.ExportMetadata
import dev.mark.lineover.core.io.export.JpegSegments
import dev.mark.lineover.core.io.export.WebpChunks
import dev.mark.lineover.core.model.ExportFormat
import dev.mark.lineover.core.model.ExportSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Writes the processed export image to [File]s (Stage 12):
 * - PNG and TIFF: native writers, 8 or 16 bit straight from the floats, ICC embedded.
 * - JPEG, WebP, Ultra HDR: an 8-bit Bitmap in the target colour space through the platform
 *   encoder; then LineOver's EXIF and (when the encoder wrote none) its ICC profile are added.
 * - HEIF, AVIF: androidx.heifwriter (the device's HEVC / AV1 encoders), sRGB, EXIF item.
 */
object ExportEncoders {
    private const val HEIF_TIMEOUT_MS = 120_000L

    /** The EXIF block, the tags (TIFF text) and an Ultra HDR gain map. */
    class Metadata(
        val exif: ByteArray?,
        val tags: Map<String, String>,
        val gainmap: Gainmap?,
    )

    fun encode(
        image: ExportImage,
        settings: ExportSettings,
        file: File,
        metadata: Metadata,
    ) {
        val exif = metadata.exif
        val tags = metadata.tags
        val gainmap = metadata.gainmap
        file.parentFile?.mkdirs()
        when (settings.format) {
            ExportFormat.Png -> {
                withFd(file) { fd -> image.writePng(fd, settings.bits, settings.colorSpace, exif) }
            }

            ExportFormat.Tiff -> {
                withFd(file) { fd -> image.writeTiff(fd, settings.bits, settings.colorSpace, ExportMetadata.tiffText(tags)) }
            }

            ExportFormat.Jpeg, ExportFormat.UltraHdr -> {
                withBitmap(image, settings) { bitmap ->
                    if (settings.format == ExportFormat.UltraHdr) {
                        bitmap.gainmap = gainmap ?: throw IOException("This edit has no HDR highlights to keep: export a plain JPEG")
                    }
                    val bytes = compress(bitmap, Bitmap.CompressFormat.JPEG, settings.quality)
                    file.writeBytes(JpegSegments.rewrite(bytes, exif, ExportImage.iccProfile(settings.colorSpace)))
                }
            }

            ExportFormat.Webp -> {
                withBitmap(image, settings) { bitmap ->
                    val format = if (settings.webpLossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
                    val bytes = compress(bitmap, format, if (settings.webpLossless) LOSSLESS_EFFORT else settings.quality)
                    file.writeBytes(
                        WebpChunks.rewrite(bytes, bitmap.width, bitmap.height, ExportImage.iccProfile(settings.colorSpace), exif),
                    )
                }
            }

            ExportFormat.Heif, ExportFormat.Avif -> {
                withBitmap(image, settings) { bitmap -> writeHeif(settings, bitmap, file, exif) }
            }
        }
        if (!file.isFile || file.length() == 0L) throw IOException("the encoder wrote nothing")
    }

    /**
     * True when this device writes [format] (HEIF / AVIF) as a file its own decoder reads: a
     * 64 px image is encoded and decoded. An encoder alone is not enough (an emulator's AV1
     * encoder output was muxed as HEVC and could not be decoded).
     */
    @Suppress("TooGenericExceptionCaught") // any failure means "not on this device"
    fun probe(
        format: ExportFormat,
        dir: File,
    ): Boolean {
        dir.mkdirs()
        val file = File(dir, "probe.${format.extension}")
        val bitmap = Bitmap.createBitmap(PROBE_SIZE, PROBE_SIZE, Bitmap.Config.ARGB_8888)
        return try {
            // A gradient, so the encoder has something to code.
            for (y in 0 until PROBE_SIZE) {
                for (x in 0 until PROBE_SIZE) {
                    bitmap.setPixel(
                        x,
                        y,
                        android.graphics.Color.rgb(
                            x * MAX_CODE / PROBE_SIZE,
                            y * MAX_CODE / PROBE_SIZE,
                            MAX_CODE / 2,
                        ),
                    )
                }
            }
            writeHeif(ExportSettings(format = format), bitmap, file, null)
            val decoded = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file))
            decoded.width == PROBE_SIZE && decoded.height == PROBE_SIZE
        } catch (e: Exception) {
            android.util.Log.i("LineOverExport", "${format.label} is not usable here: $e")
            false
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    private const val PROBE_SIZE = 64
    private const val MAX_CODE = 255

    private fun writeHeif(
        settings: ExportSettings,
        bitmap: Bitmap,
        file: File,
        exif: ByteArray?,
    ) {
        // heifwriter hands this to MediaMuxer as muxer data; Android's MPEG-4 writer recognises an
        // EXIF block by its leading "Exif\0\0" and writes the item's TIFF-header offset itself.
        val item = exif?.let { EXIF_HEADER + it }
        if (settings.format == ExportFormat.Avif) {
            val writer =
                AvifWriter
                    .Builder(file.path, bitmap.width, bitmap.height, AvifWriter.INPUT_MODE_BITMAP)
                    .setQuality(settings.quality)
                    .setGridEnabled(true)
                    .build()
            writer.use {
                it.start()
                it.addBitmap(bitmap)
                item?.let { e -> it.addExifData(0, e, 0, e.size) }
                it.stop(HEIF_TIMEOUT_MS)
            }
        } else {
            val writer =
                HeifWriter
                    .Builder(file.path, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
                    .setQuality(settings.quality)
                    .setGridEnabled(true)
                    .build()
            writer.use {
                it.start()
                it.addBitmap(bitmap)
                item?.let { e -> it.addExifData(0, e, 0, e.size) }
                it.stop(HEIF_TIMEOUT_MS)
            }
        }
    }

    private inline fun withBitmap(
        image: ExportImage,
        settings: ExportSettings,
        use: (Bitmap) -> Unit,
    ) {
        val bitmap = image.toBitmap(settings.colorSpace)
        try {
            use(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun compress(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream(bitmap.width * bitmap.height / 2)
        if (!bitmap.compress(format, quality, out)) throw IOException("the $format encoder failed")
        return out.toByteArray()
    }

    private inline fun withFd(
        file: File,
        write: (Int) -> Unit,
    ) {
        ParcelFileDescriptor
            .open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
            .use { write(it.fd) }
    }

    private val EXIF_HEADER = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    private const val LOSSLESS_EFFORT = 100
}
