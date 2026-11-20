package dev.mark.lineover.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoDimens
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.LocalReducedMotion
import dev.mark.lineover.core.designsystem.component.LoCategoryBar
import dev.mark.lineover.core.designsystem.component.LoChip
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.designsystem.component.LoToolButton
import dev.mark.lineover.core.engine.CompareMode
import dev.mark.lineover.core.model.SelectionMode
import dev.mark.lineover.core.model.ToolCategory
import dev.mark.lineover.core.ui.tools.ToolRegistry
import dev.mark.lineover.core.ui.tools.categoryBarEntries
import dev.mark.lineover.core.ui.tools.icon
import dev.mark.lineover.core.ui.tools.label
import dev.mark.lineover.core.ui.tools.toolCategoryOf
import kotlinx.coroutines.flow.first

/** Test tags of the editor's slots, so tests can reach every tool the way a finger does. */
object EditorTags {
    fun category(id: String) = "category:$id"

    fun tool(id: String) = "tool:$id"

    /** The visible placeholder (or control) of a tool's reserved slot. */
    fun slot(id: String) = "slot:$id"

    fun historyRow(index: Int) = "history-row:$index"

    fun version(id: String) = "version:$id"

    const val HISTORY_PREVIEW = "history-preview"
    const val NAME_FIELD = "name-field"

    const val STRIP = "tool-strip"
    const val RAIL = "category-rail"
    const val CATEGORY_BAR = "category-bar"
    const val CANVAS = "canvas"
    const val CANVAS_OVERLAY = "canvas-overlay"
    const val PANEL = "panel"
    const val SELECTION_BAR = "selection-options"
    const val COACH = "coach-marks"
    const val SPINNER = "tile-spinner"
    const val SEARCH_FIELD = "tool-search-field"
    const val LIGHT_PANEL = "light-panel"
    const val DETAIL_PANEL = "detail-panel"
    const val ONE_TO_ONE = "one-to-one"
    const val LIGHT_AUTO = "light-auto"
    const val LIGHT_SWIPE = "light-swipe-mode"
    const val BEFORE_LABEL = "before-label"
    const val SWIPE_HUD = "swipe-hud"
    const val SPLIT_HANDLE = "compare-split-handle"
    const val RENDER_STATS = "render-stats"
    const val COLOR_PANEL = "color-panel"
    const val AS_SHOT = "wb-as-shot"
    const val EYEDROPPER = "wb-eyedropper"
    const val TEMPERATURE = "wb-temperature"
    const val TINT = "wb-tint"
    const val TARGETED = "hsl-targeted"
    const val LOUPE = "eyedropper-loupe"
    const val TARGETED_HUD = "targeted-hud"

    const val GRADE_PANEL = "grade-panel"
    const val CURVE_EDITOR = "curve-editor"
    const val GRADE_WHEEL = "grade-wheel"
    const val GRADIENT_BAR = "gradient-bar"

    fun hslDot(band: String) = "hsl-dot:$band"

    fun hslSlider(band: String) = "hsl-slider:$band"

    // Crop and Optics (Stage 9)
    const val GEOMETRY_PANEL = "geometry-panel"
    const val STRAIGHTEN_RULER = "straighten-ruler"
    const val AUTO_STRAIGHTEN = "auto-straighten"
    const val AUTO_CHROMATIC = "auto-chromatic"
    const val CONSTRAIN = "constrain-crop"
    const val LENS_PROFILE = "lens-profile"

    fun aspect(name: String) = "aspect:$name"

    fun upright(name: String) = "upright:$name"

    fun slider(id: String) = "slider:$id"
}

/** The 56 dp top bar: Back · Undo · Redo · Compare · History · Layers · More · Export. */
@Composable
internal fun EditorTopBar(
    state: EditorState,
    wide: Boolean,
    onIntent: (EditorIntent) -> Unit,
    onBack: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = LoDimens.TopBarHeight).background(LoColors.Surface1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoIconButton(LoIcons.ArrowBack, "Back", onClick = onBack)
        // Back, More and Export are pinned. The actions between them scroll sideways when the
        // window is too narrow for all eight buttons (< 384 dp: small phones, large display
        // size, split screen), so Export can never be pushed off the screen.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (wide) {
                Text(
                    state.project?.name.orEmpty(),
                    style = LoType.Title,
                    color = LoColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = LoSpacing.Xs),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                // The undo / redo slot itself (Stage 8); also two- and three-finger taps on the canvas.
                Row(Modifier.testTag(EditorTags.slot("history.undo_redo"))) {
                    LoIconButton(LoIcons.Undo, "Undo", onClick = { onIntent(EditorIntent.Undo) }, enabled = state.history.canUndo)
                    LoIconButton(LoIcons.Redo, "Redo", onClick = { onIntent(EditorIntent.Redo) }, enabled = state.history.canRedo)
                }
                LoIconButton(
                    LoIcons.Compare,
                    "Compare",
                    // Cycles off → split → side by side (holding the photo shows the original).
                    onClick = { onIntent(EditorIntent.CycleCompare) },
                    selected = state.compare != CompareMode.Off,
                )
                LoIconButton(
                    LoIcons.History,
                    "History",
                    onClick = { onIntent(EditorIntent.OpenPanel(EditorPanel.History)) },
                    selected = state.panel == EditorPanel.History,
                )
                LoIconButton(
                    LoIcons.Layers,
                    "Layers",
                    onClick = { onIntent(EditorIntent.OpenPanel(EditorPanel.Layers)) },
                    selected = state.panel == EditorPanel.Layers,
                )
            }
        }
        Box {
            LoIconButton(
                LoIcons.MoreVert,
                "More",
                onClick = { onIntent(EditorIntent.OpenMenu(EditorMenu.More)) },
                selected = state.menu == EditorMenu.More,
            )
            MoreMenu(state, onIntent)
        }
        LoIconButton(LoIcons.IosShare, "Export", onClick = onExport, enabled = state.project != null)
    }
}

/** More: copy / paste settings, batch, canvas overlays, info, tool search, favourites, help. */
@Composable
private fun MoreMenu(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    DropdownMenu(
        expanded = state.menu == EditorMenu.More,
        onDismissRequest = { onIntent(EditorIntent.OpenMenu(null)) },
        containerColor = LoColors.Surface2,
        shape = LoShapes.Medium,
    ) {
        MoreItem("Copy settings", LoIcons.ContentCopy, "presets.copy_paste_settings", "more:copy") {
            onIntent(EditorIntent.SelectTool("presets.copy_paste_settings"))
        }
        MoreItem("Paste settings", LoIcons.ContentPaste, "presets.copy_paste_settings", "more:paste") {
            onIntent(EditorIntent.SelectTool("presets.copy_paste_settings"))
        }
        MoreItem("Batch", LoIcons.Stacks, "workflow.batch", "more:workflow.batch") {
            onIntent(EditorIntent.SelectTool("workflow.batch"))
        }
        HorizontalDivider(color = LoColors.Outline)
        listOf(
            "Show histogram" to "light.histogram",
            "Clipping warnings" to "light.clipping_warnings",
            "Guides / grid / rulers" to "workflow.guides_grid_rulers",
        ).forEach { (label, id) ->
            MoreItem(label, requireNotNull(ToolRegistry[id]).icon, id, "more:$id", checked = id in state.overlays) {
                onIntent(EditorIntent.ToggleOverlay(id))
            }
        }
        HorizontalDivider(color = LoColors.Outline)
        MoreItem("Info", LoIcons.Info, null, "more:info") { onIntent(EditorIntent.OpenPanel(EditorPanel.Info)) }
        MoreItem("Tool search", LoIcons.Search, null, "more:workflow.tool_search") { onIntent(EditorIntent.OpenSearch) }
        MoreItem("Customize favorites", LoIcons.Star, "favorites.favorites", "more:favorites.favorites") {
            onIntent(EditorIntent.SelectTool("favorites.favorites"))
        }
        MoreItem("Help", LoIcons.Help, null, "more:help") { onIntent(EditorIntent.ShowHelp) }
        if (state.debuggable) {
            // Debug builds only: preview update latency and frame timing on the canvas.
            MoreItem("Render stats", LoIcons.BugReport, null, "more:render-stats", checked = state.renderStats) {
                onIntent(EditorIntent.ToggleRenderStats)
            }
        }
    }
}

@Composable
private fun MoreItem(
    label: String,
    icon: Int,
    reservedToolId: String?,
    tag: String,
    checked: Boolean? = null,
    onClick: () -> Unit,
) {
    val stage = reservedToolId?.let { ToolRegistry[it] }?.takeIf { !it.done }?.stage
    DropdownMenuItem(
        text = {
            Column {
                Text(label, style = LoType.Body, color = LoColors.TextPrimary)
                if (stage != null) Text("Stage $stage", style = LoType.Micro, color = LoColors.TextSecondary)
            }
        },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
        trailingIcon =
            if (checked == true) {
                { Icon(painterResource(LoIcons.Check), contentDescription = "On") }
            } else {
                null
            },
        onClick = onClick,
        colors = MenuDefaults.itemColors(leadingIconColor = LoColors.TextSecondary, trailingIconColor = LoColors.Accent),
        modifier = Modifier.testTag(tag),
    )
}

/** The 56 dp strip with the tools of the selected category. Long press pins a tool to Favorites. */
@Composable
internal fun ToolStrip(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tools = state.strip
    val listState = rememberLazyListState()
    LaunchedEffect(state.category) { listState.scrollToItem(0) }
    LazyRow(
        state = listState,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = LoDimens.ToolStripHeight)
                .background(LoColors.Surface1)
                .selectableGroup()
                .testTag(EditorTags.STRIP),
        contentPadding = PaddingValues(horizontal = LoSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tools, key = { it.id }) { tool ->
            val pinned = tool.id in state.favorites
            LoToolButton(
                icon = tool.icon,
                label = tool.name,
                selected = state.selectedToolId == tool.id,
                onClick = { onIntent(EditorIntent.SelectTool(tool.id)) },
                onLongClick =
                    if (tool.id == EditorViewModel.FAVORITES_TOOL) {
                        null
                    } else {
                        { onIntent(EditorIntent.ToggleFavorite(tool.id)) }
                    },
                onLongClickLabel = if (pinned) "Remove from Favorites" else "Add to Favorites",
                modifier = Modifier.testTag(EditorTags.tool(tool.id)),
            )
        }
        if (state.category == ToolCategory.Favorites && state.favorites.isEmpty()) {
            item(key = "hint") {
                Text(
                    "Long-press any tool to add it here",
                    style = LoType.Caption,
                    color = LoColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = LoSpacing.S),
                )
            }
        }
    }
}

/** The 64 dp category bar in its fixed order (phones). */
@Composable
internal fun CategoryBar(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LoCategoryBar(
        categories = categoryBarEntries,
        selectedId = state.category.id,
        onSelect = { id -> toolCategoryOf(id)?.let { onIntent(EditorIntent.SelectCategory(it)) } },
        modifier = modifier.testTag(EditorTags.CATEGORY_BAR),
        itemModifier = { id -> Modifier.testTag(EditorTags.category(id)) },
    )
}

/** On wide windows the category bar becomes a left rail, in the same fixed order. */
@Composable
internal fun CategoryRail(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val reducedMotion = LocalReducedMotion.current
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(state.category) {
        // Wait for the first layout, otherwise nothing looks visible.
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        val index = state.category.ordinal
        val visible = listState.layoutInfo.visibleItemsInfo
        val fully = visible.any { it.index == index && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
        // The first positioning (a restored category) jumps; later changes animate.
        if (!fully) {
            if (reducedMotion || !positioned) {
                listState.scrollToItem(index)
            } else {
                listState.animateScrollToItem(index)
            }
        }
        positioned = true
    }
    LazyColumn(
        state = listState,
        modifier =
            modifier
                .fillMaxHeight()
                .width(RAIL_WIDTH)
                .background(LoColors.Surface1)
                .selectableGroup()
                .testTag(EditorTags.RAIL),
        contentPadding = PaddingValues(vertical = LoSpacing.Xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(ToolCategory.entries, key = { it.id }) { category ->
            LoToolButton(
                icon = category.icon,
                label = category.label,
                selected = category == state.category,
                onClick = { onIntent(EditorIntent.SelectCategory(category)) },
                minHeight = LoDimens.CategoryBarHeight,
                modifier = Modifier.testTag(EditorTags.category(category.id)),
            )
        }
    }
}

/**
 * Shown above the tool strip in the Select category (Stage 15): how a new selection combines
 * with the current one, anti-aliasing, and Select all / Invert / Deselect.
 */
@Composable
internal fun SelectionOptionsBar(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.selectionSettings
    val select = { action: SelectAction -> onIntent(EditorIntent.Select(action)) }
    Row(
        modifier
            .fillMaxWidth()
            .background(LoColors.Surface1)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = LoSpacing.Xs)
            .testTag(EditorTags.SELECTION_BAR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
    ) {
        SelectionMode.entries.forEach { mode ->
            LoChip(mode.label, selected = settings.mode == mode, onClick = { select(SelectAction.SetSettings(settings.copy(mode = mode))) })
        }
        LoChip("Anti-alias", selected = settings.antiAlias, onClick = {
            select(SelectAction.SetSettings(settings.copy(antiAlias = !settings.antiAlias)))
        })
        LoChip("All", selected = false, onClick = { select(SelectAction.SelectAll) })
        val has = state.selection != null
        LoChip("Invert", selected = false, onClick = { select(SelectAction.Invert) }, enabled = has)
        LoChip("Deselect", selected = false, onClick = { select(SelectAction.Deselect) }, enabled = has)
        // Stage 16: the one-tap smart selections.
        SmartButtons(state, onIntent)
    }
}

internal val RAIL_WIDTH = 88.dp
