package dev.mark.lineover.feature.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoDimens
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
import dev.mark.lineover.core.designsystem.rememberLoHaptics
import dev.mark.lineover.core.model.AdjustmentKind
import dev.mark.lineover.core.model.BlendMode
import dev.mark.lineover.core.model.BlendSpace
import dev.mark.lineover.core.model.ColorAdjustments
import dev.mark.lineover.core.model.Layer
import dev.mark.lineover.core.model.LayerMath
import dev.mark.lineover.core.model.LayerPlacement
import dev.mark.lineover.core.model.LayerType
import dev.mark.lineover.core.ui.tools.ToolRegistry
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Test tags of the Layers panel (Stage 13). */
object LayerTags {
    fun row(id: String?) = "layer-row:${id ?: "background"}"

    fun handle(id: String) = "layer-handle:$id"

    fun blendMode(mode: BlendMode) = "blend-mode:${mode.name}"

    const val BLEND_LIST = "blend-list"
}

private fun send(
    onIntent: (EditorIntent) -> Unit,
    action: LayerAction,
) = onIntent(EditorIntent.Layers(action))

/**
 * Layers (Stage 13): the layer list (top layer first, the photo at the bottom), the selected
 * layer's properties and the layer operations. Rows drag to reorder (handle) and swipe left to
 * delete (with Undo); the selected row carries its opacity slider; the blend list previews each
 * mode on the canvas while it scrolls and applies the one tapped.
 */
@Composable
internal fun LayersPanel(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    onClose: () -> Unit,
) {
    val layers = LayerMath.flatten(state.graph)
    val space = if (state.graph.blendSpace == BlendSpace.Linear) "linear light" else "gamma"
    PanelHeader("Layers", "${layers.size + 1} layers · blending in $space", onClose)
    Column(Modifier.padding(horizontal = LoSpacing.M), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        AddLayerButton(state, onIntent)
        LayerList(state, layers, onIntent)
        val selected = state.selectedLayer
        LayerProperties(state, selected, onIntent)
        LayerOperations(state, selected, onIntent)
        Text("Blending", style = LoType.Caption, color = LoColors.TextSecondary)
        LoSegmented(
            options = listOf("Gamma (Photoshop)", "Linear light"),
            selectedIndex = if (state.graph.blendSpace == BlendSpace.Linear) 1 else 0,
            onSelect = { send(onIntent, LayerAction.SetBlendSpace(if (it == 1) BlendSpace.Linear else BlendSpace.Gamma)) },
        )
        Text("Masks", style = LoType.Caption, color = LoColors.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
            listOf("layers.layer_mask", "layers.clipping_mask", "layers.vector_mask").forEach { id ->
                val tool = requireNotNull(ToolRegistry[id])
                LoChip(
                    "${tool.name} · ${tool.stage}",
                    selected = false,
                    onClick = { onIntent(EditorIntent.SelectTool(id)) },
                    modifier = Modifier.testTag(EditorTags.slot(id)),
                )
            }
        }
    }
}

@Composable
private fun AddLayerButton(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    val pick = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    val raster =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) send(onIntent, LayerAction.Import(uri.toString(), smart = false))
        }
    val smart =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) send(onIntent, LayerAction.Import(uri.toString(), smart = true))
        }
    var kinds by remember { mutableStateOf(false) }
    val close = {
        kinds = false
        onIntent(EditorIntent.OpenMenu(null))
    }
    Box {
        TextButton(onClick = { onIntent(EditorIntent.OpenMenu(EditorMenu.AddLayer)) }) {
            Icon(painterResource(LoIcons.Add), contentDescription = null, tint = LoColors.Accent)
            Text("Add layer", style = LoType.Body, color = LoColors.Accent)
        }
        DropdownMenu(
            expanded = state.menu == EditorMenu.AddLayer,
            onDismissRequest = close,
            containerColor = LoColors.Surface2,
            shape = LoShapes.Medium,
        ) {
            if (kinds) {
                MenuItem("From current edit", "The photo's own colour and tone", LoIcons.ContentCopy) {
                    close()
                    send(onIntent, LayerAction.AddAdjustment(null))
                }
                AdjustmentKind.entries.forEach { kind ->
                    MenuItem(kind.label, null, LoIcons.Tune) {
                        close()
                        send(onIntent, LayerAction.AddAdjustment(kind))
                    }
                }
            } else {
                ToolItem("layers.raster_layer", "A photo as a layer") {
                    close()
                    raster.launch(pick)
                }
                ToolItem("layers.adjustment_layer", "Edits everything below it") { kinds = true }
                ToolItem("layers.group", "Pass-through or isolated") {
                    close()
                    send(onIntent, LayerAction.AddGroup)
                }
                ToolItem("layers.smart_object", "A photo with its own edit") {
                    close()
                    smart.launch(pick)
                }
                MenuItem("Text", "Stage 23", LoIcons.TextFields, enabled = false) {}
                MenuItem("Shape", "Stage 24", LoIcons.Shapes, enabled = false) {}
            }
        }
    }
}

@Composable
private fun ToolItem(
    id: String,
    caption: String,
    onClick: () -> Unit,
) {
    val tool = requireNotNull(ToolRegistry[id])
    MenuItem(tool.name, caption, tool.icon, Modifier.testTag(EditorTags.slot(id)), onClick = onClick)
}

@Composable
private fun MenuItem(
    title: String,
    caption: String?,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title, style = LoType.Body, color = if (enabled) LoColors.TextPrimary else LoColors.TextDisabled)
                if (caption != null) Text(caption, style = LoType.Micro, color = LoColors.TextSecondary)
            }
        },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
        onClick = onClick,
        enabled = enabled,
        colors = MenuDefaults.itemColors(leadingIconColor = LoColors.TextSecondary),
        modifier = modifier,
    )
}

@DrawableRes
private fun iconOf(type: LayerType): Int =
    when (type) {
        LayerType.Background, LayerType.Raster -> LoIcons.Image
        LayerType.Adjustment -> LoIcons.Tune
        LayerType.Group -> LoIcons.Folder
        LayerType.SmartObject -> LoIcons.DataObject
        LayerType.Text -> LoIcons.TextFields
        LayerType.Shape -> LoIcons.Shapes
    }

internal fun captionOf(layer: Layer): String =
    buildString {
        append(layer.type.label)
        append(" · ")
        append(layer.blend.label)
        append(" · ")
        append(layer.opacity.roundToInt())
        append(" %")
        if (layer.fill != Layer.FULL) append(" · fill ${layer.fill.roundToInt()} %")
        if (layer.type == LayerType.Group) append(if (layer.isolated) " · isolated" else " · pass through")
    }

/** The rows, top layer first. Dragging a handle moves a layer among its siblings. */
@Composable
private fun LayerList(
    state: EditorState,
    layers: List<Pair<Layer, Int>>,
    onIntent: (EditorIntent) -> Unit,
) {
    val haptics = rememberLoHaptics()
    val slot = with(LocalDensity.current) { (RowHeight + LoSpacing.Xs).toPx() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var target by remember { mutableStateOf<Int?>(null) }
    val selected = state.selectedLayer?.id
    Column(verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        for ((layer, depth) in layers.asReversed()) {
            key(layer.id) {
                val isDragged = dragging == layer.id
                LayerRow(
                    layer = layer,
                    depth = depth,
                    selected = layer.id == selected,
                    onIntent = onIntent,
                    modifier =
                        Modifier
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragged) offset else 0f
                                shadowElevation = if (isDragged) DRAG_ELEVATION else 0f
                            },
                    handle =
                        Modifier.pointerInput(layer.id) {
                            detectDragGestures(
                                onDragStart = {
                                    dragging = layer.id
                                    offset = 0f
                                    target = null
                                    haptics.confirm()
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    offset += amount.y
                                    val (siblings, from) = LayerMath.siblings(state.graph, layer.id) ?: return@detectDragGestures
                                    val floor = if (siblings.firstOrNull()?.isBackground == true) 1 else 0
                                    // Up the list = higher in the stack.
                                    val to = (from - (offset / slot).roundToInt()).coerceIn(floor, siblings.lastIndex)
                                    if (to != (target ?: from)) haptics.tick()
                                    target = to
                                },
                                onDragEnd = {
                                    target?.let { send(onIntent, LayerAction.Move(layer.id, it)) }
                                    if (target != null) haptics.confirm()
                                    dragging = null
                                    offset = 0f
                                    target = null
                                },
                                onDragCancel = {
                                    dragging = null
                                    offset = 0f
                                    target = null
                                },
                            )
                        },
                )
            }
        }
        PhotoRow(state, selected == null, onIntent)
    }
}

/** A layer row: handle, eye, kind, name and caption, lock; swipe left deletes; selected = its opacity slider. */
@Composable
private fun LayerRow(
    layer: Layer,
    depth: Int,
    selected: Boolean,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier,
    handle: Modifier,
) {
    val name = layer.name.ifBlank { layer.type.label }
    val scope = rememberCoroutineScope()
    val swipe = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { SWIPE_DELETE.toPx() }
    val currentLayer by rememberUpdatedState(layer)
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = INDENT * depth)
            .testTag(LayerTags.row(layer.id)),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = RowHeight).clip(LoShapes.Medium)) {
            // Revealed behind the row while it is swiped.
            Row(
                Modifier.matchParentSize().background(LoColors.Danger).padding(horizontal = LoSpacing.M),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Delete", style = LoType.Body, color = LoColors.OnAccent)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = RowHeight)
                    .graphicsLayer { translationX = swipe.value }
                    .background(if (selected) LoColors.Surface3 else LoColors.Surface2)
                    .pointerInput(layer.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (swipe.value < -threshold && !currentLayer.locked) {
                                    send(onIntent, LayerAction.Delete(currentLayer.id))
                                }
                                scope.launch { swipe.animateTo(0f) }
                            },
                            onDragCancel = { scope.launch { swipe.animateTo(0f) } },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                scope.launch { swipe.snapTo((swipe.value + amount).coerceIn(-size.width.toFloat(), 0f)) }
                            },
                        )
                    }.clickable { send(onIntent, LayerAction.Select(layer.id)) }
                    .padding(end = LoSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    handle
                        .testTag(LayerTags.handle(layer.id))
                        .size(LoDimens.TouchTargetMin)
                        .semantics { contentDescription = "Drag $name" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(LoIcons.DragHandle), contentDescription = null, tint = LoColors.TextSecondary)
                }
                LoIconButton(
                    if (layer.visible) LoIcons.Visibility else LoIcons.VisibilityOff,
                    if (layer.visible) "Hide $name" else "Show $name",
                    onClick = { send(onIntent, LayerAction.SetVisible(layer.id, !layer.visible)) },
                )
                Icon(
                    painterResource(iconOf(layer.type)),
                    contentDescription = null,
                    tint = LoColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = LoSpacing.Xxs),
                )
                Column(Modifier.weight(1f).alpha(if (layer.visible) 1f else HIDDEN_ALPHA)) {
                    Text(name, style = LoType.Body, color = LoColors.TextPrimary, maxLines = 1)
                    Text(captionOf(layer), style = LoType.Caption, color = LoColors.TextSecondary, maxLines = 1)
                }
                LoIconButton(
                    if (layer.locked) LoIcons.Lock else LoIcons.LockOpen,
                    if (layer.locked) "Unlock $name" else "Lock $name",
                    onClick = { send(onIntent, LayerAction.SetLocked(layer.id, !layer.locked)) },
                    selected = layer.locked,
                )
            }
        }
        if (selected) {
            Box(Modifier.testTag(EditorTags.slot("layers.opacity"))) {
                LoSlider(
                    value = layer.opacity,
                    onValueChange = { send(onIntent, LayerAction.SetOpacity(layer.id, it)) },
                    label = "Opacity",
                    valueRange = 0f..Layer.FULL,
                    defaultValue = Layer.FULL,
                    fill = LoSliderFill.FromStart,
                    enabled = !layer.locked,
                    valueText = { "${it.roundToInt()} %" },
                    onValueChangeFinished = { onIntent(EditorIntent.EndEdit) },
                )
            }
        }
    }
}

/** The photo: always at the bottom, always 100 % Normal; selecting it shows the photo's opacity (fixed). */
@Composable
private fun PhotoRow(
    state: EditorState,
    selected: Boolean,
    onIntent: (EditorIntent) -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag(LayerTags.row(null))) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = RowHeight)
                .clip(LoShapes.Medium)
                .background(if (selected) LoColors.Surface3 else LoColors.Surface2)
                .clickable { send(onIntent, LayerAction.Select(null)) }
                .padding(horizontal = LoSpacing.S),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LoSpacing.S),
        ) {
            Spacer(Modifier.width(LoSpacing.Xs))
            Icon(painterResource(LoIcons.Image), contentDescription = null, tint = LoColors.TextSecondary)
            Column(Modifier.weight(1f)) {
                Text(state.project?.name ?: "Photo", style = LoType.Body, color = LoColors.TextPrimary, maxLines = 1)
                Text("Background · Normal · 100 %", style = LoType.Caption, color = LoColors.TextSecondary)
            }
            Icon(painterResource(LoIcons.Lock), contentDescription = "The photo stays at the bottom", tint = LoColors.TextDisabled)
        }
        if (selected) {
            Box(Modifier.testTag(EditorTags.slot("layers.opacity"))) {
                LoSlider(
                    value = Layer.FULL,
                    onValueChange = {},
                    label = "Opacity",
                    valueRange = 0f..Layer.FULL,
                    defaultValue = Layer.FULL,
                    fill = LoSliderFill.FromStart,
                    enabled = false,
                    valueText = { "${it.roundToInt()} %" },
                )
            }
        }
    }
}

/** Blend mode, fill and what the selected layer's kind adds (group isolation, adjustment, placement). */
@Composable
private fun LayerProperties(
    state: EditorState,
    layer: Layer?,
    onIntent: (EditorIntent) -> Unit,
) {
    val editable = layer != null && !layer.locked
    var blendOpen by remember(layer?.id) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().testTag(EditorTags.slot("layers.blend_modes")),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
    ) {
        Text("Blend mode", style = LoType.Body, color = LoColors.TextPrimary, modifier = Modifier.weight(1f))
        LoChip(
            (layer?.blend ?: BlendMode.Normal).label,
            selected = blendOpen,
            onClick = { blendOpen = !blendOpen },
            enabled = editable,
        )
    }
    if (blendOpen && layer != null && editable) {
        BlendList(layer, onIntent) { blendOpen = false }
    }
    Box(Modifier.testTag(EditorTags.slot("layers.fill"))) {
        LoSlider(
            value = layer?.fill ?: Layer.FULL,
            onValueChange = { v -> layer?.let { send(onIntent, LayerAction.SetFill(it.id, v)) } },
            label = "Fill",
            valueRange = 0f..Layer.FULL,
            defaultValue = Layer.FULL,
            fill = LoSliderFill.FromStart,
            enabled = editable,
            valueText = { "${it.roundToInt()} %" },
            onValueChangeFinished = { onIntent(EditorIntent.EndEdit) },
        )
    }
    if (layer == null) {
        Text(
            "The photo is the bottom layer. Add a layer to blend over it.",
            style = LoType.Caption,
            color = LoColors.TextSecondary,
        )
        return
    }
    when (layer.type) {
        LayerType.Group -> {
            LoSwitchRow(
                "Isolated",
                checked = layer.isolated,
                onCheckedChange = { send(onIntent, LayerAction.SetIsolated(layer.id, it)) },
                description = "Blend the group as one image (off: pass through)",
                enabled = editable,
            )
        }

        LayerType.Adjustment -> {
            AdjustmentControls(layer, editable, onIntent)
        }

        LayerType.Raster, LayerType.SmartObject -> {
            PlacementControls(state, layer, editable, onIntent)
            if (layer.type == LayerType.SmartObject) {
                Text("Its own edit (re-rendered from the source)", style = LoType.Caption, color = LoColors.TextSecondary)
                AdjustmentKind.SMART.forEach { kind -> KindSlider(layer, kind, editable, onIntent) }
            }
        }

        else -> {
            Unit
        }
    }
}

@Composable
private fun AdjustmentControls(
    layer: Layer,
    editable: Boolean,
    onIntent: (EditorIntent) -> Unit,
) {
    val kind = AdjustmentKind.of(layer.ops)
    when {
        kind == null -> {
            Text("${layer.ops.size} adjustments copied from the photo's edit", style = LoType.Caption, color = LoColors.TextSecondary)
        }

        kind.hasValue -> {
            KindSlider(layer, kind, editable, onIntent)
        }

        else -> {
            Text("${kind.label}: no settings", style = LoType.Caption, color = LoColors.TextSecondary)
        }
    }
}

@Composable
private fun KindSlider(
    layer: Layer,
    kind: AdjustmentKind,
    editable: Boolean,
    onIntent: (EditorIntent) -> Unit,
) {
    val neutral = if (kind == AdjustmentKind.Temperature) ColorAdjustments.DEFAULT_KELVIN else 0f
    val value = kind.valueIn(layer.ops) ?: neutral
    val step =
        when (kind) {
            AdjustmentKind.Exposure -> EV_STEP
            AdjustmentKind.Threshold -> FRACTION_STEP
            AdjustmentKind.Temperature -> KELVIN_STEP
            else -> 1f
        }
    LoSlider(
        value = value,
        onValueChange = { send(onIntent, LayerAction.SetAdjustment(layer.id, kind, it)) },
        label = kind.label,
        valueRange = kind.min..kind.max,
        defaultValue = neutral.coerceIn(kind.min, kind.max),
        step = step,
        enabled = editable,
        onValueChangeFinished = { onIntent(EditorIntent.EndEdit) },
    )
}

/** Where a photo layer sits: centre, size and angle (re-rendered from the source, never resampled twice). */
@Composable
private fun PlacementControls(
    state: EditorState,
    layer: Layer,
    editable: Boolean,
    onIntent: (EditorIntent) -> Unit,
) {
    val (w, h) = layer.sourceSize.takeIf { it.size == 2 } ?: return
    val (docW, docH) = state.canvasSize ?: return
    val p = LayerPlacement.of(layer.transform, w, h)
    val fit = LayerPlacement.fit(w, h, docW.toFloat(), docH.toFloat()).scale

    fun set(next: LayerPlacement) = send(onIntent, LayerAction.SetPlacement(layer.id, next))
    val end = { onIntent(EditorIntent.EndEdit) }
    LoSlider(
        value = p.scale / fit * PERCENT,
        onValueChange = { set(p.copy(scale = fit * it / PERCENT)) },
        label = "Size",
        valueRange = MIN_SIZE..MAX_SIZE,
        defaultValue = PERCENT,
        fill = LoSliderFill.FromStart,
        enabled = editable,
        valueText = { "${it.roundToInt()} %" },
        onValueChangeFinished = end,
    )
    LoSlider(
        value = p.degrees,
        onValueChange = { set(p.copy(degrees = it)) },
        label = "Angle",
        valueRange = -HALF_TURN..HALF_TURN,
        enabled = editable,
        valueText = { "${it.roundToInt()}°" },
        onValueChangeFinished = end,
    )
    LoSlider(
        value = p.centerX / docW * PERCENT,
        onValueChange = { set(p.copy(centerX = docW * it / PERCENT)) },
        label = "Horizontal",
        valueRange = 0f..PERCENT,
        defaultValue = PERCENT / 2,
        enabled = editable,
        valueText = { "${it.roundToInt()} %" },
        onValueChangeFinished = end,
    )
    LoSlider(
        value = p.centerY / docH * PERCENT,
        onValueChange = { set(p.copy(centerY = docH * it / PERCENT)) },
        label = "Vertical",
        valueRange = 0f..PERCENT,
        defaultValue = PERCENT / 2,
        enabled = editable,
        valueText = { "${it.roundToInt()} %" },
        onValueChangeFinished = end,
    )
}

/**
 * The 27 modes. Scrolling previews the mode under the marker (the middle row) on the canvas;
 * a tap applies a mode; closing the list goes back to the layer's own mode.
 */
@Composable
private fun BlendList(
    layer: Layer,
    onIntent: (EditorIntent) -> Unit,
    onDone: () -> Unit,
) {
    val modes = BlendMode.entries
    val haptics = rememberLoHaptics()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (modes.indexOf(layer.blend) - CENTRE_ROWS).coerceAtLeast(0))
    var previewed by remember { mutableStateOf<BlendMode?>(null) }
    val current by rememberUpdatedState(onIntent)
    LaunchedEffect(layer.id, list) {
        snapshotFlow { if (list.isScrollInProgress) centred(list) else null }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { mode ->
                previewed = mode
                haptics.tick()
                send(current, LayerAction.PreviewBlend(layer.id, mode))
            }
    }
    DisposableEffect(layer.id) { onDispose { send(current, LayerAction.PreviewBlend(layer.id, null)) } }
    Box(
        Modifier
            .fillMaxWidth()
            .height(BLEND_LIST_HEIGHT)
            .clip(LoShapes.Medium)
            .background(LoColors.Surface2),
    ) {
        LazyColumn(Modifier.fillMaxWidth().testTag(LayerTags.BLEND_LIST), state = list) {
            items(modes, key = { it.name }) { mode ->
                val first = BlendMode.GROUPS.any { it.first() == mode } && mode != BlendMode.Normal
                Column {
                    if (first) Box(Modifier.fillMaxWidth().height(1.dp).background(LoColors.Outline))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = BLEND_ROW)
                            .background(if (mode == previewed) LoColors.Surface3 else LoColors.Surface2)
                            .clickable {
                                send(onIntent, LayerAction.SetBlend(layer.id, mode))
                                onDone()
                            }.padding(horizontal = LoSpacing.M)
                            .testTag(LayerTags.blendMode(mode)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            mode.label,
                            style = LoType.Body,
                            color = if (mode == layer.blend) LoColors.Accent else LoColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (mode ==
                            layer.blend
                        ) {
                            Icon(painterResource(LoIcons.Check), contentDescription = "Current", tint = LoColors.Accent)
                        }
                    }
                }
            }
        }
    }
}

/** The mode of the row under the middle of the list (the preview marker). */
private fun centred(list: LazyListState): BlendMode? {
    val info = list.layoutInfo
    val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
    val item =
        info.visibleItemsInfo.firstOrNull { it.offset <= middle && it.offset + it.size > middle }
            ?: info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - middle) }
    return (item?.key as? String)?.let { name -> BlendMode.entries.firstOrNull { it.name == name } }
}

/** Duplicate, merge down, group / ungroup, flatten and delete for the selected layer. */
@Composable
private fun LayerOperations(
    state: EditorState,
    layer: Layer?,
    onIntent: (EditorIntent) -> Unit,
) {
    val hasLayers = LayerMath.flatten(state.graph).isNotEmpty()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        val id = layer?.id
        LoChip("Duplicate", selected = false, onClick = { id?.let { send(onIntent, LayerAction.Duplicate(it)) } }, enabled = id != null)
        LoChip(
            "Merge down",
            selected = false,
            onClick = { id?.let { send(onIntent, LayerAction.MergeDown(it)) } },
            enabled = id != null && LayerMath.canMergeDown(state.graph, id),
        )
        if (layer?.type == LayerType.Group) {
            LoChip("Ungroup", selected = false, onClick = { send(onIntent, LayerAction.Ungroup(layer.id)) })
        } else {
            LoChip("Group", selected = false, onClick = { id?.let { send(onIntent, LayerAction.Group(it)) } }, enabled = id != null)
        }
        LoChip("Flatten", selected = false, onClick = { send(onIntent, LayerAction.Flatten) }, enabled = hasLayers)
        LoChip(
            "Delete",
            selected = false,
            onClick = { id?.let { send(onIntent, LayerAction.Delete(it)) } },
            enabled = layer != null && !layer.locked,
            leadingIcon = LoIcons.Delete,
        )
    }
}

private val RowHeight = 56.dp
private val INDENT = 16.dp
private val SWIPE_DELETE = 96.dp
private val BLEND_LIST_HEIGHT = 240.dp
private val BLEND_ROW = 48.dp
private const val CENTRE_ROWS = 2
private const val DRAG_ELEVATION = 12f
private const val HIDDEN_ALPHA = 0.5f
private const val PERCENT = 100f
private const val MIN_SIZE = 5f
private const val MAX_SIZE = 400f
private const val HALF_TURN = 180f
private const val EV_STEP = 0.05f
private const val FRACTION_STEP = 0.01f
private const val KELVIN_STEP = 50f
