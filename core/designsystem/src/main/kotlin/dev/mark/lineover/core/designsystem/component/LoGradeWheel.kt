package dev.mark.lineover.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.rememberLoHaptics
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Geometry of the luminance ring of [LoGradeWheel]: a 270° arc open at the bottom. */
@Suppress("MagicNumber") // a full turn and the -1..1 <-> 0..1 mapping
object LoGradeWheelMath {
    const val START_DEGREES = 135f
    const val SWEEP_DEGREES = 270f

    /** Ring angle (degrees, clockwise from +x as on screen) for luminance -1..1. */
    fun angleOf(luminance: Float): Float = START_DEGREES + (luminance.coerceIn(-1f, 1f) + 1f) / 2f * SWEEP_DEGREES

    /** Luminance -1..1 for a touch at (dx, dy) from the centre (screen y down). */
    fun luminanceAt(
        dx: Float,
        dy: Float,
    ): Float {
        var a = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        if (a < 0f) a += 360f
        var rel = a - START_DEGREES
        if (rel < 0f) rel += 360f
        // The gap at the bottom snaps to the nearer end.
        if (rel > SWEEP_DEGREES) rel = if (rel - SWEEP_DEGREES < (360f - SWEEP_DEGREES) / 2f) SWEEP_DEGREES else 0f
        return rel / SWEEP_DEGREES * 2f - 1f
    }
}

/**
 * A Color Grading wheel: drag inside for hue / saturation ([LoColorWheel]; double tap
 * the centre resets them), drag the outer ring for luminance (-1..1; double tap the ring
 * resets it).
 */
@Composable
fun LoGradeWheel(
    value: LoHueSat,
    luminance: Float,
    onValueChange: (LoHueSat) -> Unit,
    onLuminanceChange: (Float) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = rememberLoHaptics()
    val onLum by rememberUpdatedState(onLuminanceChange)
    val ring =
        if (enabled) {
            Modifier
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { offset ->
                        if (onRing(offset, size.width.toFloat())) {
                            onLum(0f)
                            haptics.confirm()
                        }
                    })
                }.pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        if (onRing(change.position, size.width.toFloat())) {
                            change.consume()
                            val c = size.width / 2f
                            onLum(LoGradeWheelMath.luminanceAt(change.position.x - c, change.position.y - c))
                        }
                    }
                }
        } else {
            Modifier
        }
    Box(
        modifier
            .widthIn(max = 280.dp)
            .aspectRatio(1f)
            .semantics {
                contentDescription = "$label luminance ring"
                stateDescription = "Luminance ${(luminance * PERCENT).roundToInt()} percent"
            }.then(ring),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = RING_WIDTH.toPx()
            val r = size.minDimension / 2 - stroke / 2 - KNOB.toPx()
            val topLeft = Offset(center.x - r, center.y - r)
            val arc = Size(2 * r, 2 * r)
            drawArc(
                LoColors.Outline,
                LoGradeWheelMath.START_DEGREES,
                LoGradeWheelMath.SWEEP_DEGREES,
                false,
                topLeft,
                arc,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val mid = LoGradeWheelMath.angleOf(0f)
            val at = LoGradeWheelMath.angleOf(luminance)
            val alpha = if (enabled) 1f else DISABLED_ALPHA
            drawArc(
                LoColors.Accent.copy(alpha = alpha),
                minOf(mid, at),
                kotlin.math.abs(at - mid),
                false,
                topLeft,
                arc,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val rad = Math.toRadians(at.toDouble())
            val knob = Offset(center.x + r * cos(rad).toFloat(), center.y + r * sin(rad).toFloat())
            drawCircle(LoColors.Bg, KNOB.toPx() + 2.dp.toPx(), knob)
            drawCircle(if (enabled) LoColors.Thumb else LoColors.TextDisabled, KNOB.toPx(), knob)
        }
        LoColorWheel(value, onValueChange, label, Modifier.padding(RING_WIDTH + KNOB * 2 + 4.dp), enabled = enabled)
    }
}

private fun onRing(
    p: Offset,
    width: Float,
): Boolean {
    val c = width / 2f
    val d = hypot(p.x - c, p.y - c)
    return d > c * INNER_FRACTION
}

private val RING_WIDTH = 6.dp
private val KNOB = 7.dp
private const val INNER_FRACTION = 0.78f
private const val PERCENT = 100
private const val DISABLED_ALPHA = 0.38f

@Preview(name = "LoGradeWheel", widthDp = 220, heightDp = 220)
@Composable
private fun LoGradeWheelPreview() {
    LineOverTheme { LoGradeWheel(LoHueSat(200f, 0.5f), -0.3f, {}, {}, "Shadows") }
}
