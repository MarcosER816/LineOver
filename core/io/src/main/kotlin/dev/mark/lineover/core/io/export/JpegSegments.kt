package dev.mark.lineover.core.io.export

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Adds LineOver's EXIF block and an ICC profile to a JPEG the platform encoder wrote (Stage 12).
 *
 * The encoder's own Exif APP1 (if any) is replaced; its ICC profile is kept when present, else
 * [icc] is inserted. New segments go right after SOI (after a leading JFIF APP0). Everything
 * else is copied byte for byte, including an Ultra HDR file's MPF APP2 and the gain map image
 * after the primary EOI: MPF offsets count from the MPF header itself, which moves together
 * with the images it points to, so they stay valid.
 */
object JpegSegments {
    private const val SOI = 0xD8
    private const val SOS = 0xDA
    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2
    private const val MAX_SEGMENT = 0xFFFF - 2
    private val EXIF = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    private val ICC = "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1)

    /** A marker segment of the header (before SOS): [marker] and its payload (without the length). */
    data class Segment(
        val marker: Int,
        val payload: ByteArray,
    ) {
        val isExif: Boolean get() = marker == APP1 && payload.startsWith(EXIF)
        val isIcc: Boolean get() = marker == APP2 && payload.startsWith(ICC)

        override fun equals(other: Any?) = other is Segment && marker == other.marker && payload.contentEquals(other.payload)

        override fun hashCode() = 31 * marker + payload.contentHashCode()
    }

    /** The header segments of [jpeg] and the offset where the scan (SOS) starts. */
    fun parse(jpeg: ByteArray): Pair<List<Segment>, Int> {
        if (jpeg.size < 4 || jpeg.u8(0) != 0xFF || jpeg.u8(1) != SOI) throw IOException("not a JPEG")
        val segments = ArrayList<Segment>()
        var at = 2
        while (at + 4 <= jpeg.size) {
            if (jpeg.u8(at) != 0xFF) throw IOException("bad JPEG marker at $at")
            val marker = jpeg.u8(at + 1)
            if (marker == 0xFF) {
                at++ // fill byte
                continue
            }
            if (marker == SOS) return segments to at
            val length = (jpeg.u8(at + 2) shl 8) or jpeg.u8(at + 3)
            if (length < 2 || at + 2 + length > jpeg.size) throw IOException("truncated JPEG segment at $at")
            segments += Segment(marker, jpeg.copyOfRange(at + 4, at + 2 + length))
            at += 2 + length
        }
        throw IOException("JPEG without a scan")
    }

    /**
     * [jpeg] with [exif] (a TIFF-structured EXIF payload, or null for none) and, when it has
     * no ICC profile, [icc].
     */
    fun rewrite(
        jpeg: ByteArray,
        exif: ByteArray?,
        icc: ByteArray?,
    ): ByteArray {
        val (segments, scan) = parse(jpeg)
        val kept = segments.filterNot { it.isExif }
        val added = ArrayList<Segment>()
        if (exif != null) {
            val payload = EXIF + exif
            if (payload.size > MAX_SEGMENT) throw IOException("EXIF block too large (${payload.size} bytes)")
            added += Segment(APP1, payload)
        }
        if (icc != null && kept.none { it.isIcc }) added += iccSegments(icc)
        val leadingJfif = kept.takeWhile { it.marker == APP0 }
        val out = ByteArrayOutputStream(jpeg.size + added.sumOf { it.payload.size + 4 })
        out.write(0xFF)
        out.write(SOI)
        (leadingJfif + added + kept.drop(leadingJfif.size)).forEach { out.writeSegment(it) }
        out.write(jpeg, scan, jpeg.size - scan)
        return out.toByteArray()
    }

    /** The EXIF payload (TIFF structure) of [jpeg], or null. */
    fun exifOf(jpeg: ByteArray): ByteArray? =
        parse(jpeg).first.firstOrNull { it.isExif }?.payload?.let { it.copyOfRange(EXIF.size, it.size) }

    /** The ICC profile of [jpeg] (chunks joined in sequence order), or null. */
    fun iccOf(jpeg: ByteArray): ByteArray? {
        val chunks = parse(jpeg).first.filter { it.isIcc }
        if (chunks.isEmpty()) return null
        val out = ByteArrayOutputStream()
        chunks.sortedBy { it.payload[ICC.size].toInt() and 0xFF }.forEach { out.write(it.payload, ICC.size + 2, it.payload.size - ICC.size - 2) }
        return out.toByteArray()
    }

    private fun iccSegments(icc: ByteArray): List<Segment> {
        val room = MAX_SEGMENT - ICC.size - 2
        val parts = icc.toList().chunked(room)
        return parts.mapIndexed { i, part ->
            Segment(APP2, ICC + byteArrayOf((i + 1).toByte(), parts.size.toByte()) + part.toByteArray())
        }
    }

    private fun ByteArrayOutputStream.writeSegment(s: Segment) {
        val length = s.payload.size + 2
        write(0xFF)
        write(s.marker)
        write(length shr 8)
        write(length and 0xFF)
        write(s.payload)
    }

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
