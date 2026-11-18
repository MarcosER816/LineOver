package dev.mark.lineover.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Pure geometry of [LoCurveEditor] (graph space: x right, y up, 0..1), testable without Compose. */
object LoCurveEditorMath {
    /** How far outside the graph a dragged point must go to be deleted, in graph units. */
    const val DELETE_MARGIN = 0.12f

    fun isOffGraph(
        x: Float,
        y: Float,
    ): Boolean = x < -DELETE_MARGIN || x > 1f + DELETE_MARGIN || y < -DELETE_MARGIN || y > 1f + DELETE_MARGIN

    /** The index of the point nearest (x, y) within [radius] graph units, or -1. */
    fun hit(
        points: List<Offset>,
        x: Float,
        y: Float,
        radius: Float,
    ): Int {
        var best = -1
        var bestDistance = radius
        points.forEachIndexed { i, p ->
            val d = hypot(p.x - x, p.y - y)
            if (d <= bestDistance) {
                best = i
                bestDistance = d
            }
        }
        return best
    }

    /** "In 64 · Out 80" on the 0..255 scale. */
    fun readout(point: Offset): String = "In ${(point.x * LEVELS).roundToInt()} · Out ${(point.y * LEVELS).roundToInt()}"

    private const val LEVELS = 255f
}

/**
 * The curve editor (point curves; full width, square). Tap the graph to add a point on
 * the curve, drag a point to move it, drag it off the graph to delete it (it turns red
 * first). While dragging, a 3× magnifier shows the area around the point and the input /
 * output values are shown under the graph. [histogram] (heights 0..1) is drawn behind
 * the curve. The caller owns the points and their rules (monotone or free).
 */
@Composable
fun LoCurveEditor(
    points: List<Offset>,
    curve: (Float) -> Float,
    onAddPoint: (x: Float) -> Int?,
    onMovePoint: (index: Int, x: Float, y: Float) -> Unit,
    onDeletePoint: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    curveColor: Color = LoColors.TextPrimary,
    histogram: FloatArray? = null,
    enabled: Boolean = true,
    label: String = "Curve",
) {
    val drag = remember { CurveDrag() }
    val callbacks = rememberUpdatedState(CurveCallbacks(points, onAddPoint, onMovePoint, onDeletePoint))
    val insetPx = with(LocalDensity.current) { INSET.toPx() }
    val touchPx = with(LocalDensity.current) { TOUCH.toPx() }
    val input = if (enabled) Modifier.pointerInput(Unit) { curveGestures(drag, callbacks, insetPx, touchPx) } else Modifier
    val active = drag.active
    val offGraph = drag.offGraph
    val dragging = drag.dragging
    val selected = drag.selected

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(LoShapes.Small)
                .background(LoColors.Surface2)
                .semantics {
                    contentDescription = label
                    stateDescription = "${points.size} points"
                }.then(input),
        ) {
            Canvas(Modifier.matchParentSize()) {
                val origin = Offset(insetPx, insetPx)
                val area = Size(size.width - 2 * insetPx, size.height - 2 * insetPx)
                drawGraph(origin, area, points, curve, curveColor, histogram, active, if (offGraph) active else -1, enabled)
                val focus = points.getOrNull(active)
                if (dragging && focus != null && !offGraph) {
                    drawMagnifier(origin, area, focus) {
                        drawGraph(origin, area, points, curve, curveColor, histogram, active, -1, enabled)
                    }
                }
            }
        }
        val shown = points.getOrNull(if (active >= 0) active else selected)
        Text(
            text =
                if (offGraph) {
                    "Release to delete the point"
                } else {
                    shown?.let(
                        LoCurveEditorMath::readout,
                    ) ?: "Tap the curve to add a point"
                },
            style = LoType.Caption,
            color = if (offGraph) LoColors.Danger else LoColors.TextSecondary,
            modifier = Modifier.padding(top = LoSpacing.Xxs),
        )
    }
}

/** What the editor is doing with a point (Compose state, read while drawing). */
private class CurveDrag {
    var active by mutableIntStateOf(-1)
    var selected by mutableIntStateOf(-1)
    var dragging by mutableStateOf(false)
    var offGraph by mutableStateOf(false)
}

private class CurveCallbacks(
    val points: List<Offset>,
    val add: (Float) -> Int?,
    val move: (Int, Float, Float) -> Unit,
    val delete: (Int) -> Unit,
)

/** Tap: add a point on the curve; drag a point: move it; drag it off the graph: delete it on release. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.curveGestures(
    drag: CurveDrag,
    callbacks: androidx.compose.runtime.State<CurveCallbacks>,
    insetPx: Float,
    touchPx: Float,
) {
    val w = size.width - 2 * insetPx
    val h = size.height - 2 * insetPx

    fun graph(p: Offset) = Offset((p.x - insetPx) / w, 1f - (p.y - insetPx) / h)
    awaitEachGesture {
        val down = awaitFirstDown()
        val cb = callbacks.value
        val g = graph(down.position)
        var index = LoCurveEditorMath.hit(cb.points, g.x, g.y, touchPx / w)
        if (index < 0 && g.x in 0f..1f) index = cb.add(g.x) ?: -1
        if (index < 0) return@awaitEachGesture
        drag.active = index
        drag.selected = index
        down.consume()
        var pressed = true
        while (pressed) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            pressed = change != null && change.pressed
            if (change != null && pressed && change.positionChange() != Offset.Zero) {
                drag.dragging = true
                val p = graph(change.position)
                drag.offGraph = LoCurveEditorMath.isOffGraph(p.x, p.y)
                if (!drag.offGraph) callbacks.value.move(index, p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))
                change.consume()
            }
        }
        if (drag.offGraph) {
            callbacks.value.delete(index)
            drag.selected = -1
        }
        drag.active = -1
        drag.dragging = false
        drag.offGraph = false
    }
}

@Suppress("LongParameterList")
private fun DrawScope.drawGraph(
    origin: Offset,
    area: Size,
    points: List<Offset>,
    curve: (Float) -> Float,
    curveColor: Color,
    histogram: FloatArray?,
    active: Int,
    deleting: Int,
    enabled: Boolean,
) {
    fun at(
        x: Float,
        y: Float,
    ) = Offset(origin.x + x * area.width, origin.y + (1f - y) * area.height)
    val stroke = 1.dp.toPx()
    if (histogram != null && histogram.isNotEmpty()) {
        val path = Path().apply { moveTo(origin.x, origin.y + area.height) }
        histogram.forEachIndexed { i, v ->
            path.lineTo(
                origin.x + area.width * i / (histogram.size - 1).coerceAtLeast(1),
                origin.y + area.height * (1f - v.coerceIn(0f, 1f)),
            )
        }
        path.lineTo(origin.x + area.width, origin.y + area.height)
        path.close()
        drawPath(path, LoColors.Outline.copy(alpha = HISTOGRAM_ALPHA))
    }
    for (i in 1 until GRID) {
        drawLine(LoColors.Outline, at(i / GRID.toFloat(), 0f), at(i / GRID.toFloat(), 1f), stroke)
        drawLine(LoColors.Outline, at(0f, i / GRID.toFloat()), at(1f, i / GRID.toFloat()), stroke)
    }
    drawLine(LoColors.Outline, at(0f, 0f), at(1f, 1f), stroke)
    val path = Path()
    for (i in 0..SAMPLES) {
        val x = i / SAMPLES.toFloat()
        val p = at(x, curve(x).coerceIn(0f, 1f))
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    drawPath(path, curveColor.copy(alpha = alpha), style = Stroke(2.dp.toPx()))
    points.forEachIndexed { i, p ->
        val c = at(p.x, p.y)
        val color = if (i == deleting) LoColors.Danger else LoColors.Thumb
        drawCircle(LoColors.Bg, (if (i == active) POINT_ACTIVE else POINT).toPx() + 2.dp.toPx(), c)
        drawCircle(color.copy(alpha = alpha), (if (i == active) POINT_ACTIVE else POINT).toPx(), c)
    }
}

/** A 3× loupe of the graph around [focus], drawn in the corner away from the point. */
private fun DrawScope.drawMagnifier(
    origin: Offset,
    area: Size,
    focus: Offset,
    content: DrawScope.() -> Unit,
) {
    val radius = MAGNIFIER.toPx() / 2
    val focusPx = Offset(origin.x + focus.x * area.width, origin.y + (1f - focus.y) * area.height)
    val centre =
        Offset(
            if (focus.x > HALF) {
                origin.x + radius + 4.dp.toPx()
            } else {
                size.width - origin.x - radius - 4.dp.toPx()
            },
            origin.y + radius + 4.dp.toPx(),
        )
    val circle =
        Path().apply {
            addOval(
                androidx.compose.ui.geometry
                    .Rect(centre, radius),
            )
        }
    clipPath(circle) {
        drawCircle(LoColors.Surface2, radius, centre)
        withTransform({
            translate(centre.x - focusPx.x * ZOOM, centre.y - focusPx.y * ZOOM)
            scale(ZOOM, ZOOM, Offset.Zero)
        }) { content() }
    }
    drawCircle(LoColors.TextPrimary, radius, centre, style = Stroke(MAGNIFIER_RING.toPx()))
}

private val INSET = 12.dp
private val TOUCH = 24.dp
private val POINT = 6.dp
private val POINT_ACTIVE = 9.dp
private val MAGNIFIER = 96.dp
private val MAGNIFIER_RING = 1.5.dp
private const val ZOOM = 3f
private const val HALF = 0.5f
private const val GRID = 4
private const val SAMPLES = 128
private const val HISTOGRAM_ALPHA = 0.6f
private const val DISABLED_ALPHA = 0.38f

@Preview(name = "LoCurveEditor", widthDp = 320, heightDp = 360)
@Composable
private fun LoCurveEditorPreview() {
    LineOverTheme {
        LoCurveEditor(
            points = listOf(Offset(0f, 0f), Offset(0.3f, 0.22f), Offset(0.7f, 0.8f), Offset(1f, 1f)),
            curve = { it },
            onAddPoint = { null },
            onMovePoint = { _, _, _ -> },
            onDeletePoint = {},
        )
    }
}
