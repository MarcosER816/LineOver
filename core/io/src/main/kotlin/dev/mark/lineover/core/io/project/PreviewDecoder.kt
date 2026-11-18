package dev.mark.lineover.core.io.project

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Gainmap
import android.net.Uri
import dev.mark.lineover.core.io.source.SourceFormat
import dev.mark.lineover.core.io.source.SourceInfo
import dev.mark.lineover.core.io.source.SourceReader

/**
 * The canvas preview of a project: a bitmap whose long edge is at most
 * [PreviewDecoder.MAX_EDGE] px, plus the size of the original it stands for (so 100 %
 * zoom means one original pixel per screen pixel).
 *
 * @param format how [bitmap]'s pixels are encoded for the engine
 * @param gainmap the Ultra HDR gain map of the original, kept for HDR display / export
 * @param info the original's header (null in tests that build a preview by hand)
 */
class PreviewImage(
    val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int,
    val format: SourceFormat = SourceFormat.Srgb8,
    val gainmap: Gainmap? = null,
    val info: SourceInfo? = null,
)

/** Decodes previews through [SourceReader] (header, EXIF orientation, format, gain map). */
internal class PreviewDecoder(
    resolver: ContentResolver,
) {
    private val reader = SourceReader(resolver)

    fun decode(
        source: Uri,
        maxEdge: Int,
    ): PreviewImage? {
        val info = reader.info(source) ?: return null
        return reader.decodePreview(info, maxEdge)
    }

    companion object {
        /** Long edge of the interactive preview (ARCHITECTURE: 2048-px preview proxy). */
        const val MAX_EDGE = SourceReader.MAX_PREVIEW_EDGE
    }
}
