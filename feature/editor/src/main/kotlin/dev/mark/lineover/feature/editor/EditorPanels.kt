package dev.mark.lineover.feature.editor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import dev.mark.lineover.core.model.ToolCategory
import dev.mark.lineover.core.ui.tools.ToolRegistry
import dev.mark.lineover.core.ui.tools.ToolSpec
import dev.mark.lineover.core.ui.tools.label

/** The content of the panel slot: the open panel, or what the category shows by default. */
@Composable
internal fun ColumnScope.PanelContent(
    state: EditorState,
    wide: Boolean,
    onIntent: (EditorIntent) -> Unit,
) {
    val close = { onIntent(EditorIntent.ClosePanel) }
    when (val panel = state.panel) {
        is EditorPanel.Tool -> {
            ToolRegistry[panel.id]?.let { ToolPanel(it, close) }
        }

        EditorPanel.Layers -> {
            LayersSheet(state, onIntent, close)
        }

        EditorPanel.History -> {
            HistorySheet(state, onIntent, close)
        }

        EditorPanel.Info -> {
            InfoPanel(state, close)
        }

        null -> {
            when {
                state.category == ToolCategory.Masks -> MasksSheet(state, onIntent)
                wide -> CategoryToolList(state, onIntent)
                else -> Unit
            }
        }
    }
}

/** True when the panel slot has something to show on a phone (the bottom panel is open). */
internal val EditorState.hasCompactPanel: Boolean
    get() = panel != null || category == ToolCategory.Masks

@Composable
private fun PanelHeader(
    title: String,
    subtitle: String?,
    onClose: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = LoSpacing.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = LoType.Title, color = LoColors.TextPrimary)
            if (subtitle != null) Text(subtitle, style = LoType.Caption, color = LoColors.TextSecondary)
        }
        if (onClose != null) LoIconButton(LoIcons.Close, "Close $title", onClick = onClose)
    }
}

/** A tool's panel until its stage ships: what it will be and when it arrives. */
@Composable
private fun ToolPanel(
    tool: ToolSpec,
    onClose: () -> Unit,
) {
    val where = tool.category?.label ?: "Editor"
    PanelHeader(tool.name, where, onClose)
    Column(Modifier.padding(horizontal = LoSpacing.M, vertical = LoSpacing.Xs)) {
        SlotPlaceholder(
            tool = tool,
            detail =
                if (tool.id == EditorViewModel.FAVORITES_TOOL) {
                    "Long-press any tool to pin it to Favorites. Reordering arrives in Stage ${tool.stage}."
                } else {
                    "${tool.panelType.label}. Your photo is never changed."
                },
        )
    }
}

/** The visible placeholder of one reserved slot: icon, name, "Arrives in Stage N". */
@Composable
internal fun SlotPlaceholder(
    tool: ToolSpec,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = LoDimens.TouchTargetMin + LoSpacing.Xs)
            .clip(LoShapes.Medium)
            .background(LoColors.Surface2)
            .border(1.dp, LoColors.Outline, LoShapes.Medium)
            .padding(horizontal = LoSpacing.M, vertical = LoSpacing.Xs)
            .testTag(EditorTags.slot(tool.id))
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.S),
    ) {
        Icon(
            painterResource(tool.icon),
            contentDescription = null,
            tint = LoColors.TextSecondary,
            modifier = Modifier.size(LoDimens.IconSize),
        )
        Column(Modifier.weight(1f)) {
            Text(tool.name, style = LoType.Body, color = LoColors.TextPrimary)
            Text(
                if (tool.done) "Ready" else "Arrives in Stage ${tool.stage}",
                style = LoType.Caption,
                color = if (tool.done) LoColors.Success else LoColors.TextSecondary,
            )
            if (detail != null) Text(detail, style = LoType.Caption, color = LoColors.TextSecondary)
        }
    }
}

/** Layers: the layer list, blend mode / opacity / fill, masks and the add-layer menu (Stages 13, 14, 24). */
@Composable
private fun LayersSheet(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    onClose: () -> Unit,
) {
    PanelHeader("Layers", "Layers arrive in Stage 13", onClose)
    Column(Modifier.padding(horizontal = LoSpacing.M), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { onIntent(EditorIntent.OpenMenu(EditorMenu.AddLayer)) }) {
                    Icon(painterResource(LoIcons.Add), contentDescription = null, tint = LoColors.Accent)
                    Text("Add layer", style = LoType.Body, color = LoColors.Accent)
                }
                AddMenu(
                    expanded = state.menu == EditorMenu.AddLayer,
                    ids = listOf("layers.raster_layer", "layers.adjustment_layer", "layers.group", "layers.smart_object"),
                    onIntent = onIntent,
                )
            }
        }
        // The photo itself is the bottom layer of every project.
        LayerRow(name = state.project?.name ?: "Photo", caption = "Background · Normal · 100 %")
        Text("Blend mode, opacity and fill", style = LoType.Caption, color = LoColors.TextSecondary)
        val blend = requireNotNull(ToolRegistry["layers.blend_modes"])
        Row(
            Modifier.fillMaxWidth().testTag(EditorTags.slot(blend.id)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
        ) {
            LoChip("Normal", selected = true, onClick = {}, enabled = false)
            Text("27 blend modes · Stage ${blend.stage}", style = LoType.Caption, color = LoColors.TextSecondary)
        }
        DisabledSlider("layers.opacity", "Opacity")
        DisabledSlider("layers.fill", "Fill")
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
private fun DisabledSlider(
    id: String,
    label: String,
) {
    val tool = requireNotNull(ToolRegistry[id])
    Column(Modifier.testTag(EditorTags.slot(id))) {
        LoSlider(value = 100f, onValueChange = {
        }, label = "$label · Stage ${tool.stage}", valueRange = 0f..100f, defaultValue = 100f, enabled = false)
    }
}

@Composable
private fun LayerRow(
    name: String,
    caption: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = LoDimens.TouchTargetMin + LoSpacing.Xs)
            .clip(LoShapes.Medium)
            .background(LoColors.Surface2)
            .padding(horizontal = LoSpacing.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.S),
    ) {
        Icon(painterResource(LoIcons.Image), contentDescription = null, tint = LoColors.TextSecondary)
        Column(Modifier.weight(1f)) {
            Text(name, style = LoType.Body, color = LoColors.TextPrimary, maxLines = 1)
            Text(caption, style = LoType.Caption, color = LoColors.TextSecondary)
        }
        Icon(painterResource(LoIcons.Visibility), contentDescription = "Visible", tint = LoColors.TextSecondary)
    }
}

/** History: list, snapshots and versions (Stage 8). */
@Composable
private fun HistorySheet(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    onClose: () -> Unit,
) {
    PanelHeader("History", null, onClose)
    Column(Modifier.padding(horizontal = LoSpacing.M), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        LoSegmented(
            options = HistoryTab.entries.map { it.label },
            selectedIndex = state.historyTab.ordinal,
            onSelect = { onIntent(EditorIntent.SelectHistoryTab(HistoryTab.entries[it])) },
            modifier = Modifier.fillMaxWidth(),
        )
        SlotPlaceholder(
            requireNotNull(ToolRegistry[state.historyTab.toolId]),
            detail =
                when (state.historyTab) {
                    HistoryTab.History -> "Every edit step, tap one to go back to it."
                    HistoryTab.Snapshots -> "Named states you can return to at any time."
                    HistoryTab.Versions -> "Variants of the same photo, side by side."
                },
        )
    }
}

/** Masks: the mask list, the add-mask menu and how masks combine (Stage 17). */
@Composable
private fun MasksSheet(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    PanelHeader("Masks", "Local adjustments arrive in Stage 17", null)
    Column(Modifier.padding(horizontal = LoSpacing.M), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        Text("No masks yet", style = LoType.Body, color = LoColors.TextSecondary, modifier = Modifier.testTag("mask-list"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
            Box {
                TextButton(onClick = { onIntent(EditorIntent.OpenMenu(EditorMenu.AddMask)) }) {
                    Icon(painterResource(LoIcons.Add), contentDescription = null, tint = LoColors.Accent)
                    Text("Add mask", style = LoType.Body, color = LoColors.Accent)
                }
                AddMenu(
                    expanded = state.menu == EditorMenu.AddMask,
                    ids = ToolRegistry.strip(ToolCategory.Masks).map { it.id },
                    onIntent = onIntent,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs), verticalAlignment = Alignment.CenterVertically) {
            CombineButton(LoIcons.Add, "Add")
            CombineButton(LoIcons.Remove, "Subtract")
            CombineButton(LoIcons.JoinInner, "Intersect")
        }
    }
}

@Composable
private fun CombineButton(
    @DrawableRes icon: Int,
    label: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LoIconButton(icon, "$label mask (Stage 17)", onClick = {}, enabled = false)
        Text(label, style = LoType.Micro, color = LoColors.TextDisabled)
    }
}

@Composable
private fun AddMenu(
    expanded: Boolean,
    ids: List<String>,
    onIntent: (EditorIntent) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { onIntent(EditorIntent.OpenMenu(null)) },
        containerColor = LoColors.Surface2,
        shape = LoShapes.Medium,
    ) {
        ids.forEach { id ->
            val tool = requireNotNull(ToolRegistry[id])
            DropdownMenuItem(
                text = {
                    Column {
                        Text(tool.name, style = LoType.Body, color = LoColors.TextPrimary)
                        Text("Stage ${tool.stage}", style = LoType.Micro, color = LoColors.TextSecondary)
                    }
                },
                leadingIcon = { Icon(painterResource(tool.icon), contentDescription = null) },
                onClick = { onIntent(EditorIntent.SelectTool(id)) },
                colors = MenuDefaults.itemColors(leadingIconColor = LoColors.TextSecondary),
                modifier = Modifier.testTag(EditorTags.slot(id)),
            )
        }
    }
}

/** More → Info: what is open. Real data, nothing reserved. */
@Composable
private fun InfoPanel(
    state: EditorState,
    onClose: () -> Unit,
) {
    PanelHeader("Info", null, onClose)
    Column(Modifier.padding(horizontal = LoSpacing.M), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xxs)) {
        InfoLine("Project", state.project?.name ?: "—")
        val image = state.image
        if (image != null) {
            val mp = image.width.toLong() * image.height / MEGA
            InfoLine("Original", "${image.width} × ${image.height} px ($mp MP)")
            InfoLine("Colour", listOfNotNull(image.colorSpace, image.encoding).joinToString(" · "))
            if (image.ultraHdr) InfoLine("HDR", "Ultra HDR gain map kept")
            InfoLine("Preview", "${image.previewWidth} × ${image.previewHeight} px, sharp tiles when zoomed in")
        } else if (state.previewUnavailable) {
            InfoLine("Original", "This format is shown once its decoder arrives (TIFF: Stage 12, RAW: Stage 25)")
        }
        InfoLine("Original file", "Never changed")
    }
}

@Composable
private fun InfoLine(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = LoType.Caption, color = LoColors.TextSecondary, modifier = Modifier.weight(0.35f))
        Text(value, style = LoType.Body, color = LoColors.TextPrimary, modifier = Modifier.weight(0.65f))
    }
}

/** Wide layouts: the right column lists the category's tools when no panel is open. */
@Composable
private fun CategoryToolList(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    PanelHeader(state.category.label, "${state.strip.size} tools", null)
    Column(Modifier.padding(horizontal = LoSpacing.M), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        state.strip.forEach { tool ->
            Box(Modifier.clip(LoShapes.Medium).clickable { onIntent(EditorIntent.SelectTool(tool.id)) }) {
                SlotPlaceholder(tool, modifier = Modifier.testTag("list:${tool.id}"))
            }
        }
        if (state.category == ToolCategory.Favorites && state.favorites.isEmpty()) {
            Text("Long-press any tool to add it here.", style = LoType.Caption, color = LoColors.TextSecondary)
        }
    }
}

private const val MEGA = 1_000_000L
