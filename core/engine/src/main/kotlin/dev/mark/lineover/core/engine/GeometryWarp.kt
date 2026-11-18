package dev.mark.lineover.core.engine

import android.graphics.Bitmap
import android.graphics.Gainmap
import dev.mark.lineover.core.model.OpParams
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The geometry op applied to an RGBA8 image (Stage 9): the Ultra HDR gain map follows the
 * crop, rotation, Upright and lens corrections of the photo (same matrix, one bicubic
 * resample, at the gain map's own resolution). Any thread but the main one.
 */
object GeometryWarp {
    init {
        System.loadLibrary("lineover_engine")
    }

    /** [gainmap] warped like the photo of [sourceWidth] x [sourceHeight]; null when it cannot be read. */
    @Suppress("MagicNumber") // RGBA byte offsets
    fun warpGainmap(
        gainmap: Gainmap,
        graphJson: String,
        geometry: OpParams.Geometry,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Gainmap? {
        val contents = gainmap.gainmapContents
        // Single-channel gain maps are ALPHA_8: warp the value in every channel, keep the format.
        val single = contents.config == Bitmap.Config.ALPHA_8
        val width = contents.width
        val height = contents.height
        val bytes = ByteArray(width * height * CHANNELS)
        if (single) {
            val plane = ByteArray(contents.rowBytes * height)
            contents.copyPixelsToBuffer(ByteBuffer.wrap(plane))
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val v = plane[y * contents.rowBytes + x]
                    val o = (y * width + x) * CHANNELS
                    bytes[o] = v
                    bytes[o + 1] = v
                    bytes[o + 2] = v
                    bytes[o + 3] = OPAQUE
                }
            }
        } else {
            val rgba = contents.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            rgba.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
            if (rgba != contents) rgba.recycle()
        }
        val outWidth = max(1, (geometry.outputWidth.toDouble() * width / sourceWidth).roundToInt())
        val outHeight = max(1, (geometry.outputHeight.toDouble() * height / sourceHeight).roundToInt())
        val warped =
            nativeWarpRgba8(graphJson, sourceWidth, sourceHeight, bytes, width, height, outWidth, outHeight) ?: return null
        val bitmap: Bitmap
        if (single) {
            bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ALPHA_8)
            val plane = ByteArray(bitmap.rowBytes * outHeight)
            for (y in 0 until outHeight) {
                for (x in 0 until outWidth) plane[y * bitmap.rowBytes + x] = warped[(y * outWidth + x) * CHANNELS]
            }
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(plane))
        } else {
            bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(warped))
        }
        return Gainmap(bitmap).apply {
            val min = gainmap.ratioMin
            setRatioMin(min[0], min[1], min[2])
            val maxRatio = gainmap.ratioMax
            setRatioMax(maxRatio[0], maxRatio[1], maxRatio[2])
            val gamma = gainmap.gamma
            setGamma(gamma[0], gamma[1], gamma[2])
            val sdr = gainmap.epsilonSdr
            setEpsilonSdr(sdr[0], sdr[1], sdr[2])
            val hdr = gainmap.epsilonHdr
            setEpsilonHdr(hdr[0], hdr[1], hdr[2])
            displayRatioForFullHdr = gainmap.displayRatioForFullHdr
            minDisplayRatioForHdrTransition = gainmap.minDisplayRatioForHdrTransition
        }
    }

    private const val CHANNELS = 4
    private const val OPAQUE: Byte = -1

    @JvmStatic
    @Suppress("LongParameterList")
    private external fun nativeWarpRgba8(
        graph: String,
        sourceWidth: Int,
        sourceHeight: Int,
        rgba: ByteArray,
        width: Int,
        height: Int,
        outWidth: Int,
        outHeight: Int,
    ): ByteArray?
}
