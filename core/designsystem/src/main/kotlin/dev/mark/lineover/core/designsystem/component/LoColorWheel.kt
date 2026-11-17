package dev.mark.lineover.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
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

/** Hue/saturation of a point on a [LoColorWheel]: hue in degrees (0..360), saturation 0..1. */
data class LoHueSat(
    val hue: Float,
    val saturation: Float,
)

/**
 * Wheel geometry, free of Compose so it can be unit-tested. Angles follow the screen:
 * 0 deg points right and angles grow clockwise, matching the sweep gradient drawn.
 */
object LoColorWheelMath {
    private const val FULL_TURN = 360f

    fun hueSatAt(
        x: Float,
        y: Float,
        centerX: Float,
        centerY: Float,
        radius: Float,
    ): LoHueSat {
        val dx = x - centerX
        val dy = y - centerY
        val degrees = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        val hue = (degrees + FULL_TURN) % FULL_TURN
        val saturation = if (radius > 0f) (hypot(dx, dy) / radius).coerceIn(0f, 1f) else 0f
        return LoHueSat(if (saturation == 0f) 0f else hue, saturation)
    }

    fun positionOf(
        value: LoHueSat,
        centerX: Float,
        centerY: Float,
        radius: Float,
    ): Offset {
        val radians = Math.toRadians(value.hue.toDouble())
        val distance = value.saturation.coerceIn(0f, 1f) * radius
        return Offset(centerX + (cos(radians) * distance).toFloat(), centerY + (sin(radians) * distance).toFloat())
    }
}

/**
 * Colour wheel for colour grading: the angle picks the hue, the distance from the centre
 * the saturation. Touching or dragging moves the puck directly to the finger (no
 * animation); a double tap resets to the centre (no tint).
 */
@Composable
fun LoColorWheel(
    value: LoHueSat,
    onValueChange: (LoHueSat) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: () -> Unit = {},
) {
    val haptics = rememberLoHaptics()
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)
    val input =
        if (enabled) {
            Modifier
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            onChange(LoHueSat(0f, 0f))
                            haptics.confirm()
                            onFinished()
                        },
                        onTap = { offset ->
                            onChange(hueSatAt(offset, size.width.toFloat(), size.height.toFloat()))
                            onFinished()
                        },
                    )
                }.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset -> onChange(hueSatAt(offset, size.width.toFloat(), size.height.toFloat())) },
                        onDragEnd = { onFinished() },
                        onDragCancel = { onFinished() },
                    ) { change, _ ->
                        change.consume()
                        onChange(hueSatAt(change.position, size.width.toFloat(), size.height.toFloat()))
                    }
                }
        } else {
            Modifier
        }
    Canvas(
        modifier =
            modifier
                .widthIn(max = 280.dp)
                .aspectRatio(1f)
                .semantics {
                    contentDescription = label
                    stateDescription = "Hue ${value.hue.roundToInt()} degrees, saturation ${(value.saturation * 100).roundToInt()} percent"
                    if (!enabled) disabled()
                }.then(input),
    ) {
        val radius = size.minDimension / 2 - PUCK_RADIUS.toPx() - RING.toPx()
        val alpha = if (enabled) 1f else DISABLED_ALPHA
        drawCircle(Brush.sweepGradient(LoColors.HueStops, center), radius, center, alpha = alpha)
        drawCircle(Brush.radialGradient(listOf(LoColors.WheelCenter, Color.Transparent), center, radius), radius, center, alpha = alpha)
        drawCircle(LoColors.Outline, radius, center, style = Stroke(RING.toPx()))
        val puck = LoColorWheelMath.positionOf(value, center.x, center.y, radius)
        drawCircle(LoColors.Bg, PUCK_RADIUS.toPx() + RING.toPx(), puck)
        drawCircle(if (enabled) LoColors.Thumb else LoColors.TextDisabled, PUCK_RADIUS.toPx(), puck)
    }
}

private fun hueSatAt(
    offset: Offset,
    width: Float,
    height: Float,
): LoHueSat {
    val radius = minOf(width, height) / 2
    return LoColorWheelMath.hueSatAt(offset.x, offset.y, width / 2, height / 2, radius)
}

private val PUCK_RADIUS = 8.dp
private val RING = 2.dp
private const val DISABLED_ALPHA = 0.38f

@Preview(name = "LoColorWheel", widthDp = 200, heightDp = 200)
@Composable
private fun LoColorWheelPreview() {
    LineOverTheme {
        LoColorWheel(value = LoHueSat(30f, 0.6f), onValueChange = {}, label = "Highlights tint")
    }
}
