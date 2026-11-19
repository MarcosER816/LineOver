package dev.mark.lineover.core.model

/**
 * The export sheet's file size estimate (Stage 12). The sheet measures bytes per pixel by
 * encoding the project's small thumbnail ([Sample]); a full-size photo compresses better than
 * its thumbnail (smoother at the pixel level), hence the factors. TIFF is exact (uncompressed).
 */
object ExportEstimate {
    /** Bytes per pixel of the thumbnail at the settings' quality (8-bit encoders). */
    data class Sample(
        val jpeg: Float,
        val png: Float,
        val webp: Float,
        val webpLossless: Float,
    )

    private const val LOSSY_FACTOR = 0.55f
    private const val LOSSLESS_FACTOR = 0.8f
    private const val HEIF_OF_JPEG = 0.55f
    private const val AVIF_OF_JPEG = 0.45f
    private const val ULTRA_HDR_OF_JPEG = 1.15f
    private const val TIFF_HEADER = 8_192L
    private const val CHANNELS = 3
    private const val BITS_PER_BYTE = 8

    fun bytes(
        settings: ExportSettings,
        width: Int,
        height: Int,
        sample: Sample,
    ): Long {
        val pixels = width.toLong() * height
        val bytesPerSample = settings.bits / BITS_PER_BYTE
        val perPixel =
            when (settings.format) {
                ExportFormat.Jpeg -> sample.jpeg * LOSSY_FACTOR
                ExportFormat.UltraHdr -> sample.jpeg * LOSSY_FACTOR * ULTRA_HDR_OF_JPEG
                ExportFormat.Heif -> sample.jpeg * LOSSY_FACTOR * HEIF_OF_JPEG
                ExportFormat.Avif -> sample.jpeg * LOSSY_FACTOR * AVIF_OF_JPEG
                ExportFormat.Webp -> if (settings.webpLossless) sample.webpLossless * LOSSLESS_FACTOR else sample.webp * LOSSY_FACTOR
                ExportFormat.Png -> sample.png * LOSSLESS_FACTOR * bytesPerSample
                ExportFormat.Tiff -> return pixels * CHANNELS * bytesPerSample + TIFF_HEADER
            }
        return (pixels * perPixel).toLong()
    }

    /** "8.4 MB", "640 KB". */
    fun label(bytes: Long): String {
        val kb = bytes / KILO
        return when {
            bytes >= MEGA -> "%.1f MB".format(java.util.Locale.ROOT, bytes / MEGA.toDouble())
            else -> "${kb.coerceAtLeast(1)} KB"
        }
    }

    private const val KILO = 1_000L
    private const val MEGA = 1_000_000L
}
