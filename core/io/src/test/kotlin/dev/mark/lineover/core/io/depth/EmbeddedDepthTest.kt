package dev.mark.lineover.core.io.depth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Dynamic Depth (Stage 17): the depth item found from the XMP container, or none. */
class EmbeddedDepthTest {
    private fun segment(
        marker: Int,
        body: ByteArray,
    ): ByteArray {
        val length = body.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + body
    }

    private fun jpeg(
        xmp: String?,
        tail: ByteArray,
    ): ByteArray {
        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val app1 = xmp?.let { segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + it.toByteArray()) } ?: ByteArray(0)
        val scan = segment(0xDA, ByteArray(10)) + ByteArray(50) { 7 } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        return soi + app1 + scan + tail
    }

    private val confidence = ByteArray(40) { 3 }
    private val depth = ByteArray(64) { (it * 3).toByte() }

    private fun container(depthPadding: Int = 0) =
        """<x:xmpmeta><rdf:RDF><rdf:Description Depth:Format="RangeInverse">
        <Container:Directory><rdf:Seq>
        <rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/></rdf:li>
        <rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="Depth" Item:Mime="image/png" Item:Length="${depth.size}" Item:Padding="$depthPadding"/></rdf:li>
        <rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="Confidence" Item:Mime="image/png" Item:Length="${confidence.size}"/></rdf:li>
        </rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""

    @Test
    fun findsTheDepthItemCountingBackFromTheEnd() {
        val found = EmbeddedDepth.find(jpeg(container(), depth + confidence))!!
        assertEquals("image/png", found.mime)
        assertEquals("RangeInverse", found.format)
        assertArrayEquals(depth, found.bytes)
        // With padding after the depth item.
        val padded = EmbeddedDepth.find(jpeg(container(depthPadding = 6), depth + ByteArray(6) + confidence))!!
        assertArrayEquals(depth, padded.bytes)
    }

    @Test
    fun photosWithoutADepthItemHaveNone() {
        assertNull(EmbeddedDepth.find(jpeg(null, ByteArray(0))))
        val gainMapOnly = container().replace("Semantic=\"Depth\"", "Semantic=\"GainMap\"")
        assertNull(EmbeddedDepth.find(jpeg(gainMapOnly, depth + confidence)))
        assertNull(EmbeddedDepth.find(ByteArray(3)))
    }

    @Test
    fun marksPhotosCarryAGainMapButNoDepth() {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        val photos = File(requireNotNull(dir), "testdata/photos").listFiles { f -> f.extension.lowercase() == "jpg" }.orEmpty()
        assertTrue(photos.isNotEmpty())
        for (photo in photos) {
            val bytes = photo.readBytes()
            assertTrue(EmbeddedDepth.xmpPackets(bytes).any { it.contains("Container:Directory") }) // Ultra HDR's container
            assertNull(photo.name, EmbeddedDepth.find(bytes))
        }
    }
}
