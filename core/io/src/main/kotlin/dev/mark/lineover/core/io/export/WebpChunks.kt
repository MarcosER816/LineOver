package dev.mark.lineover.core.io.export

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Adds an ICC profile and EXIF to a still WebP the platform encoder wrote (Stage 12): the file
 * becomes the extended format (VP8X) with the chunks in the order the WebP container spec
 * requires: VP8X, ICCP, ALPH, VP8 / VP8L, EXIF. The image chunks are copied unchanged.
 */
object WebpChunks {
    private const val FLAG_ICC = 0x20
    private const val FLAG_ALPHA = 0x10
    private const val FLAG_EXIF = 0x08
    private const val HEADER = 12
    private const val VP8X_SIZE = 10

    data class Chunk(
        val fourcc: String,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?) = other is Chunk && fourcc == other.fourcc && data.contentEquals(other.data)

        override fun hashCode() = 31 * fourcc.hashCode() + data.contentHashCode()
    }

    fun parse(webp: ByteArray): List<Chunk> {
        if (webp.size < HEADER || webp.ascii(0) != "RIFF" || webp.ascii(8) != "WEBP") throw IOException("not a WebP file")
        val chunks = ArrayList<Chunk>()
        var at = HEADER
        while (at + 8 <= webp.size) {
            val fourcc = webp.ascii(at)
            val size = webp.le32(at + 4)
            if (size < 0 || at + 8 + size > webp.size) throw IOException("truncated WebP chunk $fourcc")
            chunks += Chunk(fourcc, webp.copyOfRange(at + 8, at + 8 + size))
            at += 8 + size + (size and 1)
        }
        return chunks
    }

    /** [webp] (width x height) with [icc] and [exif] (TIFF-structured), each optional. */
    fun rewrite(
        webp: ByteArray,
        width: Int,
        height: Int,
        icc: ByteArray?,
        exif: ByteArray?,
    ): ByteArray {
        val chunks = parse(webp)
        if (chunks.any { it.fourcc == "ANIM" }) throw IOException("animated WebP is not supported")
        val image = chunks.filter { it.fourcc == "ALPH" || it.fourcc == "VP8 " || it.fourcc == "VP8L" }
        if (image.none { it.fourcc != "ALPH" }) throw IOException("WebP without image data")
        val hadAlpha = chunks.any { it.fourcc == "ALPH" } || chunks.firstOrNull { it.fourcc == "VP8X" }?.let { (it.data[0].toInt() and FLAG_ALPHA) != 0 } == true
        var flags = 0
        if (icc != null) flags = flags or FLAG_ICC
        if (exif != null) flags = flags or FLAG_EXIF
        if (hadAlpha) flags = flags or FLAG_ALPHA
        val vp8x = ByteArray(VP8X_SIZE)
        vp8x[0] = flags.toByte()
        put24(vp8x, 4, width - 1)
        put24(vp8x, 7, height - 1)
        val out = listOf(Chunk("VP8X", vp8x)) +
            listOfNotNull(icc?.let { Chunk("ICCP", it) }) +
            image +
            listOfNotNull(exif?.let { Chunk("EXIF", it) })
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        for (c in out) {
            body.write(c.fourcc.toByteArray(Charsets.US_ASCII))
            body.write(le32Bytes(c.data.size))
            body.write(c.data)
            if (c.data.size % 2 == 1) body.write(0)
        }
        val payload = body.toByteArray()
        return "RIFF".toByteArray(Charsets.US_ASCII) + le32Bytes(payload.size) + payload
    }

    private fun put24(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte()
        b[at + 1] = (v shr 8).toByte()
        b[at + 2] = (v shr 16).toByte()
    }

    private fun le32Bytes(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun ByteArray.ascii(at: Int) = String(this, at, 4, Charsets.US_ASCII)

    private fun ByteArray.le32(at: Int) =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8) or ((this[at + 2].toInt() and 0xFF) shl 16) or
            ((this[at + 3].toInt() and 0xFF) shl 24)
}
