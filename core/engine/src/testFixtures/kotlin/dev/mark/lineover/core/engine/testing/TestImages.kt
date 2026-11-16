package dev.mark.lineover.core.engine.testing

/** Interleaved 8-bit image: [data] holds width * height * channels samples, row-major. */
class U8Image(
    val width: Int,
    val height: Int,
    val channels: Int,
    val data: ByteArray,
) {
    init {
        require(data.size == width * height * channels) { "data size does not match ${width}x${height}x$channels" }
    }

    /** The samples as floats in 0..1 (value / 255). */
    fun toF32(): F32Image = F32Image(width, height, channels, FloatArray(data.size) { (data[it].toInt() and BYTE_MASK) / U8_MAX })

    private companion object {
        const val BYTE_MASK = 0xFF
        const val U8_MAX = 255f
    }
}

/** Interleaved float image: [data] holds width * height * channels samples, row-major. */
class F32Image(
    val width: Int,
    val height: Int,
    val channels: Int,
    val data: FloatArray,
) {
    init {
        require(data.size == width * height * channels) { "data size does not match ${width}x${height}x$channels" }
    }
}
