package dev.mark.lineover.feature.editor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import dev.mark.lineover.core.designsystem.component.LoSliderDefaults
import dev.mark.lineover.core.designsystem.component.LoSliderFill
import dev.mark.lineover.core.designsystem.component.LoSliderTrack
import dev.mark.lineover.core.designsystem.component.LoSwitchRow
import dev.mark.lineover.core.model.CombineMode
import dev.mark.lineover.core.model.Layer
import dev.mark.lineover.core.model.LocalAdjustments
import dev.mark.lineover.core.model.LocalParam
import dev.mark.lineover.core.model.MaskComponent
import dev.mark.lineover.core.model.ToolCategory
import dev.mark.lineover.core.model.componentLabel
import dev.mark.lineover.core.ui.tools.ToolRegistry
import dev.mark.lineover.feature.editor.canvas.CanvasMatrix
import dev.mark.lineover.feature.editor.canvas.CanvasTransform
import kotlin.math.roundToInt

/** Test tags of the Masks category (Stage 17). */
internal object LocalMaskTags {
    const val SHEET = "mask-sheet"
    const val LIST = "mask-list"
    const val PANEL = "local-mask-panel"
    const val PINS = "mask-pins"
    const val OVERLAY = "mask-overlay-toggle"
    const val DUPLICATE_INVERT = "mask-duplicate-invert"
    const val AMOUNT = "mask-amount"

    fun row(id: String) = "mask-row:$id"

    fun component(index: Int) = "mask-component:$index"

    fun join(mode: CombineMode) = "mask-join:${mode.name}"
}

/**
 * The Masks sheet (the category's default panel slot): every local mask with its components,
 * Add mask, and Add / Subtract / Intersect, which make the next tool join the selected mask.
 */
@Composable
internal fun MasksSheet(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    val masks = state.localMasks
    PanelHeader("Masks", if (masks.isEmpty()) "Adjust part of the photo" else "Tap a pin or a mask to edit it", null)
    Column(
        Modifier.padding(horizontal = LoSpacing.M).testTag(LocalMaskTags.SHEET),
        verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
    ) {
        Column(Modifier.testTag(LocalMaskTags.LIST), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xxs)) {
            if (masks.isEmpty()) Text("No masks yet", style = LoType.Body, color = LoColors.TextSecondary)
            for (layer in masks.asReversed()) MaskRow(layer, layer.id == state.localMask.selectedId, onIntent)
        }
        AddMaskRow(state, onIntent)
        JoinButtons(state, onIntent)
    }
}

@Composable
private fun MaskRow(
    layer: Layer,
    selected: Boolean,
    onIntent: (EditorIntent) -> Unit,
) {
    val components = layer.mask?.components.orEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(LoShapes.Medium)
            .background(if (selected) LoColors.Surface3 else LoColors.Surface2)
            .border(1.dp, if (selected) LoColors.Accent else LoColors.Outline, LoShapes.Medium)
            .clickable { onIntent(EditorIntent.Mask(MaskAction.Select(if (selected) null else layer.id))) }
            .padding(horizontal = LoSpacing.S, vertical = LoSpacing.Xxs)
            .testTag(LocalMaskTags.row(layer.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.S),
    ) {
        Icon(painterResource(LoIcons.AdsClick), contentDescription = null, tint = if (selected) LoColors.Accent else LoColors.TextSecondary)
        Column(Modifier.weight(1f)) {
            Text(layer.name, style = LoType.Body, color = LoColors.TextPrimary)
            Text(
                components
                    .mapIndexed { i, c ->
                        (if (i == 0) "" else modeSymbol(c.mode) + " ") + componentLabel(c) +
                            if (c.invert) " (inverted)" else ""
                    }.joinToString("  "),
                style = LoType.Caption,
                color = LoColors.TextSecondary,
            )
        }
        Text("${layer.opacity.roundToInt()} %", style = LoType.Caption, color = LoColors.TextSecondary)
    }
}

private fun modeSymbol(mode: CombineMode) =
    when (mode) {
        CombineMode.Add -> "+"
        CombineMode.Subtract -> "−"
        CombineMode.Intersect -> "∩"
    }

@Composable
private fun AddMaskRow(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    Box {
        TextButton(onClick = { onIntent(EditorIntent.OpenMenu(EditorMenu.AddMask)) }) {
            Icon(painterResource(LoIcons.Add), contentDescription = null, tint = LoColors.Accent)
            val join = state.localMask.joinMode
            Text(
                if (join == null) "Add mask" else "${join.name} a component",
                style = LoType.Body,
                color = LoColors.Accent,
            )
        }
        MaskToolMenu(expanded = state.menu == EditorMenu.AddMask, busy = state.localMask.busy, onIntent = onIntent)
    }
}

/** The ten Masks tools (the Add menu): a new mask, or the next component of the selected one. */
@Composable
private fun MaskToolMenu(
    expanded: Boolean,
    busy: String?,
    onIntent: (EditorIntent) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { onIntent(EditorIntent.OpenMenu(null)) },
        containerColor = LoColors.Surface2,
        shape = LoShapes.Medium,
    ) {
        MaskTools.ALL.forEach { id ->
            val tool = requireNotNull(ToolRegistry[id])
            DropdownMenuItem(
                text = {
                    Column {
                        Text(tool.name, style = LoType.Body, color = LoColors.TextPrimary)
                        if (id in MaskTools.MODELS) {
                            Text(
                                if (id == MaskTools.DEPTH) "Depth map in the photo, else a model" else "On-device model",
                                style = LoType.Micro,
                                color = LoColors.TextSecondary,
                            )
                        }
                    }
                },
                leadingIcon = { Icon(painterResource(tool.icon), contentDescription = null) },
                enabled = busy == null,
                onClick = { onIntent(EditorIntent.SelectTool(id)) },
                colors = MenuDefaults.itemColors(leadingIconColor = LoColors.TextSecondary),
                modifier = Modifier.testTag(EditorTags.slot(id)),
            )
        }
    }
}

/** Add / Subtract / Intersect: the next tool joins the selected mask in that mode. */
@Composable
private fun JoinButtons(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    val enabled = state.selectedLocalMask != null
    Row(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs), verticalAlignment = Alignment.CenterVertically) {
        JoinButton(LoIcons.Add, CombineMode.Add, "Add", state, enabled, onIntent)
        JoinButton(LoIcons.Remove, CombineMode.Subtract, "Subtract", state, enabled, onIntent)
        JoinButton(LoIcons.JoinInner, CombineMode.Intersect, "Intersect", state, enabled, onIntent)
    }
}

@Composable
private fun JoinButton(
    @DrawableRes icon: Int,
    mode: CombineMode,
    label: String,
    state: EditorState,
    enabled: Boolean,
    onIntent: (EditorIntent) -> Unit,
) {
    val active = state.localMask.joinMode == mode
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.testTag(LocalMaskTags.join(mode))) {
        LoIconButton(
            icon,
            "$label to the selected mask",
            onClick = {
                onIntent(EditorIntent.Mask(MaskAction.Join(if (active) null else mode)))
                if (!active) onIntent(EditorIntent.OpenMenu(EditorMenu.AddMask))
            },
            enabled = enabled,
            selected = active,
        )
        Text(
            label,
            style = LoType.Micro,
            color =
                if (!enabled) {
                    LoColors.TextDisabled
                } else if (active) {
                    LoColors.Accent
                } else {
                    LoColors.TextSecondary
                },
        )
    }
}

/**
 * A Masks tool's panel: the selected mask's components (tap one to edit it; its mode, invert,
 * delete), the component's own settings, the overlay, Duplicate and invert, the amount and the
 * full local adjustment set. A drag is one History step.
 */
@Composable
internal fun LocalMaskPanel(
    toolId: String,
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    onClose: () -> Unit,
) {
    val title = requireNotNull(ToolRegistry[toolId]).name
    val layer = state.selectedLocalMask
    Column(Modifier.fillMaxWidth().testTag(LocalMaskTags.PANEL)) {
        Row(Modifier.fillMaxWidth().padding(start = LoSpacing.M), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(layer?.name ?: title, style = LoType.Title, color = LoColors.TextPrimary)
                Text(if (state.localMask.busy == toolId) "Finding it…" else title, style = LoType.Caption, color = LoColors.TextSecondary)
            }
            LoIconButton(
                if (state.localMask.overlay) LoIcons.Visibility else LoIcons.VisibilityOff,
                if (state.localMask.overlay) "Hide the mask overlay" else "Show the mask overlay",
                onClick = { onIntent(EditorIntent.Mask(MaskAction.ToggleOverlay)) },
                selected = state.localMask.overlay,
                enabled = layer != null,
                modifier = Modifier.testTag(LocalMaskTags.OVERLAY),
            )
            LoIconButton(LoIcons.Close, "Close $title", onClick = onClose)
        }
        val waiting =
            state.localMask.busy == toolId ||
                (toolId in MaskTools.MODELS && layer?.mask?.components?.none { MaskTools.of(it) == toolId } != false)
        if (layer == null || waiting) {
            Text(
                when {
                    state.localMask.busy != null -> "The model is running on this device…"
                    toolId in MaskTools.MODELS -> "Preparing the mask (the model may need to download first)."
                    else -> "Choose a mask in the Masks list."
                },
                style = LoType.Body,
                color = LoColors.TextSecondary,
                modifier = Modifier.padding(horizontal = LoSpacing.M, vertical = LoSpacing.S).testTag(EditorTags.slot(toolId)),
            )
            return@Column
        }
        Column(Modifier.testTag(EditorTags.slot(toolId))) {
            ComponentChips(layer, state, onIntent)
            layer.mask?.components?.getOrNull(state.localMask.component)?.let { c ->
                ComponentSettings(state.localMask.component, c, state, onIntent)
            }
            MaskActionsRow(state, onIntent)
            LoSlider(
                layer.opacity,
                { onIntent(EditorIntent.Mask(MaskAction.SetAmount(it))) },
                "Amount",
                modifier = Modifier.testTag(LocalMaskTags.AMOUNT),
                valueRange = 0f..Layer.FULL,
                defaultValue = Layer.FULL,
                fill = LoSliderFill.FromStart,
                valueText = { "${it.roundToInt()} %" },
                onValueChangeFinished = { onIntent(EditorIntent.EndEdit) },
            )
            LocalSliders(LocalAdjustments.fromOps(layer.ops), onIntent)
        }
    }
}

@Composable
private fun ComponentChips(
    layer: Layer,
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    val components = layer.mask?.components.orEmpty()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = LoSpacing.M, vertical = LoSpacing.Xxs),
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
    ) {
        components.forEachIndexed { i, c ->
            LoChip(
                (if (i == 0) "" else modeSymbol(c.mode) + " ") + componentLabel(c),
                selected = i == state.localMask.component,
                onClick = { onIntent(EditorIntent.Mask(MaskAction.SelectComponent(i))) },
                modifier = Modifier.testTag(LocalMaskTags.component(i)),
            )
        }
    }
}

/** The selected component: its mode (not for the first: it starts from black), invert, delete, settings. */
@Composable
private fun ComponentSettings(
    index: Int,
    c: MaskComponent,
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = LoSpacing.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
    ) {
        if (index > 0) {
            val modes = listOf(CombineMode.Add, CombineMode.Subtract, CombineMode.Intersect)
            LoSegmented(
                listOf("Add", "Subtract", "Intersect"),
                modes.indexOf(c.mode),
                { onIntent(EditorIntent.Mask(MaskAction.SetComponentMode(index, modes[it]))) },
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(componentLabel(c), style = LoType.Body, color = LoColors.TextPrimary, modifier = Modifier.weight(1f))
        }
        LoIconButton(
            LoIcons.InvertColors,
            "Invert ${componentLabel(c)}",
            onClick = { onIntent(EditorIntent.Mask(MaskAction.InvertComponent(index))) },
            selected = c.invert,
        )
        LoIconButton(
            LoIcons.Delete,
            "Remove ${componentLabel(c)}",
            onClick = { onIntent(EditorIntent.Mask(MaskAction.DeleteComponent(index))) },
        )
    }
    val edit = { next: MaskComponent -> onIntent(EditorIntent.Mask(MaskAction.EditComponent(index, next))) }
    val end = { onIntent(EditorIntent.EndEdit) }
    when (c) {
        is MaskComponent.Radial -> {
            Hint("Drag the centre to move it, elsewhere to size it")
            Slider("Feather", c.feather, 0f..100f, 50f, end) { edit(c.copy(feather = it)) }
            Slider("Angle", c.angle, -90f..90f, 0f, end) { edit(c.copy(angle = it)) }
        }

        is MaskComponent.Linear -> {
            Hint("Drag from where the effect is full to where it ends")
        }

        is MaskComponent.Brush -> {
            BrushSettings(state, onIntent)
        }

        is MaskComponent.ColorRange -> {
            Hint(if (c.colors.isEmpty()) "Tap the colours to adjust" else "${c.colors.size / LAB} colour(s) picked · tap to add one")
            Slider("Range", c.fuzziness, 1f..100f, 20f, end) { edit(c.copy(fuzziness = it)) }
        }

        is MaskComponent.LuminanceRange -> {
            RangeSliders(c.lo, c.hi, c.smooth, end) { lo, hi, s -> edit(c.copy(lo = lo, hi = hi, smooth = s)) }
        }

        is MaskComponent.DepthRange -> {
            Hint("1 is the nearest point of the photo")
            RangeSliders(c.lo, c.hi, c.smooth, end) { lo, hi, s -> edit(c.copy(lo = lo, hi = hi, smooth = s)) }
        }

        is MaskComponent.Raster -> {
            Hint("Made on this device; follows the photo's edges when exported")
        }
    }
}

@Composable
private fun RangeSliders(
    lo: Float,
    hi: Float,
    smooth: Float,
    end: () -> Unit,
    onChange: (Float, Float, Float) -> Unit,
) {
    Slider("From", lo * PERCENT, 0f..100f, 0f, end) { onChange(minOf(it / PERCENT, hi), hi, smooth) }
    Slider("To", hi * PERCENT, 0f..100f, 100f, end) { onChange(lo, maxOf(it / PERCENT, lo), smooth) }
    Slider("Smoothness", smooth * PERCENT, 0f..50f, 10f, end) { onChange(lo, hi, it / PERCENT) }
}

@Composable
private fun BrushSettings(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    val b = state.localMask.brush
    val set = { next: LocalBrush -> onIntent(EditorIntent.Mask(MaskAction.SetBrush(next))) }
    val (w, h) = state.canvasSize ?: (0 to 0)
    Row(Modifier.padding(horizontal = LoSpacing.M), horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        LoChip("Paint", selected = !b.erase, onClick = { set(b.copy(erase = false)) }, leadingIcon = LoIcons.Brush)
        LoChip("Erase", selected = b.erase, onClick = { set(b.copy(erase = true)) }, leadingIcon = LoIcons.InkEraser)
    }
    Slider("Size", b.radiusFor(maxOf(w, h)).coerceIn(1f, LocalBrush.MAX_SIZE), 1f..LocalBrush.MAX_SIZE, 1f, {}) { set(b.copy(size = it)) }
    Slider("Feather", b.feather, 0f..100f, LocalBrush.DEFAULT_FEATHER, {}) { set(b.copy(feather = it)) }
    Slider("Flow", b.flow, 1f..100f, 100f, {}) { set(b.copy(flow = it)) }
    LoSwitchRow(
        "Auto mask",
        checked = b.autoMask,
        onCheckedChange = { set(b.copy(autoMask = it)) },
        description = "Keeps to colours like the one where a stroke starts, following edges",
    )
}

@Composable
private fun Slider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    end: () -> Unit,
    onChange: (Float) -> Unit,
) = LoSlider(value, onChange, label, valueRange = range, defaultValue = default, fill = LoSliderFill.FromStart, onValueChangeFinished = end)

@Composable
private fun Hint(text: String) =
    Text(text, style = LoType.Caption, color = LoColors.TextSecondary, modifier = Modifier.padding(horizontal = LoSpacing.M))

@Composable
private fun MaskActionsRow(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    FlowRow(
        Modifier.padding(horizontal = LoSpacing.M, vertical = LoSpacing.Xxs),
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
    ) {
        LoChip(
            "Duplicate and invert",
            selected = false,
            onClick = { onIntent(EditorIntent.Mask(MaskAction.DuplicateAndInvert)) },
            leadingIcon = LoIcons.ContentCopy,
            modifier = Modifier.testTag(LocalMaskTags.DUPLICATE_INVERT),
        )
        for ((mode, label) in listOf(CombineMode.Add to "Add", CombineMode.Subtract to "Subtract", CombineMode.Intersect to "Intersect")) {
            LoChip(
                label,
                selected = state.localMask.joinMode == mode,
                onClick = {
                    onIntent(EditorIntent.Mask(MaskAction.Join(mode)))
                    onIntent(EditorIntent.OpenMenu(EditorMenu.AddMask))
                },
                leadingIcon =
                    when (mode) {
                        CombineMode.Add -> LoIcons.Add
                        CombineMode.Subtract -> LoIcons.Remove
                        CombineMode.Intersect -> LoIcons.JoinInner
                    },
            )
        }
        Box { MaskToolMenu(state.menu == EditorMenu.AddMask && state.panel is EditorPanel.Tool, state.localMask.busy, onIntent) }
        LoChip(
            "Delete mask",
            selected = false,
            onClick = { onIntent(EditorIntent.Mask(MaskAction.DeleteMask)) },
            leadingIcon = LoIcons.Delete,
        )
    }
}

/** The local adjustment set (Stage 17): the Stage 5-10 ops through the mask. */
@Composable
private fun LocalSliders(
    adjustments: LocalAdjustments,
    onIntent: (EditorIntent) -> Unit,
) {
    for (p in LocalParam.entries) {
        val hue = p == LocalParam.ColorHue
        LoSlider(
            adjustments[p],
            { onIntent(EditorIntent.Mask(MaskAction.SetParam(p, it))) },
            p.label,
            valueRange = p.min..p.max,
            defaultValue = LocalAdjustments.default(p),
            step = if (p == LocalParam.Exposure) EXPOSURE_STEP else 1f,
            fill = if (p.min < 0f) LoSliderFill.FromDefault else LoSliderFill.FromStart,
            track =
                when (p) {
                    LocalParam.Temperature -> LoSliderTrack.Temperature
                    LocalParam.ColorHue -> LoSliderTrack.Hue
                    else -> LoSliderTrack.Plain
                },
            valueText = {
                if (hue) {
                    "${it.roundToInt()}°"
                } else {
                    LoSliderDefaults.formatValue(
                        it,
                        if (p ==
                            LocalParam.Exposure
                        ) {
                            EXPOSURE_STEP
                        } else {
                            1f
                        },
                        signed = p.min < 0f,
                    )
                }
            },
            onValueChangeFinished = { onIntent(EditorIntent.EndEdit) },
        )
    }
}

/**
 * The local masks' pins over the canvas (the reserved overlay slot): a ring per mask, filled for
 * the selected one. Drawing only; CanvasView's one-finger gesture picks them (MaskAction.Start).
 */
@Composable
internal fun MaskPinsLayer(state: EditorState) {
    val pins = state.maskPins
    if (pins.isEmpty()) return
    val (imageW, imageH) = state.canvasSize ?: return
    val live = LocalCanvasMatrix.current
    val fallback = remember { CanvasMatrix() }
    val fallbackTransform = remember { CanvasTransform() }
    val matrix = live ?: fallback
    val description = "${pins.size} mask pin(s)" + (pins.firstOrNull { it.selected }?.let { " · one selected" } ?: "")
    Canvas(
        Modifier
            .fillMaxSize()
            .testTag(LocalMaskTags.PINS)
            .semantics { contentDescription = description }
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
        val out = FloatArray(2)
        val radius = PIN_RADIUS.dp.toPx()
        for (pin in pins) {
            matrix.toView(pin.u * matrix.imageWidth, pin.v * matrix.imageHeight, out)
            val c = Offset(out[0], out[1])
            drawCircle(Color.Black.copy(alpha = SHADOW), radius + 2.dp.toPx(), c)
            drawCircle(Color.White, radius, c, style = Stroke(2.dp.toPx()))
            drawCircle(if (pin.selected) LoColors.Accent else Color.White.copy(alpha = IDLE), radius * INNER, c)
        }
    }
}

private const val PIN_RADIUS = 11f
private const val INNER = 0.55f
private const val SHADOW = 0.35f
private const val IDLE = 0.6f
private const val PERCENT = 100f
private const val LAB = 3
private const val EXPOSURE_STEP = 0.01f

/** True in the Masks category: its sheet is the default panel. */
internal val EditorState.inMasks: Boolean get() = category == ToolCategory.Masks
