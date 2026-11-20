package dev.mark.lineover.core.io.depth

/**
 * A depth map stored in the photo (Stage 17's Depth range mask without a model): the Android
 * Dynamic Depth 1.0 format, a JPEG whose XMP lists a container of media items (Container:Directory,
 * Item:Semantic / Mime / Length / Padding) appended after the primary image in that order. The
 * item with Semantic "Depth" is the depth image (JPEG or PNG); its [format] ("RangeInverse" or
 * "RangeLinear", from Depth:Format) encodes the nearest point as 0 in both, so near = 1 - grey.
 */
class EmbeddedDepth(
    val mime: String,
    val bytes: ByteArray,
    val format: String?,
) {
    companion object {
        /** The depth item of [jpeg], or null when it has none (most photos; Mark's carry only a gain map). */
        fun find(jpeg: ByteArray): EmbeddedDepth? {
            val xmp = xmpPackets(jpeg).joinToString("\n")
            val items =
                if (xmp.contains("Container:Directory")) {
                    ITEM
                        .findAll(xmp)
                        .map {
                            attributes(it.groupValues[1] + it.groupValues[2])
                        }.toList()
                } else {
                    emptyList()
                }
            val depth = items.indexOfFirst { it["Item:Semantic"] == "Depth" }
            val range = if (depth > 0) itemRange(items, depth, jpeg.size) else null
            return range?.let {
                val format = FORMAT.find(xmp)?.groupValues?.let { g -> g[1].ifEmpty { g[2] } }
                EmbeddedDepth(items[depth]["Item:Mime"].orEmpty(), jpeg.copyOfRange(it.first, it.last + 1), format)
            }
        }

        /** Where item [index] lies: the items after the primary are concatenated at the end of the file. */
        private fun itemRange(
            items: List<Map<String, String>>,
            index: Int,
            fileSize: Int,
        ): IntRange? {
            val lengths = items.map { it["Item:Length"]?.toLongOrNull() ?: 0L }
            val sizes = items.mapIndexed { i, item -> lengths[i] + (item["Item:Padding"]?.toLongOrNull() ?: 0L) }
            val start = fileSize - sizes.drop(index).sum()
            val valid = lengths.drop(1).all { it > 0L } && start >= 0 && start + lengths[index] <= fileSize
            return if (valid) start.toInt() until (start + lengths[index]).toInt() else null
        }

        /** The XMP packets of the JPEG's APP1 segments (standard and extended), as text. */
        internal fun xmpPackets(jpeg: ByteArray): List<String> {
            val out = mutableListOf<String>()
            if (jpeg.size < HEADER || jpeg[0] != FF || jpeg[1] != SOI) return out
            var i = MARKER
            var next = segmentEnd(jpeg, i)
            while (next > 0) {
                if ((jpeg[i + 1].toInt() and BYTE) == APP1) xmpOf(jpeg, i + HEADER, next)?.let { out += it }
                i = next
                next = segmentEnd(jpeg, i)
            }
            return out
        }

        /** The end of the marker segment at [i], or -1 at the scan, the end, or a broken segment. */
        private fun segmentEnd(
            jpeg: ByteArray,
            i: Int,
        ): Int {
            if (i + HEADER > jpeg.size || jpeg[i] != FF) return -1
            val marker = jpeg[i + 1].toInt() and BYTE
            val length = ((jpeg[i + MARKER].toInt() and BYTE) shl BITS) or (jpeg[i + MARKER + 1].toInt() and BYTE)
            val end = i + MARKER + length
            val scan = marker == SOS || marker == EOI
            return if (scan || length < MARKER || end > jpeg.size) -1 else end
        }

        private fun xmpOf(
            jpeg: ByteArray,
            from: Int,
            to: Int,
        ): String? {
            val head = String(jpeg, from, minOf(to - from, XMP_EXTENSION.length), Charsets.ISO_8859_1)
            val xmp = head.startsWith(XMP) || head.startsWith(XMP_EXTENSION)
            return if (xmp) String(jpeg, from, to - from, Charsets.UTF_8) else null
        }

        private fun attributes(text: String): Map<String, String> =
            ATTRIBUTE.findAll(text).associate { it.groupValues[1] to it.groupValues[2] } +
                ELEMENT.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }

        private val ITEM = Regex("<Container:Item\\b([^>]*?)(?:/>|>(.*?)</Container:Item>)", RegexOption.DOT_MATCHES_ALL)
        private val ATTRIBUTE = Regex("(Item:\\w+)\\s*=\\s*\"([^\"]*)\"")
        private val ELEMENT = Regex("<(Item:\\w+)>([^<]*)</Item:\\w+>")
        private val FORMAT = Regex("Depth:Format\\s*=\\s*\"([^\"]*)\"|<Depth:Format>([^<]*)<")
        private const val XMP = "http://ns.adobe.com/xap/1.0/\u0000"
        private const val XMP_EXTENSION = "http://ns.adobe.com/xmp/extension/\u0000"
        private const val FF = 0xFF.toByte()
        private const val SOI = 0xD8.toByte()
        private const val APP1 = 0xE1
        private const val SOS = 0xDA
        private const val EOI = 0xD9
        private const val BYTE = 0xFF
        private const val MARKER = 2 // a marker's bytes, and the length field's
        private const val HEADER = 4 // marker + length
        private const val BITS = 8
    }
}
