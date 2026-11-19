package dev.mark.lineover.core.engine

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Gainmap
import dev.mark.lineover.core.model.ExportColorSpace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** JNI of export_jni.cpp. */
internal object NativeExport {
    init {
        System.loadLibrary("lineover_engine")
    }

    external fun nativeCreate(
        dir: String,
        width: Int,
        height: Int,
    ): Long

    external fun nativeDestroy(job: Long)

    external fun nativeSize(job: Long): IntArray

    @Suppress("LongParameterList")
    external fun nativePutTile(
        job: Long,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        pixels: FloatArray,
    )

    @Suppress("LongParameterList")
    external fun nativeProcess(
        job: Long,
        outWidth: Int,
        outHeight: Int,
        sharpen: Float,
        mark: ByteArray?,
        markWidth: Int,
        markHeight: Int,
        markX: Int,
        markY: Int,
        opacity: Float,
    ): String?

    external fun nativeFillBitmap(
        job: Long,
        bitmap: Bitmap,
        space: Int,
    ): Boolean

    external fun nativeRebuildGainmap(
        job: Long,
        space: Int,
        factor: Int,
    ): ByteArray?

    external fun nativeIcc(space: Int): ByteArray

    external fun nativeWritePng(
        job: Long,
        fd: Int,
        bits: Int,
        space: Int,
        exif: ByteArray?,
    ): String?

    external fun nativeWriteTiff(
        job: Long,
        fd: Int,
        bits: Int,
        space: Int,
        text: Array<String>,
    ): String?
}

/** A watermark ready to composite: premultiplied sRGB RGBA8 ([Bitmap] memory) at (x, y). */
class WatermarkPixels(
    val rgba: ByteArray,
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,
    /** 0..1. */
    val opacity: Float,
)

/**
 * The full-resolution render of an export (Stage 12) in linear Rec. 2020 floats, kept in a
 * temporary file mapped into memory (never on the Java heap). Filled by [EngineSession.renderForExport],
 * then [process]ed and written: [writePng] / [writeTiff] natively, or [toBitmap] for the
 * platform encoders. Not thread-safe; close it when done.
 */
class ExportImage private constructor(
    private var job: Long,
) : AutoCloseable {
    val width: Int get() = NativeExport.nativeSize(checked())[0]
    val height: Int get() = NativeExport.nativeSize(checked())[1]

    internal fun putTile(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        pixels: FloatArray,
    ) = NativeExport.nativePutTile(checked(), x, y, width, height, pixels)

    /** Resize (Lanczos, never enlarges), sharpen for screen (0 = off), then the watermark. */
    suspend fun process(
        outWidth: Int,
        outHeight: Int,
        sharpen: Float,
        watermark: WatermarkPixels?,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) = withContext(dispatcher) {
        val error =
            NativeExport.nativeProcess(
                checked(),
                outWidth,
                outHeight,
                sharpen,
                watermark?.rgba,
                watermark?.width ?: 0,
                watermark?.height ?: 0,
                watermark?.x ?: 0,
                watermark?.y ?: 0,
                watermark?.opacity ?: 0f,
            )
        if (error != null) throw IOException(error)
    }

    /** An opaque RGBA_8888 Bitmap in [space] (sRGB or Display P3), gamut-mapped; native memory. */
    fun toBitmap(space: ExportColorSpace): Bitmap {
        require(space != ExportColorSpace.Rec2020) { "8-bit Bitmaps are sRGB or Display P3" }
        val named = if (space == ExportColorSpace.Srgb) ColorSpace.Named.SRGB else ColorSpace.Named.DISPLAY_P3
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, false, ColorSpace.get(named))
        if (!NativeExport.nativeFillBitmap(checked(), bitmap, space.id)) {
            bitmap.recycle()
            throw IOException("cannot convert the image for the encoder")
        }
        return bitmap
    }

    /**
     * An Ultra HDR gain map rebuilt from the render's highlights above the SDR base in
     * [space], one sample per [factor] x [factor] pixels; null when there are none.
     */
    fun rebuildGainmap(
        space: ExportColorSpace,
        factor: Int = GAINMAP_FACTOR,
    ): Gainmap? {
        val bytes = NativeExport.nativeRebuildGainmap(checked(), space.id, factor) ?: return null
        val header = ByteBuffer.wrap(bytes, 0, GAINMAP_HEADER).order(ByteOrder.LITTLE_ENDIAN)
        val w = header.int
        val h = header.int
        val ratio = header.float
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val rowBytes = bitmap.rowBytes
        val padded = ByteArray(rowBytes * h)
        for (y in 0 until h) System.arraycopy(bytes, GAINMAP_HEADER + y * w, padded, y * rowBytes, w)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(padded))
        return Gainmap(bitmap).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(ratio, ratio, ratio)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(GAINMAP_EPSILON, GAINMAP_EPSILON, GAINMAP_EPSILON)
            setEpsilonHdr(GAINMAP_EPSILON, GAINMAP_EPSILON, GAINMAP_EPSILON)
            displayRatioForFullHdr = ratio
            minDisplayRatioForHdrTransition = 1f
        }
    }

    /** PNG (8 / 16 bit, ICC, optional EXIF) into the open file descriptor [fd]. */
    fun writePng(
        fd: Int,
        bits: Int,
        space: ExportColorSpace,
        exif: ByteArray?,
    ) {
        NativeExport.nativeWritePng(checked(), fd, bits, space.id, exif)?.let { throw IOException(it) }
    }

    /** TIFF (8 / 16 bit, ICC, text tags: make, model, software, date, artist, copyright). */
    fun writeTiff(
        fd: Int,
        bits: Int,
        space: ExportColorSpace,
        text: Array<String>,
    ) {
        NativeExport.nativeWriteTiff(checked(), fd, bits, space.id, text)?.let { throw IOException(it) }
    }

    override fun close() {
        if (job != 0L) NativeExport.nativeDestroy(job)
        job = 0L
    }

    private fun checked(): Long {
        check(job != 0L) { "export image closed" }
        return job
    }

    companion object {
        const val GAINMAP_FACTOR = 4
        const val GAINMAP_EPSILON = 1f / 64f // output.h kGainmapEpsilon
        private const val GAINMAP_HEADER = 12

        /** A [width] x [height] image backed by a temporary file in [dir]. */
        fun create(
            dir: File,
            width: Int,
            height: Int,
        ): ExportImage {
            dir.mkdirs()
            return ExportImage(NativeExport.nativeCreate(dir.path, width, height))
        }

        /** LineOver's ICC profile for [space] (byte-identical to the one PNG / TIFF embed). */
        fun iccProfile(space: ExportColorSpace): ByteArray = NativeExport.nativeIcc(space.id)
    }
}
