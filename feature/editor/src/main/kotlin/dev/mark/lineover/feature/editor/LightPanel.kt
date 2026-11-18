package dev.mark.lineover.feature.editor

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoChip
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.designsystem.component.LoSlider
import dev.mark.lineover.core.designsystem.component.LoSliderDefaults
import dev.mark.lineover.core.model.LightParam

/**
 * The Light panel (Stage 5): Auto, the sliders in the fixed order (Exposure, Contrast,
 * Highlights, Shadows, Whites, Blacks, Brightness) and the swipe-mode switch.
 *
 * Collapsed (phone, low detent) it shows only the selected slider; swiping up on the
 * panel (or "All sliders") shows all of them. Double tap a slider to reset it; a second
 * finger on a slider shows the clipping warnings while it stays down.
 */
@Composable
internal fun LightPanel(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    showAll: Boolean,
    onShowAll: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val showAllNow by rememberUpdatedState(onShowAll)
    var dragged by remember { mutableFloatStateOf(0f) }
    val thresholdPx = with(LocalDensity.current) { SWIPE_UP_THRESHOLD.toPx() }
    val swipeUp =
        if (!showAll && onShowAll != null) {
            Modifier.draggable(
                state = rememberDraggableState { delta -> dragged += delta },
                orientation = Orientation.Vertical,
                onDragStarted = { dragged = 0f },
                onDragStopped = { if (dragged < -thresholdPx) showAllNow?.invoke() },
            )
        } else {
            Modifier
        }
    Column(Modifier.fillMaxWidth().then(swipeUp).testTag(EditorTags.LIGHT_PANEL)) {
        Row(
            Modifier.fillMaxWidth().padding(start = LoSpacing.M),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
        ) {
            Text("Light", style = LoType.Title, color = LoColors.TextPrimary, modifier = Modifier.weight(1f))
            LoChip(
                "Auto",
                selected = false,
                onClick = { onIntent(EditorIntent.AutoLight) },
                leadingIcon = LoIcons.AutoFixHigh,
                modifier = Modifier.testTag(EditorTags.LIGHT_AUTO),
            )
            LoIconButton(
                LoIcons.Gesture,
                if (state.swipeMode) "Swipe mode on" else "Swipe mode off",
                onClick = { onIntent(EditorIntent.ToggleSwipeMode) },
                selected = state.swipeMode,
                modifier = Modifier.testTag(EditorTags.LIGHT_SWIPE),
            )
            LoIconButton(LoIcons.Close, "Close Light", onClick = onClose)
        }
        val params = if (showAll) LightParam.entries else listOf(state.lightParam)
        params.forEach { param -> LightSlider(param, state, onIntent) }
        if (!showAll && onShowAll != null) {
            TextButton(onClick = onShowAll, modifier = Modifier.padding(start = LoSpacing.Xs)) {
                Text("All sliders", style = LoType.Body, color = LoColors.Accent)
            }
        }
    }
}

@Composable
private fun LightSlider(
    param: LightParam,
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    LoSlider(
        value = state.light[param],
        onValueChange = { onIntent(EditorIntent.SetLight(param, it)) },
        label = param.label,
        modifier = Modifier.testTag(EditorTags.slot(param.toolId)),
        valueRange = param.min..param.max,
        defaultValue = param.default,
        step = param.step,
        valueText = { value ->
            if (param == LightParam.Exposure) {
                LoSliderDefaults.formatValue(value, param.step, signed = true) + " EV"
            } else {
                LoSliderDefaults.formatValue(value, param.step, signed = true)
            }
        },
        onSecondaryPointerChange = { down -> onIntent(EditorIntent.ClippingPreview(down)) },
    )
}

private val SWIPE_UP_THRESHOLD = 24.dp
