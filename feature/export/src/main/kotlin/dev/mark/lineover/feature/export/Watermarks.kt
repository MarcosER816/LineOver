package dev.mark.lineover.feature.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import dev.mark.lineover.core.engine.WatermarkPixels
import dev.mark.lineover.core.model.WatermarkKind
import dev.mark.lineover.core.model.WatermarkSettings
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The watermark as pixels at the export's final size (Stage 12): text drawn white with a soft
 * shadow, or the person's logo (copied into the app's files by the sheet), scaled to
 * [WatermarkSettings.scale] % of the long edge and placed at its position with a margin of 3 %
 * of the short edge. The engine composites it in linear light.
 */
object Watermarks {
    private const val MARGIN = 0.03f
    private const val PERCENT = 100f
    private const val SHADOW_RADIUS = 0.06f
    private const val SHADOW_ALPHA = 110

    /** Where the sheet keeps the logo for image watermarks. */
    fun logoFile(context: Context) = File(context.filesDir, "watermark/logo.png")

    fun render(
        context: Context,
        settings: WatermarkSettings,
        width: Int,
        height: Int,
    ): WatermarkPixels? {
        if (!settings.enabled) return null
        val target = max(1, (max(width, height) * settings.scale / PERCENT).roundToInt())
        val bitmap =
            when (settings.kind) {
                WatermarkKind.Text -> text(settings.text, target)
                WatermarkKind.Image -> logo(logoFile(context), target)
            } ?: return null
        val margin = (min(width, height) * MARGIN).roundToInt()
        val x = (margin + (width - 2 * margin - bitmap.width) * settings.position.fx).roundToInt()
        val y = (margin + (height - 2 * margin - bitmap.height) * settings.position.fy).roundToInt()
        val bytes = ByteArray(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
        val pixels =
            WatermarkPixels(
                tight(bytes, bitmap.width, bitmap.height, bitmap.rowBytes),
                bitmap.width,
                bitmap.height,
                x,
                y,
                settings.opacity / PERCENT,
            )
        bitmap.recycle()
        return pixels
    }

    /** [text] about [targetWidth] px wide, white with a shadow, on transparent premultiplied ARGB_8888. */
    fun text(
        text: String,
        targetWidth: Int,
    ): Bitmap? {
        if (text.isBlank()) return null
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textSize = REFERENCE_SIZE
            }
        val reference = paint.measureText(text)
        paint.textSize = REFERENCE_SIZE * targetWidth / reference.coerceAtLeast(1f)
        val shadow = paint.textSize * SHADOW_RADIUS
        paint.setShadowLayer(shadow, 0f, shadow / 2, Color.argb(SHADOW_ALPHA, 0, 0, 0))
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val pad = (shadow * 2).roundToInt() + 1
        val bitmap = Bitmap.createBitmap(bounds.width() + 2 * pad, bounds.height() + 2 * pad, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(text, pad - bounds.left.toFloat(), pad - bounds.top.toFloat(), paint)
        return bitmap
    }

    private fun logo(
        file: File,
        targetWidth: Int,
    ): Bitmap? {
        if (!file.isFile) return null
        val decoded = BitmapFactory.decodeFile(file.path) ?: return null
        val h = max(1, (decoded.height.toFloat() * targetWidth / decoded.width).roundToInt())
        val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, h, true)
        if (scaled !== decoded) decoded.recycle()
        return if (scaled.config ==
            Bitmap.Config.ARGB_8888
        ) {
            scaled
        } else {
            scaled.copy(Bitmap.Config.ARGB_8888, false).also { scaled.recycle() }
        }
    }

    private fun tight(
        bytes: ByteArray,
        width: Int,
        height: Int,
        rowBytes: Int,
    ): ByteArray {
        if (rowBytes == width * CHANNELS) return bytes
        val out = ByteArray(width * height * CHANNELS)
        for (y in 0 until height) System.arraycopy(bytes, y * rowBytes, out, y * width * CHANNELS, width * CHANNELS)
        return out
    }

    private const val CHANNELS = 4
    private const val REFERENCE_SIZE = 100f
}
