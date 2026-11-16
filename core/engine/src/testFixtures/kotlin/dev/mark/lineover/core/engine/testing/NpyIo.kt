package dev.mark.lineover.core.engine.testing

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reader for NumPy .npy files holding little-endian float32, C order, shape (H, W, C). */
object NpyIo {
    private val MAGIC =
        byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte())
    private const val VERSION_1_HEADER_LENGTH_BYTES = 2
    private const val VERSION_2_HEADER_LENGTH_BYTES = 4
    private const val DIMENSIONS = 3

    fun readF32(input: InputStream): F32Image {
        val stream = DataInputStream(input)
        val magic = ByteArray(MAGIC.size).also(stream::readFully)
        require(magic.contentEquals(MAGIC)) { "NPY: bad magic" }
        val major = stream.readUnsignedByte()
        stream.readUnsignedByte() // minor version

        val lengthBytes = if (major == 1) VERSION_1_HEADER_LENGTH_BYTES else VERSION_2_HEADER_LENGTH_BYTES
        var headerLength = 0
        for (i in 0 until lengthBytes) {
            headerLength = headerLength or (stream.readUnsignedByte() shl (Byte.SIZE_BITS * i))
        }
        val header = String(ByteArray(headerLength).also(stream::readFully), Charsets.ISO_8859_1)
        require("'descr': '<f4'" in header) { "NPY: expected little-endian float32, got $header" }
        require("'fortran_order': False" in header) { "NPY: expected C order" }

        val shape =
            header
                .substringAfter("'shape':")
                .substringAfter('(')
                .substringBefore(')')
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
        require(shape.size == DIMENSIONS) { "NPY: expected shape (H, W, C), got $shape" }
        val (height, width, channels) = shape

        val bytes = ByteArray(height * width * channels * Float.SIZE_BYTES).also(stream::readFully)
        require(stream.read() == -1) { "NPY: trailing data" }
        val data = FloatArray(height * width * channels)
        ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(data)
        return F32Image(width, height, channels, data)
    }
}
