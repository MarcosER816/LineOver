package dev.mark.lineover.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoHaptics
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.rememberLoHaptics
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Where the accent fill of a [LoSlider] starts. */
enum class LoSliderFill {
    /** From the default value (centred-zero sliders such as Exposure or Temperature offsets). */
    FromDefault,

    /** From the minimum (sliders that start at zero, such as Grain amount). */
    FromStart,
}

/** Track of a [LoSlider]: plain, or a gradient that shows what the value does. */
enum class LoSliderTrack(
    internal val colors: List<Color>?,
) {
    Plain(null),
    Temperature(listOf(LoColors.TrackTemperatureCool, LoColors.TrackTemperatureWarm)),
    Tint(listOf(LoColors.TrackTintGreen, LoColors.TrackTintMagenta)),
    Hue(LoColors.HueStops),
}

object LoSliderDefaults {
    val MinHeight = 56.dp
    internal val TrackHeight = 28.dp
    internal val TrackStroke = 4.dp
    internal val ThumbRadius = 8.dp
    internal val ThumbRadiusActive = 11.dp
    internal val ThumbRing = 2.dp
    internal val DefaultTickHeight = 10.dp
    internal val DefaultTickWidth = 2.dp

    /** Taps closer than this to the thumb do not step the value. */
    internal val ThumbTouchRadius = 24.dp
    private const val MAX_DECIMALS = 4
    private const val DECIMAL_EPSILON = 1e-6
    private const val DECIMAL_BASE = 10

    /** Number of decimals that [step] needs (0.05 -> 2); continuous sliders show 2. */
    fun decimalsFor(step: Float): Int {
        if (step <= 0f) return 2
        var scaled = step.toDouble()
        var decimals = 0
        while (decimals < MAX_DECIMALS && abs(scaled - scaled.roundToLong()) > DECIMAL_EPSILON) {
            scaled *= DECIMAL_BASE
            decimals++
        }
        return decimals
    }

    /** Formats [value] with the decimals of [step]; [signed] adds "+" to positive values. */
    fun formatValue(
        value: Float,
        step: Float,
        signed: Boolean,
    ): String {
        val text = "%.${decimalsFor(step)}f".format(Locale.ROOT, value)
        val isZero = text.trimStart('-').all { it == '0' || it == '.' }
        return when {
            isZero -> text.trimStart('-')
            signed && value > 0f -> "+$text"
            else -> text
        }
    }
}

/**
 * Value slider of the editor.
 *
 * - The whole row (label, value and track) is the touch target; dragging is relative,
 *   so the thumb follows the finger's movement and never jumps.
 * - Fine mode: move the finger at least two row heights below the slider and it moves
 *   at a quarter of the speed.
 * - A tap left or right of the thumb steps the value by one [step]; a double tap resets
 *   it to [defaultValue] (haptic confirm). Passing the default gives a haptic detent.
 * - Values are never animated: the thumb is drawn exactly where the value is.
 * - [onSecondaryPointerChange] reports a second finger touching the screen during the
 *   gesture, used by the editor for the clipping preview.
 */
@Composable
fun LoSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = -100f..100f,
    defaultValue: Float = 0f,
    step: Float = 1f,
    fill: LoSliderFill = LoSliderFill.FromDefault,
    track: LoSliderTrack = LoSliderTrack.Plain,
    enabled: Boolean = true,
    valueText: (Float) -> String = { LoSliderDefaults.formatValue(it, step, signed = fill == LoSliderFill.FromDefault) },
    onValueChangeFinished: () -> Unit = {},
    onSecondaryPointerChange: (Boolean) -> Unit = {},
) {
    val model =
        remember(valueRange, step, defaultValue) {
            LoSliderModel(valueRange.start, valueRange.endInclusive, step, defaultValue)
        }
    val haptics = rememberLoHaptics()
    val currentValue by rememberUpdatedState(value)
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)
    val onSecondary by rememberUpdatedState(onSecondaryPointerChange)
    var dragging by remember { mutableStateOf(false) }
    val sidePaddingPx = with(LocalDensity.current) { LoSpacing.M.toPx() }
    val shown = model.coerce(value)
    val text = valueText(shown)
    val steps = if (step > 0f) ((model.span / step).roundToLong() - 1).coerceAtLeast(0).toInt() else 0

    val gestures =
        if (enabled) {
            Modifier.pointerInput(model, sidePaddingPx) {
                sliderGestures(
                    model = model,
                    sidePaddingPx = sidePaddingPx,
                    haptics = haptics,
                    currentValue = { currentValue },
                    onValueChange = { onChange(it) },
                    onFinished = { onFinished() },
                    onSecondary = { onSecondary(it) },
                    onDragging = { dragging = it },
                )
            }
        } else {
            Modifier
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = LoSliderDefaults.MinHeight)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    stateDescription = text
                    progressBarRangeInfo = ProgressBarRangeInfo(shown, valueRange, steps)
                    if (enabled) {
                        setProgress { target ->
                            onChange(model.coerce(target))
                            onFinished()
                            true
                        }
                    } else {
                        disabled()
                    }
                }.then(gestures)
                .padding(horizontal = LoSpacing.M),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = LoSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = LoType.Body,
                color = if (enabled) LoColors.TextSecondary else LoColors.TextDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = text,
                style = LoType.Body,
                color =
                    when {
                        !enabled -> LoColors.TextDisabled
                        dragging -> LoColors.Accent
                        else -> LoColors.TextPrimary
                    },
            )
        }
        Canvas(Modifier.fillMaxWidth().height(LoSliderDefaults.TrackHeight)) {
            drawSliderTrack(model, shown, fill, track, enabled, dragging)
        }
    }
}

@Suppress("LongParameterList")
private fun DrawScope.drawSliderTrack(
    model: LoSliderModel,
    value: Float,
    fill: LoSliderFill,
    track: LoSliderTrack,
    enabled: Boolean,
    dragging: Boolean,
) {
    val y = size.height / 2
    val stroke = LoSliderDefaults.TrackStroke.toPx()
    val thumbX = model.fraction(value) * size.width
    val trackAlpha = if (enabled) 1f else DISABLED_ALPHA
    val colors = track.colors
    if (colors == null) {
        drawLine(LoColors.Outline, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
        val originX = if (fill == LoSliderFill.FromDefault) model.fraction(model.default) * size.width else 0f
        drawLine(
            color = if (enabled) LoColors.Accent else LoColors.TextDisabled,
            start = Offset(originX, y),
            end = Offset(thumbX, y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    } else {
        drawLine(
            brush = Brush.horizontalGradient(colors, startX = 0f, endX = size.width),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
            alpha = trackAlpha,
        )
    }
    if (fill == LoSliderFill.FromDefault && model.default > model.min && model.default < model.max) {
        val tickX = model.fraction(model.default) * size.width
        val half = LoSliderDefaults.DefaultTickHeight.toPx() / 2
        drawLine(
            color = if (enabled) LoColors.TextSecondary else LoColors.TextDisabled,
            start = Offset(tickX, y - half),
            end = Offset(tickX, y + half),
            strokeWidth = LoSliderDefaults.DefaultTickWidth.toPx(),
        )
    }
    val radius = (if (dragging) LoSliderDefaults.ThumbRadiusActive else LoSliderDefaults.ThumbRadius).toPx()
    val ring = LoSliderDefaults.ThumbRing.toPx()
    drawCircle(LoColors.Bg, radius + ring, Offset(thumbX, y))
    drawCircle(if (enabled) LoColors.Thumb else LoColors.TextDisabled, radius, Offset(thumbX, y))
    if (dragging) drawCircle(LoColors.Accent, radius, Offset(thumbX, y), style = Stroke(ring))
}

/**
 * Gesture loop of [LoSlider]. A mostly vertical movement is left to the parent (so a
 * panel full of sliders still scrolls); a horizontal one becomes a relative drag.
 * One explicit loop keeps the tap / drag / scroll / second-finger decisions in one place.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod", "LongMethod", "LoopWithTooManyJumpStatements")
private suspend fun PointerInputScope.sliderGestures(
    model: LoSliderModel,
    sidePaddingPx: Float,
    haptics: LoHaptics,
    currentValue: () -> Float,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit,
    onSecondary: (Boolean) -> Unit,
    onDragging: (Boolean) -> Unit,
) {
    var lastTapUptime = Long.MIN_VALUE / 2
    var lastTapX = 0f
    val doubleTapSlop = LoSliderDefaults.ThumbTouchRadius.toPx() * 2
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val trackWidth = size.width - 2 * sidePaddingPx
        val rowHeight = size.height.toFloat()
        var drag: LoSliderModel.Drag? = null
        var secondary = false
        var hadSecondary = false
        var abandoned = false
        var upX = down.position.x
        var upTime = down.uptimeMillis
        var released = false
        var totalX = 0f
        var totalY = 0f
        while (true) {
            val event = awaitPointerEvent()
            val pressedCount = event.changes.count { it.pressed }
            if ((pressedCount >= 2) != secondary) {
                secondary = pressedCount >= 2
                hadSecondary = hadSecondary || secondary
                onSecondary(secondary)
            }
            val main = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!main.pressed) {
                released = true
                upX = main.position.x
                upTime = main.uptimeMillis
                break
            }
            val delta = main.positionChange()
            val active = drag
            if (active == null) {
                totalX += delta.x
                totalY += delta.y
                if (abs(totalX) > viewConfiguration.touchSlop && abs(totalX) >= abs(totalY)) {
                    drag = model.startDrag(currentValue())
                    onDragging(true)
                    main.consume()
                } else if (abs(totalY) > viewConfiguration.touchSlop) {
                    abandoned = true
                    break
                }
            } else {
                val verticalOffset = main.position.y - rowHeight / 2
                if (active.dragBy(delta.x, trackWidth, verticalOffset, rowHeight)) haptics.tick()
                onValueChange(active.value)
                main.consume()
            }
        }
        if (secondary) onSecondary(false)
        when {
            drag != null -> {
                onDragging(false)
                onFinished()
            }

            abandoned || !released || hadSecondary -> {
                // A scroll, a cancelled gesture or a two-finger hold: not a tap.
            }

            upTime - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis -> {
                // A long press without movement: not a tap.
            }

            upTime - lastTapUptime <= viewConfiguration.doubleTapTimeoutMillis && abs(upX - lastTapX) < doubleTapSlop -> {
                onValueChange(model.reset())
                haptics.confirm()
                onFinished()
                lastTapUptime = Long.MIN_VALUE / 2
            }

            else -> {
                val thumbX = sidePaddingPx + model.fraction(currentValue()) * trackWidth
                val reach = LoSliderDefaults.ThumbTouchRadius.toPx()
                val direction =
                    when {
                        upX < thumbX - reach -> -1
                        upX > thumbX + reach -> 1
                        else -> 0
                    }
                if (direction != 0) {
                    onValueChange(model.stepBy(currentValue(), direction))
                    onFinished()
                }
                lastTapUptime = upTime
                lastTapX = upX
            }
        }
    }
}

private const val DISABLED_ALPHA = 0.38f

@Preview(name = "LoSlider", widthDp = 360)
@Composable
private fun LoSliderPreview() {
    LineOverTheme {
        Column(Modifier.background(LoColors.Surface1)) {
            LoSlider(value = 0f, onValueChange = {}, label = "Exposure", valueRange = -5f..5f, step = 0.01f)
            LoSlider(value = 35f, onValueChange = {}, label = "Contrast")
            LoSlider(value = 20f, onValueChange = {}, label = "Grain", valueRange = 0f..100f, fill = LoSliderFill.FromStart)
            LoSlider(value = -12f, onValueChange = {}, label = "Temperature", track = LoSliderTrack.Temperature)
            LoSlider(value = 180f, onValueChange = {}, label = "Hue", valueRange = 0f..360f, defaultValue = 180f, track = LoSliderTrack.Hue)
            LoSlider(value = 10f, onValueChange = {}, label = "Disabled", enabled = false)
        }
    }
}
