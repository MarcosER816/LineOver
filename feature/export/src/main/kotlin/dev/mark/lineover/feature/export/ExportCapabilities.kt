package dev.mark.lineover.feature.export

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import dev.mark.lineover.core.model.ExportFormat

/**
 * Which formats this device can write (Stage 12). JPEG, PNG, WebP, TIFF and Ultra HDR always.
 * HEIF needs an HEVC (or HEIC) encoder and AVIF an AV1 encoder ([detect]), and then a real
 * round trip ([verify]): a tiny image is encoded and decoded once per OS build. Formats that
 * fail are hidden from the sheet (the brief's IF WRONG).
 */
data class ExportCapabilities(
    val formats: Set<ExportFormat>,
) {
    fun supports(format: ExportFormat) = format in formats

    companion object {
        val ALL = ExportCapabilities(ExportFormat.entries.toSet())

        private val ALWAYS = ExportFormat.entries.toSet() - ExportFormat.Heif - ExportFormat.Avif

        private const val PREFS = "lineover_export_caps"
        private const val KEY_BUILD = "build"
        private const val KEY_FORMATS = "formats"
        private val PROBED = listOf(ExportFormat.Heif, ExportFormat.Avif)

        /** The last verified result for this OS build, or null before the first [verify]. */
        fun cached(context: Context): ExportCapabilities? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_BUILD, null) != Build.FINGERPRINT) return null
            val ids = prefs.getStringSet(KEY_FORMATS, null) ?: return null
            return ExportCapabilities(ExportFormat.entries.filter { it.id in ids }.toSet())
        }

        /** Encoders found and the files they write decode: HEIF / AVIF probed once, then cached. Slow (first time). */
        fun verify(context: Context): ExportCapabilities {
            cached(context)?.let { return it }
            val candidates = detect()
            val dir = java.io.File(context.cacheDir, "export-probe")
            val formats = candidates.formats.filter { it !in PROBED || ExportEncoders.probe(it, dir) }.toSet()
            dir.deleteRecursively()
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_BUILD, Build.FINGERPRINT)
                .putStringSet(KEY_FORMATS, formats.map { it.id }.toSet())
                .apply()
            return ExportCapabilities(formats)
        }

        /** Without a verified result: the formats that need no probe. */
        val UNVERIFIED: ExportCapabilities get() = ExportCapabilities(ALWAYS)

        /** Formats whose encoders exist (not yet proven to write decodable files). */
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
            if (MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC in encoders ||
                MediaFormat.MIMETYPE_VIDEO_HEVC in encoders
            ) {
                formats += ExportFormat.Heif
            }
            if (MediaFormat.MIMETYPE_VIDEO_AV1 in encoders) formats += ExportFormat.Avif
            return ExportCapabilities(formats)
        }
    }
}
