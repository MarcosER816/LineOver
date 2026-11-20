package dev.mark.lineover.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.mark.lineover.core.io.masks.MaskRaster
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.LayerMath
import dev.mark.lineover.core.model.MaskPaint
import kotlin.math.max
import kotlin.math.roundToInt

/** Saved selections (Stage 15) the graph's masks name. */
internal fun rasterNames(graph: EditGraph): Set<String> =
    LayerMath
        .flatten(graph)
        .mapNotNull { it.first.mask }
        .flatMap { it.paint }
        .filterIsInstance<MaskPaint.Raster>()
        .map { it.source }
        .toSet()

/** A small grey picture of a saved selection (the mask thumbnail). */
internal fun maskThumbnail(raster: MaskRaster): ImageBitmap {
    val w = THUMB
    val h = max(1, (THUMB.toFloat() * raster.height / raster.width).roundToInt())
    val pixels =
        IntArray(w * h) { i ->
            val x = (i % w) * raster.width / w
            val y = (i / w) * raster.height / h
            val g = (raster.values[y * raster.width + x].coerceIn(0f, 1f) * MAX_CODE).roundToInt()
            Color.rgb(g, g, g)
        }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private const val THUMB = 64
private const val MAX_CODE = 255f
