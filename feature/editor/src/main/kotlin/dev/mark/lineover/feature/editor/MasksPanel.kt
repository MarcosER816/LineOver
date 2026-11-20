package dev.mark.lineover.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoChip
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.designsystem.component.LoSegmented
import dev.mark.lineover.core.designsystem.component.LoSlider
import dev.mark.lineover.core.designsystem.component.LoSliderFill
import dev.mark.lineover.core.designsystem.component.LoSwitchRow
import dev.mark.lineover.core.model.Layer
import dev.mark.lineover.core.model.LayerMask
import dev.mark.lineover.core.model.LayerMath
import dev.mark.lineover.core.model.MaskPaint
import dev.mark.lineover.core.ui.tools.ToolRegistry
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas as ComposeCanvas

/** Test tags of the mask controls (Stage 14). */
object MaskTags {
    fun thumbnail(id: String) = "mask-thumbnail:$id"

    const val SWAP = "mask-swap"
    const val CONTROLS = "mask-controls"
}

private fun send(
    onIntent: (EditorIntent) -> Unit,
    action: LayerAction,
) = onIntent(EditorIntent.Layers(action))

/**
 * The selected layer's mask and clipping (Stage 14): add / delete the mask, clip to the layer
 * below, and, while the mask is targeted (its thumbnail tapped), the brush, the gradient, the
 * paint colour swap, density, feather, invert, fill, the red overlay and the mask-only view.
 * The vector mask stays reserved for Stage 24.
 */
@Composable
internal fun MaskSection(
    state: EditorState,
    layer: Layer?,
    onIntent: (EditorIntent) -> Unit,
) {
    val editable = layer != null && !layer.locked
    Row(
        Modifier.fillMaxWidth().testTag(EditorTags.slot("layers.layer_mask")),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
    ) {
        Text("Layer mask", style = LoType.Body, color = LoColors.TextPrimary, modifier = Modifier.weight(1f))
        if (layer?.mask == null) {
            LoChip(
                "Add",
                selected = false,
                onClick = { layer?.let { send(onIntent, LayerAction.AddMask(it.id, black = false)) } },
                enabled = editable,
            )
            LoChip("Hide all", selected = false, onClick = {
                layer?.let { send(onIntent, LayerAction.AddMask(it.id, black = true)) }
            }, enabled = editable)
        } else {
            LoChip(
                "Edit",
                selected = state.maskTarget,
                onClick = { send(onIntent, LayerAction.TargetMask(layer.id, !state.maskTarget)) },
            )
            LoChip("Delete", selected = false, onClick = {
                send(onIntent, LayerAction.DeleteMask(layer.id))
            }, enabled = editable, leadingIcon = LoIcons.Delete)
        }
    }
    Box(Modifier.testTag(EditorTags.slot("layers.clipping_mask"))) {
        LoSwitchRow(
            "Clip to the layer below",
            checked = layer?.clip == true,
            onCheckedChange = { on -> layer?.let { send(onIntent, LayerAction.SetClip(it.id, on)) } },
            description =
                layer?.let { l ->
                    LayerMath.clippingBase(state.graph, l.id)?.let { "Shows only where ${it.name.ifBlank { it.type.label }} is" }
                }
                    ?: "Shows only where the layer below is",
            enabled = editable && (layer?.clip == true || LayerMath.canClip(state.graph, layer?.id.orEmpty())),
        )
    }
    val vector = requireNotNull(ToolRegistry["layers.vector_mask"])
    LoChip(
        "${vector.name} · Stage ${vector.stage}",
        selected = false,
        onClick = { onIntent(EditorIntent.SelectTool(vector.id)) },
        modifier = Modifier.testTag(EditorTags.slot(vector.id)),
    )
    val mask = layer?.mask
    if (layer != null && mask != null && state.maskTarget) MaskControls(state, layer, mask, editable, onIntent)
}

@Composable
private fun MaskControls(
    state: EditorState,
    layer: Layer,
    mask: LayerMask,
    editable: Boolean,
    onIntent: (EditorIntent) -> Unit,
) {
    val brush = state.maskBrush
    val end = { onIntent(EditorIntent.EndEdit) }
    Column(Modifier.testTag(MaskTags.CONTROLS), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        Text("One finger paints the mask; two fingers move the photo", style = LoType.Caption, color = LoColors.TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
            LoSegmented(
                options = listOf("Brush", "Gradient"),
                selectedIndex = brush.tool.ordinal,
                onSelect = { send(onIntent, LayerAction.SetBrush(brush.copy(tool = MaskTool.entries[it]))) },
                modifier = Modifier.weight(1f),
            )
            PaintSwatch(brush.white)
            LoIconButton(
                LoIcons.Sync,
                if (brush.white) "Paint black instead" else "Paint white instead",
                onClick = { send(onIntent, LayerAction.SwapPaint) },
                modifier = Modifier.testTag(MaskTags.SWAP),
            )
        }
        Text(
            if (brush.white) "Painting white: shows the layer" else "Painting black: hides the layer",
            style = LoType.Caption,
            color = LoColors.TextSecondary,
        )
        if (brush.tool == MaskTool.Brush) {
            val longEdge = state.canvasSize?.let { (w, h) -> maxOf(w, h) } ?: DEFAULT_EDGE
            val maxRadius = (longEdge / 2f).coerceIn(MIN_RADIUS_RANGE, MaskBrush.MAX_RADIUS)
            LoSlider(
                value = brush.radiusFor(longEdge).coerceAtMost(maxRadius),
                onValueChange = { send(onIntent, LayerAction.SetBrush(brush.copy(radius = it))) },
                label = "Size",
                valueRange = 1f..maxRadius,
                defaultValue = (longEdge * MaskBrush.AUTO_RADIUS).coerceIn(1f, maxRadius),
                fill = LoSliderFill.FromStart,
                valueText = { "${it.roundToInt()} px" },
            )
            LoSlider(
                value = brush.hardness,
                onValueChange = { send(onIntent, LayerAction.SetBrush(brush.copy(hardness = it))) },
                label = "Hardness",
                valueRange = 0f..LayerMask.FULL,
                defaultValue = MaskBrush.DEFAULT_HARDNESS,
                fill = LoSliderFill.FromStart,
                valueText = { "${it.roundToInt()} %" },
            )
            LoSlider(
                value = brush.opacity,
                onValueChange = { send(onIntent, LayerAction.SetBrush(brush.copy(opacity = it))) },
                label = "Brush opacity",
                valueRange = 1f..LayerMask.FULL,
                defaultValue = LayerMask.FULL,
                fill = LoSliderFill.FromStart,
                valueText = { "${it.roundToInt()} %" },
            )
        }
        LoSlider(
            value = mask.density,
            onValueChange = { send(onIntent, LayerAction.SetMaskDensity(layer.id, it)) },
            label = "Density",
            valueRange = 0f..LayerMask.FULL,
            defaultValue = LayerMask.FULL,
            fill = LoSliderFill.FromStart,
            enabled = editable,
            valueText = { "${it.roundToInt()} %" },
            onValueChangeFinished = end,
        )
        LoSlider(
            value = mask.feather,
            onValueChange = { send(onIntent, LayerAction.SetMaskFeather(layer.id, it)) },
            label = "Feather",
            valueRange = 0f..LayerMask.MAX_FEATHER,
            defaultValue = 0f,
            fill = LoSliderFill.FromStart,
            enabled = editable,
            valueText = { "${it.roundToInt()} px" },
            onValueChangeFinished = end,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
            LoChip(
                "Red overlay",
                selected = state.maskView == MaskView.Overlay,
                onClick = {
                    send(
                        onIntent,
                        LayerAction.SetMaskView(
                            if (state.maskView ==
                                MaskView.Overlay
                            ) {
                                MaskView.None
                            } else {
                                MaskView.Overlay
                            },
                        ),
                    )
                },
            )
            LoChip(
                "Mask only",
                selected = state.maskView == MaskView.MaskOnly,
                onClick = {
                    send(
                        onIntent,
                        LayerAction.SetMaskView(
                            if (state.maskView ==
                                MaskView.MaskOnly
                            ) {
                                MaskView.None
                            } else {
                                MaskView.MaskOnly
                            },
                        ),
                    )
                },
            )
            LoChip("Invert", selected = mask.inverted, onClick = { send(onIntent, LayerAction.InvertMask(layer.id)) }, enabled = editable)
            LoChip(
                "Fill white",
                selected = false,
                onClick = { send(onIntent, LayerAction.FillMask(layer.id, white = true)) },
                enabled = editable,
            )
            LoChip(
                "Fill black",
                selected = false,
                onClick = { send(onIntent, LayerAction.FillMask(layer.id, white = false)) },
                enabled = editable,
            )
            LoChip(
                if (mask.enabled) "Disable" else "Enable",
                selected = !mask.enabled,
                onClick = { send(onIntent, LayerAction.SetMaskEnabled(layer.id, !mask.enabled)) },
            )
        }
    }
}

@Composable
private fun PaintSwatch(white: Boolean) {
    Box(
        Modifier
            .size(SWATCH)
            .clip(LoShapes.Small)
            .background(if (white) Color.White else Color.Black)
            .border(1.dp, LoColors.Outline, LoShapes.Small)
            .semantics { contentDescription = if (white) "Paint white" else "Paint black" },
    )
}

/**
 * The mask as a small picture next to the layer (drawn from its paint: base, gradients, strokes;
 * feather and hardness are left out). Tap: target the mask (accent outline). Long-press: switch
 * it off and on (a red cross while off).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MaskThumbnail(
    layer: Layer,
    docSize: Pair<Int, Int>?,
    targeted: Boolean,
    onIntent: (EditorIntent) -> Unit,
) {
    val mask = layer.mask ?: return
    val (docW, docH) = docSize ?: (1 to 1)
    val danger = LoColors.Danger
    Box(
        Modifier
            .size(THUMB)
            .clip(LoShapes.Small)
            .border(if (targeted) 2.dp else 1.dp, if (targeted) LoColors.Accent else LoColors.Outline, LoShapes.Small)
            .combinedClickable(
                onClick = { send(onIntent, LayerAction.TargetMask(layer.id, true)) },
                onLongClick = { send(onIntent, LayerAction.SetMaskEnabled(layer.id, !mask.enabled)) },
                onLongClickLabel = if (mask.enabled) "Disable mask" else "Enable mask",
            ).testTag(MaskTags.thumbnail(layer.id))
            .semantics { contentDescription = "Mask of ${layer.name.ifBlank { layer.type.label }}" },
    ) {
        ComposeCanvas(Modifier.size(THUMB)) {
            val sx = size.width / docW.coerceAtLeast(1)
            val sy = size.height / docH.coerceAtLeast(1)

            fun grey(v: Float): Color {
                val g = if (mask.inverted) 1f - v else v
                return Color(g, g, g)
            }
            drawRect(grey(mask.base))
            for (op in mask.paint) {
                when (op) {
                    is MaskPaint.Gradient -> {
                        drawRect(
                            Brush.linearGradient(
                                listOf(grey(op.from), grey(op.to)),
                                start = Offset(op.x0 * sx, op.y0 * sy),
                                end = Offset(op.x1 * sx + 0.01f, op.y1 * sy + 0.01f),
                            ),
                        )
                    }

                    is MaskPaint.Stroke -> {
                        val path = Path()
                        op.points.chunked(2).forEachIndexed { i, (x, y) ->
                            if (i ==
                                0
                            ) {
                                path.moveTo(x * sx, y * sy)
                            } else {
                                path.lineTo(x * sx, y * sy)
                            }
                        }
                        if (op.points.size == 2) path.lineTo(op.points[0] * sx + 0.01f, op.points[1] * sy)
                        drawPath(
                            path,
                            grey(op.value).copy(alpha = op.opacity / LayerMask.FULL),
                            style = Stroke(width = 2 * op.radius * sx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            }
            if (!mask.enabled) {
                drawLine(danger, Offset.Zero, Offset(size.width, size.height), strokeWidth = 2.dp.toPx())
                drawLine(danger, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = 2.dp.toPx())
            }
        }
    }
}

private val THUMB = 40.dp
private val SWATCH = 28.dp
private const val DEFAULT_EDGE = 4000
private const val MIN_RADIUS_RANGE = 10f
