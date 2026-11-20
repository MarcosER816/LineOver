package dev.mark.lineover.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoChip
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.designsystem.component.LoSegmented
import dev.mark.lineover.core.designsystem.component.LoSlider
import dev.mark.lineover.core.designsystem.component.LoSliderFill
import dev.mark.lineover.core.designsystem.component.LoSwitchRow
import dev.mark.lineover.core.model.SelectionOp
import dev.mark.lineover.core.ui.tools.ToolRegistry
import kotlin.math.roundToInt

/** Test tags of the Select panel (Stage 15). */
object SelectTags {
    const val TO_MASK = "select-to-mask"
    const val TO_LAYER = "select-to-layer"
    const val TOLERANCE = "select-tolerance"
}

/**
 * A Select tool (Stage 15): what one finger does on the canvas, the tool's options, then the
 * selection operations (grow / shrink, feather, smooth, border, invert) and what the selection
 * becomes (a mask, a new layer; fill is Stage 18).
 */
@Composable
internal fun SelectPanel(
    toolId: String,
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    onClose: () -> Unit,
) {
    val title = requireNotNull(ToolRegistry[toolId]).name
    val settings = state.selectionSettings
    val select = { action: SelectAction -> onIntent(EditorIntent.Select(action)) }
    val set = { next: SelectionSettings -> select(SelectAction.SetSettings(next)) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = LoSpacing.M), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = LoType.Title, color = LoColors.TextPrimary, modifier = Modifier.weight(1f))
            LoIconButton(LoIcons.Close, "Close $title", onClick = onClose)
        }
        Column(
            Modifier.padding(horizontal = LoSpacing.M).testTag(EditorTags.slot(toolId)),
            verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
        ) {
            Text(
                if (toolId in
                    SmartTools.ALL
                ) {
                    smartHint(toolId)
                } else {
                    hintOf(toolId, settings)
                },
                style = LoType.Caption,
                color = LoColors.TextSecondary,
            )
            if (toolId in SmartTools.ALL) SmartToolOptions(toolId, state, onIntent)
            when (toolId) {
                SelectTools.MARQUEE -> {
                    LoSegmented(
                        options = MarqueeShape.entries.map { it.name },
                        selectedIndex = settings.marquee.ordinal,
                        onSelect = { set(settings.copy(marquee = MarqueeShape.entries[it])) },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
                        MARQUEE_RATIOS.forEach { (label, ratio) ->
                            LoChip(label, selected = settings.ratio == ratio, onClick = { set(settings.copy(ratio = ratio)) })
                        }
                    }
                }

                SelectTools.LASSO -> {
                    LoSwitchRow(
                        "Loupe",
                        checked = settings.loupe,
                        onCheckedChange = { set(settings.copy(loupe = it)) },
                        description = "A magnified view above your finger",
                    )
                }

                SelectTools.POLYGON -> {
                    LoChip(
                        "Close shape",
                        selected = false,
                        onClick = { select(SelectAction.ClosePolygon) },
                        enabled = settings.polygonOpen,
                    )
                }

                SelectTools.WAND -> {
                    WandOptions(settings, select, set)
                }

                SelectTools.COLOR_RANGE -> {
                    LoSlider(
                        value = settings.fuzziness,
                        onValueChange = { select(SelectAction.SetFuzziness(it)) },
                        label = "Fuzziness",
                        valueRange = 1f..SelectionSettings.MAX_TOLERANCE,
                        defaultValue = SelectionSettings.DEFAULT_FUZZINESS,
                        fill = LoSliderFill.FromStart,
                        valueText = { "${it.roundToInt()}" },
                        onValueChangeFinished = { onIntent(EditorIntent.EndEdit) },
                    )
                    LoSegmented(
                        options = listOf("Add colours", "Subtract colours"),
                        selectedIndex = if (settings.pickSubtract) 1 else 0,
                        onSelect = { set(settings.copy(pickSubtract = it == 1)) },
                    )
                }
            }
            SelectionOps(state, select, set)
        }
    }
}

@Composable
private fun WandOptions(
    settings: SelectionSettings,
    select: (SelectAction) -> Unit,
    set: (SelectionSettings) -> Unit,
) {
    // Moving the slider re-runs the last wand click: a live preview of the tolerance.
    LoSlider(
        value = settings.tolerance,
        onValueChange = { select(SelectAction.SetTolerance(it)) },
        label = "Tolerance (ΔE)",
        valueRange = 0f..SelectionSettings.MAX_TOLERANCE,
        defaultValue = SelectionSettings.DEFAULT_TOLERANCE,
        fill = LoSliderFill.FromStart,
        valueText = { "${it.roundToInt()}" },
        modifier = Modifier.testTag(SelectTags.TOLERANCE),
    )
    LoSwitchRow("Contiguous", checked = settings.contiguous, onCheckedChange = { set(settings.copy(contiguous = it)) })
    LoSwitchRow("Sample all layers", checked = settings.sampleAllLayers, onCheckedChange = { set(settings.copy(sampleAllLayers = it)) })
}

@Composable
private fun SelectionOps(
    state: EditorState,
    select: (SelectAction) -> Unit,
    set: (SelectionSettings) -> Unit,
) {
    val settings = state.selectionSettings
    val has = state.selection != null
    val px = settings.amount
    Text("Selection", style = LoType.Caption, color = LoColors.TextSecondary)
    LoSlider(
        value = px,
        onValueChange = { set(settings.copy(amount = it)) },
        label = "Amount",
        valueRange = 1f..SelectionSettings.MAX_AMOUNT,
        defaultValue = SelectionSettings.DEFAULT_AMOUNT,
        fill = LoSliderFill.FromStart,
        valueText = { "${it.roundToInt()} px" },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        LoChip("Grow", selected = false, onClick = { select(SelectAction.Apply(SelectionOp.Grow(px.rounded()))) }, enabled = has)
        LoChip("Shrink", selected = false, onClick = { select(SelectAction.Apply(SelectionOp.Grow(-px.rounded()))) }, enabled = has)
        LoChip("Feather", selected = false, onClick = { select(SelectAction.Apply(SelectionOp.Feather(px.rounded()))) }, enabled = has)
        LoChip("Smooth", selected = false, onClick = { select(SelectAction.Apply(SelectionOp.Smooth(px.rounded()))) }, enabled = has)
        LoChip("Border", selected = false, onClick = { select(SelectAction.Apply(SelectionOp.Border(px.rounded()))) }, enabled = has)
        LoChip("Invert", selected = false, onClick = { select(SelectAction.Invert) }, enabled = has)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        LoChip(
            "To mask",
            selected = false,
            onClick = { select(SelectAction.ToMask) },
            enabled = has,
            modifier = Modifier.testTag(SelectTags.TO_MASK),
            leadingIcon = LoIcons.Gradient,
        )
        LoChip(
            "To new layer",
            selected = false,
            onClick = { select(SelectAction.ToNewLayer) },
            enabled = has,
            modifier = Modifier.testTag(SelectTags.TO_LAYER),
            leadingIcon = LoIcons.Layers,
        )
        LoChip("Fill · Stage 18", selected = false, onClick = {}, enabled = false, leadingIcon = LoIcons.FormatColorFill)
    }
}

private fun Float.rounded(): Float = roundToInt().toFloat()

private fun hintOf(
    toolId: String,
    settings: SelectionSettings,
): String =
    when (toolId) {
        SelectTools.MARQUEE -> {
            "Drag on the photo. Tap outside the selection to deselect."
        }

        SelectTools.LASSO -> {
            "Draw around the area with one finger; it closes itself. Tap outside to deselect."
        }

        SelectTools.POLYGON -> {
            if (settings.polygonOpen) "Tap the next corner; tap the first one to close." else "Tap each corner of the shape."
        }

        SelectTools.WAND -> {
            "Tap a colour: similar colours around it are selected."
        }

        else -> {
            "Tap colours to ${if (settings.pickSubtract) "remove from" else "add to"} the selection."
        }
    }
