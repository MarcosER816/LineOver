package dev.mark.lineover.core.engine.testing

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * Minimal PNG codec for tests: 8-bit RGB / RGBA, non-interlaced. It only needs
 * java.util.zip, so the same code runs in JVM unit tests and on a device, and it
 * never premultiplies alpha (unlike android.graphics.Bitmap).
 */
object PngIo {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val IHDR_LENGTH = 13
    private const val BIT_DEPTH = 8
    private const val COLOR_TYPE_RGB = 2
    private const val COLOR_TYPE_RGBA = 6
    private const val BYTE_MASK = 0xFF
    private const val CHUNK_TYPE_LENGTH = 4
    private const val RGB_CHANNELS = 3
    private const val RGBA_CHANNELS = 4

    private const val FILTER_NONE = 0
    private const val FILTER_SUB = 1
    private const val FILTER_UP = 2
    private const val FILTER_AVERAGE = 3
    private const val FILTER_PAETH = 4

    fun read(input: InputStream): U8Image {
        val stream = DataInputStream(input)
        val signature = ByteArray(SIGNATURE.size).also(stream::readFully)
        require(signature.contentEquals(SIGNATURE)) { "PNG: bad signature" }

        var width = 0
        var height = 0
        var channels = 0
        val compressed = ByteArrayOutputStream()
        while (true) {
            val length = stream.readInt()
            val type = String(ByteArray(CHUNK_TYPE_LENGTH).also(stream::readFully), Charsets.US_ASCII)
            val payload = ByteArray(length).also(stream::readFully)
            stream.readInt() // CRC, not verified: the inflate step detects corruption.
            when (type) {
                "IHDR" -> {
                    require(length == IHDR_LENGTH) { "PNG: bad IHDR" }
                    val header = DataInputStream(payload.inputStream())
                    width = header.readInt()
                    height = header.readInt()
                    val bitDepth = header.readUnsignedByte()
                    val colorType = header.readUnsignedByte()
                    header.skipBytes(2)
                    val interlace = header.readUnsignedByte()
                    require(bitDepth == BIT_DEPTH && interlace == 0) { "PNG: only 8-bit non-interlaced is supported" }
                    channels =
                        when (colorType) {
                            COLOR_TYPE_RGB -> RGB_CHANNELS
                            COLOR_TYPE_RGBA -> RGBA_CHANNELS
                            else -> throw IllegalArgumentException("PNG: only RGB / RGBA is supported")
                        }
                }

                "IDAT" -> {
                    compressed.write(payload)
                }

                "IEND" -> {
                    break
                }
            }
        }
        require(width > 0 && height > 0) { "PNG: missing IHDR" }

        val stride = width * channels
        val raw = ByteArray((stride + 1) * height)
        val inflater = Inflater()
        inflater.setInput(compressed.toByteArray())
        var filled = 0
        while (filled < raw.size && !inflater.finished()) {
            val count = inflater.inflate(raw, filled, raw.size - filled)
            require(count > 0 || inflater.finished()) { "PNG: corrupt image data" }
            filled += count
        }
        inflater.end()
        require(filled == raw.size) { "PNG: truncated image data" }

        return U8Image(width, height, channels, unfilter(raw, height, stride, channels))
    }

    private fun unfilter(
        raw: ByteArray,
        height: Int,
        stride: Int,
        bytesPerPixel: Int,
    ): ByteArray {
        val out = ByteArray(stride * height)
        for (y in 0 until height) {
            val inRow = y * (stride + 1)
            val outRow = y * stride
            val filter = raw[inRow].toInt()
            for (x in 0 until stride) {
                val left = if (x >= bytesPerPixel) out[outRow + x - bytesPerPixel].toInt() and BYTE_MASK else 0
                val above = if (y > 0) out[outRow - stride + x].toInt() and BYTE_MASK else 0
                val aboveLeft =
                    if (y > 0 && x >= bytesPerPixel) out[outRow - stride + x - bytesPerPixel].toInt() and BYTE_MASK else 0
                val predictor =
                    when (filter) {
                        FILTER_NONE -> 0
                        FILTER_SUB -> left
                        FILTER_UP -> above
                        FILTER_AVERAGE -> (left + above) / 2
                        FILTER_PAETH -> paeth(left, above, aboveLeft)
                        else -> throw IllegalArgumentException("PNG: unknown filter type $filter")
                    }
                out[outRow + x] = (raw[inRow + 1 + x] + predictor).toByte()
            }
        }
        return out
    }

    private fun paeth(
        a: Int,
        b: Int,
        c: Int,
    ): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) {
            a
        } else if (pb <= pc) {
            b
        } else {
            c
        }
    }

    fun write(
        image: U8Image,
        output: OutputStream,
    ) {
        require(image.channels == RGB_CHANNELS || image.channels == RGBA_CHANNELS) { "PNG: can only write RGB / RGBA" }
        val stride = image.width * image.channels
        val raw = ByteArray((stride + 1) * image.height)
        for (y in 0 until image.height) {
            // The first byte of each row stays 0: filter type "None".
            image.data.copyInto(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride)
        }

        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(raw)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(DEFLATE_BUFFER)
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()

        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeInt(image.width)
            writeInt(image.height)
            writeByte(BIT_DEPTH)
            writeByte(if (image.channels == RGBA_CHANNELS) COLOR_TYPE_RGBA else COLOR_TYPE_RGB)
            writeByte(0) // compression method
            writeByte(0) // filter method
            writeByte(0) // no interlace
        }

        val stream = DataOutputStream(output)
        stream.write(SIGNATURE)
        writeChunk(stream, "IHDR", header.toByteArray())
        writeChunk(stream, "IDAT", compressed.toByteArray())
        writeChunk(stream, "IEND", ByteArray(0))
        stream.flush()
    }

    private fun writeChunk(
        stream: DataOutputStream,
        type: String,
        payload: ByteArray,
    ) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc =
            CRC32().apply {
                update(typeBytes)
                update(payload)
            }
        stream.writeInt(payload.size)
        stream.write(typeBytes)
        stream.write(payload)
        stream.writeInt(crc.value.toInt())
    }

    private const val DEFLATE_BUFFER = 8192
}
