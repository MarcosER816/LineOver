package dev.mark.lineover.core.io.project

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** Thumbnail geometry, free of Android so it can be unit-tested. */
object ThumbnailMath {
    const val MAX_SIDE = 512

    /** Size that fits within [maxSide] keeping the aspect ratio; never upscales. */
    fun targetSize(
        width: Int,
        height: Int,
        maxSide: Int = MAX_SIDE,
    ): Pair<Int, Int> {
        require(width > 0 && height > 0) { "image size must be positive" }
        val longest = max(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toDouble() / longest
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }
}

/**
 * Writes JPEG thumbnails. The image is decoded directly at thumbnail size
 * (ImageDecoder.setTargetSize), so a full-size bitmap is never allocated. Blocking:
 * call from a background dispatcher only (rule R6).
 */
internal class Thumbnailer(
    private val resolver: ContentResolver,
) {
    /** @return true when [out] was written; false when the format cannot be decoded (e.g. TIFF). */
    fun write(
        source: Uri,
        out: File,
    ): Boolean {
        val bitmap = decode(source) ?: return false
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, "${out.name}.tmp")
        try {
            val compressed = tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            return compressed && tmp.renameTo(out)
        } finally {
            bitmap.recycle()
            tmp.delete()
        }
    }

    /** Decodes [source] directly at thumbnail size, or null when it cannot be decoded. */
    private fun decode(source: Uri): Bitmap? =
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, source)) { decoder, info, _ ->
                val (w, h) = ThumbnailMath.targetSize(info.size.width, info.size.height)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        const val JPEG_QUALITY = 85
    }
}
