package dev.mark.lineover.core.io.source

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dev.mark.lineover.core.io.project.PreviewImage
import dev.mark.lineover.core.io.project.ThumbnailMath
import java.io.Closeable
import java.io.IOException

/**
 * How decoded pixels reach the engine. The ids are the native `lo::PixelFormat` values.
 *
 * - [Srgb8]: 8-bit sRGB, the common case (half the memory of F16).
 * - [DisplayP38]: 8-bit Display P3 (wide-gamut phone photos), kept in P3, no clipping.
 * - [LinearExtendedSrgbF16]: RGBA_F16 in LINEAR_EXTENDED_SRGB for other wide-gamut and
 *   HDR sources (values outside 0..1 carry the extra gamut and headroom).
 */
enum class SourceFormat(
    val id: Int,
    val config: Bitmap.Config,
    val colorSpace: ColorSpace.Named,
) {
    Srgb8(0, Bitmap.Config.ARGB_8888, ColorSpace.Named.SRGB),
    DisplayP38(1, Bitmap.Config.ARGB_8888, ColorSpace.Named.DISPLAY_P3),
    LinearExtendedSrgbF16(2, Bitmap.Config.RGBA_F16, ColorSpace.Named.LINEAR_EXTENDED_SRGB),
    ;

    companion object {
        /** The engine format for a source in [colorSpace] (null = unknown, treated as sRGB). */
        fun forColorSpace(colorSpace: ColorSpace?): SourceFormat =
            when {
                colorSpace == null || colorSpace == ColorSpace.get(ColorSpace.Named.SRGB) -> Srgb8
                colorSpace == ColorSpace.get(ColorSpace.Named.DISPLAY_P3) -> DisplayP38
                colorSpace.isWideGamut || colorSpace.model != ColorSpace.Model.RGB || isHdr(colorSpace) -> LinearExtendedSrgbF16
                else -> Srgb8
            }

        private fun isHdr(colorSpace: ColorSpace): Boolean =
            colorSpace == ColorSpace.get(ColorSpace.Named.BT2020_HLG) || colorSpace == ColorSpace.get(ColorSpace.Named.BT2020_PQ)
    }
}

/**
 * What the engine needs to know about an original before decoding pixels.
 *
 * @param rawWidth stored size (as the region decoder sees it)
 * @param orientation EXIF orientation 1..8 (1 = as stored)
 * @param width oriented size, the image the user sees
 * @param regionDecodable false when the format has no region decoder (only the preview shows)
 */
data class SourceInfo(
    val uri: Uri,
    val mimeType: String?,
    val rawWidth: Int,
    val rawHeight: Int,
    val orientation: Int,
    val width: Int,
    val height: Int,
    val format: SourceFormat,
    val colorSpaceName: String?,
    val regionDecodable: Boolean,
)

/** A decoded raw region for one tile (see the native `lo::RegionSource`). */
class DecodedRegion(
    val bitmap: Bitmap,
    val regionX: Int,
    val regionY: Int,
    val rawLevelWidth: Int,
    val rawLevelHeight: Int,
)

/**
 * Reads originals for the engine: the header and EXIF, a preview at most 2048 px (with
 * its Ultra HDR gain map kept), and tiles through [TileDecoder]. Blocking: background
 * threads only (rule R6). Full-resolution pixels never become one bitmap.
 */
class SourceReader(
    private val resolver: ContentResolver,
) {
    /** The header of [uri], or null when it cannot be read. */
    @Suppress("ReturnCount") // one early return per way a file can be unreadable
    fun info(uri: Uri): SourceInfo? {
        var mime: String? = null
        var colorSpace: ColorSpace? = null
        var decodedWidth = 0
        var decodedHeight = 0
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { _, info, _ ->
                mime = info.mimeType
                colorSpace = info.colorSpace
                decodedWidth = info.size.width
                decodedHeight = info.size.height
                throw HeaderRead()
            }
        } catch (_: HeaderRead) {
            // Header captured; no pixels decoded.
        } catch (e: IOException) {
            Log.w(TAG, "cannot read $uri", e)
            return null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "cannot read $uri", e)
            return null
        } catch (e: SecurityException) {
            Log.w(TAG, "no access to $uri", e)
            return null
        }
        if (decodedWidth <= 0 || decodedHeight <= 0) return null
        val orientation = readOrientation(uri)
        val rawSize = regionDecoderSize(uri)
        val (rawWidth, rawHeight) =
            rawSize ?: if (orientation in SWAPPING) decodedHeight to decodedWidth else decodedWidth to decodedHeight
        val swaps = orientation in SWAPPING
        return SourceInfo(
            uri = uri,
            mimeType = mime,
            rawWidth = rawWidth,
            rawHeight = rawHeight,
            orientation = orientation,
            width = if (swaps) rawHeight else rawWidth,
            height = if (swaps) rawWidth else rawHeight,
            format = SourceFormat.forColorSpace(colorSpace),
            colorSpaceName = colorSpace?.name,
            regionDecodable = rawSize != null,
        )
    }

    /** The oriented preview, long edge at most [maxEdge], in [SourceInfo.format]; null if undecodable. */
    fun decodePreview(
        info: SourceInfo,
        maxEdge: Int = MAX_PREVIEW_EDGE,
    ): PreviewImage? {
        val bitmap =
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, info.uri)) { decoder, header, _ ->
                    val (w, h) = ThumbnailMath.targetSize(header.size.width, header.size.height, maxEdge)
                    decoder.setTargetSize(w, h)
                    // SOFTWARE: the engine reads the pixels (HARDWARE is only for UI display).
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    // Premultiplied (ImageDecoder cannot scale straight alpha): the engine
                    // un-premultiplies when it converts to the working space.
                    decoder.setTargetColorSpace(ColorSpace.get(info.format.colorSpace))
                }
            } catch (e: IOException) {
                Log.w(TAG, "preview of ${info.uri} failed", e)
                null
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "preview of ${info.uri} failed", e)
                null
            } catch (e: SecurityException) {
                Log.w(TAG, "preview of ${info.uri} failed", e)
                null
            } ?: return null
        val pixels = toFormat(bitmap, info.format) ?: return null
        val gainmap: Gainmap? = if (bitmap.hasGainmap()) bitmap.gainmap else null
        return PreviewImage(pixels, info.width, info.height, info.format, gainmap, info)
    }

    /** Opens the tile decoder of [info], or null when its format has none. */
    fun openTiles(info: SourceInfo): TileDecoder? {
        if (!info.regionDecodable) return null
        return try {
            TileDecoder(resolver, info)
        } catch (e: IOException) {
            Log.w(TAG, "no region decoder for ${info.uri}", e)
            null
        }
    }

    private fun readOrientation(uri: Uri): Int =
        try {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        } catch (_: SecurityException) {
            ExifInterface.ORIENTATION_NORMAL
        }.coerceIn(1, MAX_ORIENTATION)

    private fun regionDecoderSize(uri: Uri): Pair<Int, Int>? =
        try {
            resolver.openInputStream(uri)?.use { stream ->
                val decoder = BitmapRegionDecoder.newInstance(stream) ?: return null
                try {
                    decoder.width to decoder.height
                } finally {
                    decoder.recycle()
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    private class HeaderRead : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    companion object {
        const val MAX_PREVIEW_EDGE = 2048
        private const val TAG = "LineOverSource"
        private const val MAX_ORIENTATION = 8
        private val SWAPPING = 5..8

        /** [bitmap] in exactly the format the engine expects (converted if the decoder chose otherwise). */
        internal fun toFormat(
            bitmap: Bitmap,
            format: SourceFormat,
        ): Bitmap? {
            val space = ColorSpace.get(format.colorSpace)
            if (bitmap.config == format.config && bitmap.colorSpace == space) return bitmap
            val converted =
                Bitmap.createBitmap(bitmap.width, bitmap.height, format.config, bitmap.hasAlpha(), space)
            android.graphics.Canvas(converted).drawBitmap(bitmap, 0f, 0f, null)
            bitmap.recycle()
            return converted
        }
    }
}

/**
 * Decodes the tiles of one original with BitmapRegionDecoder: level L = inSampleSize 2^L,
 * 512 x 512 oriented tiles. Not thread-safe: use one per decoding thread. Region bitmaps
 * live in native memory (not the Java heap) and are recycled after each tile.
 */
class TileDecoder internal constructor(
    resolver: ContentResolver,
    private val info: SourceInfo,
) : Closeable {
    private val decoder: BitmapRegionDecoder =
        resolver.openInputStream(info.uri)?.use { BitmapRegionDecoder.newInstance(it) }
            ?: throw IOException("no region decoder for ${info.uri}")

    /** The raw region behind oriented tile ([level], [tx], [ty]), or null if it cannot be decoded. */
    @Suppress("ReturnCount") // one early return per way a tile can be out of range or undecodable
    fun decode(
        level: Int,
        tx: Int,
        ty: Int,
    ): DecodedRegion? {
        val sample = 1 shl level
        val levelWidth = ceilDiv(info.width, sample)
        val levelHeight = ceilDiv(info.height, sample)
        val x0 = tx * TILE
        val y0 = ty * TILE
        if (x0 >= levelWidth || y0 >= levelHeight) return null
        val tileW = minOf(TILE, levelWidth - x0)
        val tileH = minOf(TILE, levelHeight - y0)
        // Oriented tile -> raw rect, in original pixels.
        val rawLevelWidth = ceilDiv(info.rawWidth, sample)
        val rawLevelHeight = ceilDiv(info.rawHeight, sample)
        val raw = TileGeometry.rawRectFor(x0, y0, tileW, tileH, rawLevelWidth, rawLevelHeight, info.orientation)
        val rect =
            Rect(
                raw.left * sample,
                raw.top * sample,
                minOf(info.rawWidth, raw.right * sample),
                minOf(info.rawHeight, raw.bottom * sample),
            )
        if (rect.isEmpty) return null
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = info.format.config
                inPreferredColorSpace = ColorSpace.get(info.format.colorSpace)
                inPremultiplied = false
            }
        val bitmap =
            try {
                decoder.decodeRegion(rect, options)
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
        val pixels = SourceReader.toFormat(bitmap, info.format) ?: return null
        return DecodedRegion(pixels, raw.left, raw.top, rawLevelWidth, rawLevelHeight)
    }

    override fun close() = decoder.recycle()

    private fun ceilDiv(
        a: Int,
        b: Int,
    ) = (a + b - 1) / b

    companion object {
        const val TILE = 512
    }
}

/** Kotlin twin of the native orientation maths (`tiling.h`), tested against the same cases. */
object TileGeometry {
    /** The raw rect (left, top, right, bottom; exclusive) holding oriented rect (x, y, w, h). */
    @Suppress("LongParameterList") // mirrors the native signature
    fun rawRectFor(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        rawWidth: Int,
        rawHeight: Int,
        orientation: Int,
    ): Rect {
        val a = orientedToRaw(x, y, rawWidth, rawHeight, orientation)
        val b = orientedToRaw(x + w - 1, y + h - 1, rawWidth, rawHeight, orientation)
        return Rect(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first) + 1, maxOf(a.second, b.second) + 1)
    }

    @Suppress("MagicNumber") // EXIF orientation values
    fun orientedToRaw(
        u: Int,
        v: Int,
        w: Int,
        h: Int,
        orientation: Int,
    ): Pair<Int, Int> =
        when (orientation) {
            2 -> (w - 1 - u) to v
            3 -> (w - 1 - u) to (h - 1 - v)
            4 -> u to (h - 1 - v)
            5 -> v to u
            6 -> v to (h - 1 - u)
            7 -> (w - 1 - v) to (h - 1 - u)
            8 -> (w - 1 - v) to u
            else -> u to v
        }
}
