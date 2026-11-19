package dev.mark.lineover.feature.export

import android.media.MediaCodecList
import android.media.MediaFormat
import dev.mark.lineover.core.model.ExportFormat

/**
 * Which formats this device can write (Stage 12). JPEG, PNG, WebP, TIFF and Ultra HDR always;
 * HEIF needs an HEVC (or HEIC) encoder, AVIF an AV1 encoder. Formats without an encoder are
 * hidden from the sheet (the brief's IF WRONG).
 */
data class ExportCapabilities(
    val formats: Set<ExportFormat>,
) {
    fun supports(format: ExportFormat) = format in formats

    companion object {
        val ALL = ExportCapabilities(ExportFormat.entries.toSet())

        private val ALWAYS = ExportFormat.entries.toSet() - ExportFormat.Heif - ExportFormat.Avif

        fun detect(): ExportCapabilities {
            val encoders =
                runCatching {
                    MediaCodecList(MediaCodecList.REGULAR_CODECS)
                        .codecInfos
                        .filter { it.isEncoder }
                        .flatMap { it.supportedTypes.toList() }
                        .map { it.lowercase() }
                        .toSet()
                }.getOrDefault(emptySet())
            val formats = ALWAYS.toMutableSet()
            if (MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC in encoders || MediaFormat.MIMETYPE_VIDEO_HEVC in encoders) formats += ExportFormat.Heif
            if (MediaFormat.MIMETYPE_VIDEO_AV1 in encoders) formats += ExportFormat.Avif
            return ExportCapabilities(formats)
        }
    }
}
