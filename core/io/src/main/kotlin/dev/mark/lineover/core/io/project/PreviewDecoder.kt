package dev.mark.lineover.core.io.project

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.IOException

/**
 * The canvas preview of a project: a bitmap whose long edge is at most
 * [PreviewDecoder.MAX_EDGE] px, plus the size of the original it stands for (so 100 %
 * zoom means one original pixel per screen pixel).
 */
class PreviewImage(
    val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int,
)

/**
 * Decodes previews straight at their target size with ImageDecoder (a 50 MP photo never
 * becomes a 200 MB bitmap). Software allocation, because the bitmap is uploaded to a GL
 * texture; sRGB until colour management arrives in Stage 4. Blocking: background only.
 */
internal class PreviewDecoder(
    private val resolver: ContentResolver,
) {
    fun decode(
        source: Uri,
        maxEdge: Int,
    ): PreviewImage? {
        var width = 0
        var height = 0
        val bitmap =
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, source)) { decoder, info, _ ->
                    width = info.size.width
                    height = info.size.height
                    val (w, h) = ThumbnailMath.targetSize(width, height, maxEdge)
                    decoder.setTargetSize(w, h)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                }
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: SecurityException) {
                null
            } ?: return null
        return PreviewImage(bitmap, width, height)
    }

    companion object {
        /** Long edge of the interactive preview (ARCHITECTURE: 2048-px preview proxy). */
        const val MAX_EDGE = 2048
    }
}
