// Drawing geometry: point indices, stroke widths and tick proportions.
@file:Suppress("MagicNumber")

package dev.mark.lineover.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.model.CropOverlay
import dev.mark.lineover.feature.editor.canvas.CanvasMatrix
import dev.mark.lineover.feature.editor.canvas.CanvasTransform
import dev.mark.lineover.feature.editor.canvas.CropFrame
import dev.mark.lineover.feature.editor.canvas.CropMode

/** The live canvas transform (set by the editor route; screenshot tests fall back to a fitted one). */
val LocalCanvasMatrix = staticCompositionLocalOf<CanvasMatrix?> { null }

/**
 * Crop / perspective-crop / guide handles over the canvas (Stage 9, the reserved overlay
 * slot). Drawing only: the gestures are in CanvasView. The transform is read in the draw
 * phase, so pans and zooms redraw this layer without recomposing.
 */
@Composable
internal fun CropOverlayLayer(state: EditorState) {
    val frame = state.cropFrame ?: return
    val (imageW, imageH) = state.canvasSize ?: return
    val live = LocalCanvasMatrix.current
    val fallback = remember { CanvasMatrix() }
    val fallbackTransform = remember { CanvasTransform() }
    val matrix = live ?: fallback
    val overlay = state.cropOverlay
    val dragging = state.cropDragging
    val guides = state.geometry.guides
    Canvas(
        Modifier
            .fillMaxSize()
            .testTag(EditorTags.CANVAS_OVERLAY)
            .onSizeChanged {
                if (live == null) {
                    fallbackTransform.setViewSize(it.width, it.height)
                    fallbackTransform.setImageSize(imageW, imageH)
                    fallback.update(fallbackTransform)
                }
            },
    ) {
        if (live == null && (fallbackTransform.imageWidth != imageW.toFloat() || fallbackTransform.imageHeight != imageH.toFloat())) {
            fallbackTransform.setViewSize(size.width.toInt(), size.height.toInt())
            fallbackTransform.setImageSize(imageW, imageH)
            fallback.update(fallbackTransform)
        }
        matrix.version // read: the draw phase follows the transform
        val toView = { u: Float, v: Float ->
            val out = FloatArray(2)
            matrix.toView(u * matrix.imageWidth, v * matrix.imageHeight, out)
            Offset(out[0], out[1])
        }
        when (frame.mode) {
            CropMode.Crop -> drawCrop(frame, toView, overlay.takeIf { dragging })
            CropMode.Corners -> drawCorners(frame, toView)
            CropMode.Guides -> drawGuides(guides.map { floatArrayOf(it.x1, it.y1, it.x2, it.y2) }, matrix, toView)
        }
    }
}

private val Scrim = LoColors.CropScrim
private val Line = LoColors.CropLine
private val GridLine = LoColors.CropGrid

private fun DrawScope.drawCrop(
    frame: CropFrame,
    toView: (Float, Float) -> Offset,
    grid: CropOverlay?,
) {
    val r = frame.rect
    val tl = toView(r[0], r[1])
    val tr = toView(r[2], r[1])
    val br = toView(r[2], r[3])
    val bl = toView(r[0], r[3])
    // Dim outside the crop.
    val outside =
        Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(
                androidx.compose.ui.geometry
                    .Rect(0f, 0f, size.width, size.height),
            )
            moveTo(tl.x, tl.y)
            lineTo(tr.x, tr.y)
            lineTo(br.x, br.y)
            lineTo(bl.x, bl.y)
            close()
        }
    drawPath(outside, Scrim)
    if (grid != null) drawGrid(grid, r, toView)
    val border = 1.dp.toPx()
    drawPath(quad(tl, tr, br, bl), Line, style = Stroke(border))
    // Corner brackets and edge bars (the 48 dp touch targets are centred on them).
    val arm = 20.dp.toPx()
    val thick = 3.dp.toPx()
    val corners = listOf(tl to (tr to bl), tr to (tl to br), br to (bl to tr), bl to (br to tl))
    for ((c, neighbours) in corners) {
        for (n in listOf(neighbours.first, neighbours.second)) drawLine(Line, c, c + unit(n - c) * arm, thick, StrokeCap.Square)
    }
    val bar = 12.dp.toPx()
    for ((a, b) in listOf(tl to tr, tr to br, br to bl, bl to tl)) {
        val mid = (a + b) / 2f
        val dir = unit(b - a)
        drawLine(Line, mid - dir * bar, mid + dir * bar, thick, StrokeCap.Square)
    }
}

private fun DrawScope.drawGrid(
    overlay: CropOverlay,
    r: FloatArray,
    toView: (Float, Float) -> Offset,
) {
    val fractions =
        when (overlay) {
            CropOverlay.Thirds -> listOf(1f / 3f, 2f / 3f)
            CropOverlay.Golden -> listOf(GOLDEN_SMALL, 1f - GOLDEN_SMALL)
            CropOverlay.Grid -> (1 until GRID_LINES).map { it / GRID_LINES.toFloat() }
        }
    val stroke = 1.dp.toPx()
    for (f in fractions) {
        val x = r[0] + (r[2] - r[0]) * f
        val y = r[1] + (r[3] - r[1]) * f
        drawLine(GridLine, toView(x, r[1]), toView(x, r[3]), stroke)
        drawLine(GridLine, toView(r[0], y), toView(r[2], y), stroke)
    }
}

private fun DrawScope.drawCorners(
    frame: CropFrame,
    toView: (Float, Float) -> Offset,
) {
    val c = frame.corners
    val points = (0 until 4).map { toView(c[2 * it], c[2 * it + 1]) }
    drawPath(quad(points[0], points[1], points[2], points[3]), Line, style = Stroke(1.5.dp.toPx()))
    val radius = 10.dp.toPx()
    for (p in points) {
        drawCircle(Scrim, radius + 2.dp.toPx(), p)
        drawCircle(LoColors.Accent, radius, p, style = Stroke(3.dp.toPx()))
    }
}

private fun DrawScope.drawGuides(
    lines: List<FloatArray>,
    matrix: CanvasMatrix,
    toView: (Float, Float) -> Offset,
) {
    val stroke = 2.dp.toPx()
    for (l in lines) {
        val a = toView(l[0], l[1])
        val b = toView(l[2], l[3])
        drawLine(LoColors.Accent, a, b, stroke, StrokeCap.Round)
        drawCircle(LoColors.Accent, 4.dp.toPx(), a)
        drawCircle(LoColors.Accent, 4.dp.toPx(), b)
    }
    val d = matrix.drawing
    if (!d[0].isNaN()) drawLine(Line, Offset(d[0], d[1]), Offset(d[2], d[3]), stroke, StrokeCap.Round)
}

private fun quad(
    a: Offset,
    b: Offset,
    c: Offset,
    d: Offset,
) = Path().apply {
    moveTo(a.x, a.y)
    lineTo(b.x, b.y)
    lineTo(c.x, c.y)
    lineTo(d.x, d.y)
    close()
}

private fun unit(o: Offset): Offset {
    val d = o.getDistance()
    return if (d == 0f) Offset.Zero else o / d
}

private const val GOLDEN_SMALL = 0.381966f
private const val GRID_LINES = 6
