package dev.mark.lineover.core.ml

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** An sRGB image for a model: [width] x [height] RGBA bytes, row by row. */
class ModelImage(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
) {
    fun toBitmap(): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(rgba)) // ARGB_8888 is RGBA in memory
        }

    /** Bilinear resample of the RGB channels to [w] x [h], packed RGB bytes (a model's input). */
    fun rgbResized(
        w: Int,
        h: Int,
    ): ByteArray {
        val out = ByteArray(w * h * RGB)
        for (j in 0 until h) {
            val fy = ((j + HALF) * height / h - HALF).coerceIn(0f, height - 1f)
            val y0 = fy.toInt()
            val y1 = minOf(y0 + 1, height - 1)
            val ty = fy - y0
            for (i in 0 until w) {
                val fx = ((i + HALF) * width / w - HALF).coerceIn(0f, width - 1f)
                val x0 = fx.toInt()
                val x1 = minOf(x0 + 1, width - 1)
                val tx = fx - x0
                for (c in 0 until RGB) {
                    fun at(
                        x: Int,
                        y: Int,
                    ): Float = (rgba[(y * width + x) * RGBA + c].toInt() and BYTE).toFloat()
                    val top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * tx
                    val bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * tx
                    out[(j * w + i) * RGB + c] = (top + (bottom - top) * ty + HALF).toInt().coerceIn(0, BYTE).toByte()
                }
            }
        }
        return out
    }

    private companion object {
        const val HALF = 0.5f
        const val RGB = 3
        const val RGBA = 4
        const val BYTE = 255
    }
}

/** A model's soft mask (0..1, row by row) covering the whole image it was given. */
class ModelMask(
    val width: Int,
    val height: Int,
    val values: FloatArray,
)

/** A prompt for interactive segmentation: points (0..1 image coordinates) of a tap or scribble. */
data class SegmentPrompt(
    val points: List<Pair<Float, Float>>,
    val positive: Boolean,
)
