package dev.mark.lineover.feature.editor

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoDimens
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.LocalReducedMotion
import dev.mark.lineover.core.designsystem.MotionTokens
import dev.mark.lineover.core.designsystem.component.LoEmptyState
import dev.mark.lineover.core.designsystem.component.LoPanel
import dev.mark.lineover.core.designsystem.component.LoPanelDetent
import dev.mark.lineover.core.designsystem.component.LoPanelState
import dev.mark.lineover.core.designsystem.component.LoToastHost
import dev.mark.lineover.core.designsystem.component.LoToastState
import dev.mark.lineover.core.io.prefs.AppPreferences
import dev.mark.lineover.core.io.prefs.CanvasSurround
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.core.model.ToolCategory
import dev.mark.lineover.core.ui.adaptive.LoWidthClass
import dev.mark.lineover.core.ui.adaptive.LoWindowInfo
import dev.mark.lineover.core.ui.adaptive.rememberLoWindowInfo
import dev.mark.lineover.core.ui.tools.ToolRegistry
import dev.mark.lineover.feature.editor.canvas.CanvasGestureListener
import dev.mark.lineover.feature.editor.canvas.CanvasView
import dev.mark.lineover.feature.editor.canvas.EditorCanvasFactory
import kotlinx.coroutines.launch

@Composable
fun EditorRoute(
    projectId: Long,
    library: ProjectLibrary,
    preferences: AppPreferences,
    canvases: EditorCanvasFactory,
    onBack: () -> Unit,
    onExport: () -> Unit,
) {
    val context = LocalContext.current
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val viewModel =
        viewModel(key = "editor-$projectId") {
            EditorViewModel(projectId, library, preferences, canvases, debuggable = debuggable)
        }
    WideColorGamutWindow()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toast = remember { LoToastState() }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is EditorEffect.Message -> launch { toast.show(effect.text) }
            }
        }
    }
    EditorScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onBack = onBack,
        onExport = onExport,
        toast = toast,
        canvas = { modifier -> PhotoCanvas(state, modifier, viewModel.gestures) },
    )
}

/**
 * The canvas: a SurfaceView the native engine renders into. Only a new engine or surround
 * colour reaches [AndroidView.update]; gestures stay inside [CanvasView] and never recompose.
 */
@Composable
internal fun PhotoCanvas(
    state: EditorState,
    modifier: Modifier,
    gestures: CanvasGestureListener? = null,
) {
    val canvas = state.canvas
    val surround = state.surround.color.toArgb()
    val swipeMode = state.swipeMode
    AndroidView(
        factory = { CanvasView(it) },
        modifier = modifier,
        update = { view ->
            view.setSurround(surround)
            view.setRenderer(canvas)
            view.gestureListener = gestures
            view.swipeMode = swipeMode
        },
        onRelease = { view -> view.setRenderer(null) },
    )
}

/**
 * While the editor is shown the window asks for the wide colour gamut (Display P3) on
 * displays that have it, so the canvas surface can show P3 colours unclipped.
 */
@Composable
private fun WideColorGamutWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val previous = window?.colorMode
        if (window != null && view.display?.isWideColorGamut == true) window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        onDispose { if (window != null && previous != null) window.colorMode = previous }
    }
}

val CanvasSurround.color: Color
    get() = if (this == CanvasSurround.MidGrey) LoColors.CanvasSurroundGrey else LoColors.CanvasSurroundDark

/** Rail + right column on tablets, unfolded foldables and landscape phones (D4). */
internal val LoWindowInfo.editorIsWide: Boolean
    get() = widthClass == LoWidthClass.Expanded || (widthClass == LoWidthClass.Medium && isLandscape)

/**
 * The editor shell (layout frozen after Stage 3, rule R9).
 *
 * Phone: top bar · canvas (panel over its lower part, ≤ 40 % of it) · selection options ·
 * tool strip · category bar. Wide: category rail · (top bar, canvas, tool strip) · 320 dp
 * column for panels and sheets.
 *
 * [canvas] draws the photo; production passes the GL [CanvasView], screenshot tests a stand-in.
 */
@Composable
fun EditorScreen(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    onBack: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
    toast: LoToastState = remember { LoToastState() },
    canvas: @Composable (Modifier) -> Unit = {},
    autoFocusSearch: Boolean = true,
) {
    val window = rememberLoWindowInfo()
    val wide = window.editorIsWide
    BackHandler(enabled = state.searchOpen) { onIntent(EditorIntent.CloseSearch) }
    BackHandler(enabled = !state.searchOpen && state.panel != null) { onIntent(EditorIntent.ClosePanel) }
    Box(modifier.fillMaxSize().background(LoColors.Surface1).windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (wide) {
            WideLayout(state, onIntent, onBack, onExport, canvas)
        } else {
            CompactLayout(state, onIntent, onBack, onExport, canvas)
        }
        LoToastHost(
            toast,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = LoDimens.ToolStripHeight + LoDimens.CategoryBarHeight + LoSpacing.M),
        )
        if (state.searchOpen) ToolSearchScreen(state, onIntent, autoFocus = autoFocusSearch)
        state.coachStep?.let { step -> CoachMarks(step, wide, onIntent) }
    }
}

@Composable
private fun CompactLayout(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    onBack: () -> Unit,
    onExport: () -> Unit,
    canvas: @Composable (Modifier) -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    // Sheets (layers, history, masks, info) open tall; a tool's panel opens at its low detent.
    val sheetOpen = state.hasCompactPanel && state.panel !is EditorPanel.Tool
    Column(Modifier.fillMaxSize()) {
        EditorTopBar(state, wide = false, onIntent = onIntent, onBack = onBack, onExport = onExport)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            CanvasArea(state, onIntent, canvas)
            androidx.compose.animation.AnimatedVisibility(
                visible = state.hasCompactPanel,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(MotionTokens.enter(reducedMotion)) { it },
                exit = slideOutVertically(MotionTokens.exit(reducedMotion)) { it },
            ) {
                key(sheetOpen) {
                    val panelState = remember { LoPanelState(if (sheetOpen) LoPanelDetent.Expanded else LoPanelDetent.Collapsed) }
                    LoPanel(
                        state = panelState,
                        modifier = Modifier.testTag(EditorTags.PANEL),
                        maxHeight = maxHeight * LoDimens.PANEL_MAX_HEIGHT_FRACTION,
                    ) {
                        PanelContent(state, wide = false, onIntent = onIntent, panelState = panelState)
                    }
                }
            }
        }
        if (state.category == ToolCategory.Select) SelectionOptionsBar()
        ToolStrip(state, onIntent)
        CategoryBar(state, onIntent)
    }
}

@Composable
private fun WideLayout(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    onBack: () -> Unit,
    onExport: () -> Unit,
    canvas: @Composable (Modifier) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        CategoryRail(state, onIntent)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            EditorTopBar(state, wide = true, onIntent = onIntent, onBack = onBack, onExport = onExport)
            Box(Modifier.weight(1f).fillMaxWidth()) { CanvasArea(state, onIntent, canvas) }
            if (state.category == ToolCategory.Select) SelectionOptionsBar()
            ToolStrip(state, onIntent)
        }
        Column(
            Modifier
                .width(LoDimens.SidePanelWidth)
                .fillMaxHeight()
                .background(LoColors.Surface1)
                .verticalScroll(rememberScrollState())
                .padding(vertical = LoSpacing.Xs)
                .testTag(EditorTags.PANEL),
        ) {
            PanelContent(state, wide = true, onIntent = onIntent)
        }
    }
}

/** Canvas, its status, the overlay placeholders and the (hidden) handle layer. */
@Composable
private fun CanvasArea(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    canvas: @Composable (Modifier) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(state.surround.color)) {
        canvas(Modifier.fillMaxSize().testTag(EditorTags.CANVAS))
        when {
            state.loaded && state.project == null -> {
                LoEmptyState(
                    LoIcons.Image,
                    "Project not found",
                    body = "It may have been deleted.",
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            state.previewUnavailable -> {
                LoEmptyState(
                    LoIcons.Image,
                    "This format can't be shown yet",
                    body = "TIFF arrives in Stage 12 and RAW in Stage 25. The project is kept; your original is never changed.",
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        CompareOverlay(state, onIntent)
        CanvasBadges(state, Modifier.align(Alignment.TopStart))
        SwipeHud(state, Modifier.align(Alignment.Center))
        if (state.renderStats) RenderStatsOverlay(state, Modifier.align(Alignment.BottomStart).padding(LoSpacing.Xs))
        if (state.showSpinner) {
            // Subtle: sharp tiles are still loading (only after 300 ms, see EditorViewModel).
            CircularProgressIndicator(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(LoSpacing.S)
                        .size(SPINNER_SIZE)
                        .testTag(EditorTags.SPINNER),
                color = LoColors.TextSecondary,
                strokeWidth = 2.dp,
            )
        }
        // Overlay for the on-canvas handles of later stages (crop, gradients, pins). Hidden for now.
        CanvasOverlayLayer(visible = false)
    }
}

@Composable
private fun CanvasOverlayLayer(visible: Boolean) {
    if (visible) Box(Modifier.fillMaxSize().testTag(EditorTags.CANVAS_OVERLAY))
}

/**
 * The canvas overlays switched on in More: the histogram and the clipping badge (Stage 5),
 * guides as a placeholder until Stage 10.
 */
@Composable
private fun CanvasBadges(
    state: EditorState,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    Column(modifier.padding(LoSpacing.Xs), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xxs)) {
        AnimatedVisibility(
            visible = state.histogramShown,
            enter = fadeIn(MotionTokens.enter(reducedMotion)),
            exit = fadeOut(MotionTokens.exit(reducedMotion)),
        ) { HistogramOverlay(state) }
        AnimatedVisibility(
            visible = EditorState.CLIPPING_TOOL in state.overlays,
            enter = fadeIn(MotionTokens.enter(reducedMotion)),
            exit = fadeOut(MotionTokens.exit(reducedMotion)),
        ) { ClippingBadge() }
        listOf("workflow.guides_grid_rulers").forEach { id ->
            AnimatedVisibility(
                visible = id in state.overlays,
                enter = fadeIn(MotionTokens.enter(reducedMotion)),
                exit = fadeOut(MotionTokens.exit(reducedMotion)),
            ) {
                val tool = requireNotNull(ToolRegistry[id])
                Row(
                    Modifier
                        .clip(LoShapes.Small)
                        .background(LoColors.Surface2.copy(alpha = BADGE_ALPHA))
                        .padding(horizontal = LoSpacing.Xs, vertical = LoSpacing.Xxs)
                        .testTag(EditorTags.slot(id)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xxs),
                ) {
                    Icon(painterResource(tool.icon), contentDescription = null, tint = LoColors.TextSecondary)
                    Text("${tool.name} · Stage ${tool.stage}", style = LoType.Caption, color = LoColors.TextPrimary)
                }
            }
        }
    }
}

private const val BADGE_ALPHA = 0.9f
private val SPINNER_SIZE = 20.dp
