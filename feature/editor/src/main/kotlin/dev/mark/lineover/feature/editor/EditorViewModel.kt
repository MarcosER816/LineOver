package dev.mark.lineover.feature.editor

import androidx.lifecycle.viewModelScope
import dev.mark.lineover.core.engine.CompareMode
import dev.mark.lineover.core.engine.RenderScheduler
import dev.mark.lineover.core.io.prefs.AppPreferences
import dev.mark.lineover.core.io.prefs.CanvasSurround
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.LightAdjustments
import dev.mark.lineover.core.model.LightParam
import dev.mark.lineover.core.model.Project
import dev.mark.lineover.core.model.ToolCategory
import dev.mark.lineover.core.ui.mvi.MviViewModel
import dev.mark.lineover.core.ui.tools.ToolPlace
import dev.mark.lineover.core.ui.tools.ToolRegistry
import dev.mark.lineover.core.ui.tools.ToolSearch
import dev.mark.lineover.core.ui.tools.ToolSpec
import dev.mark.lineover.core.ui.tools.toolCategoryOf
import dev.mark.lineover.feature.editor.canvas.CanvasGestureListener
import dev.mark.lineover.feature.editor.canvas.EditorCanvas
import dev.mark.lineover.feature.editor.canvas.EditorCanvasFactory
import dev.mark.lineover.feature.editor.canvas.ImageSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the editor's panel slot shows (bottom panel on phones, right column when wide). */
sealed interface EditorPanel {
    /** A tool's panel: a placeholder until the tool's stage ships. */
    data class Tool(
        val id: String,
    ) : EditorPanel

    data object Layers : EditorPanel

    data object History : EditorPanel

    data object Info : EditorPanel
}

enum class HistoryTab(
    val label: String,
    val toolId: String,
) {
    History("History", "history.history_list"),
    Snapshots("Snapshots", "history.snapshots"),
    Versions("Versions", "history.versions"),
}

/** Menus that drop down from a button. */
enum class EditorMenu { More, AddLayer, AddMask }

data class EditorState(
    val project: Project? = null,
    val loaded: Boolean = false,
    /** The engine drawing the canvas, once the original is open. */
    val canvas: EditorCanvas? = null,
    val image: ImageSummary? = null,
    /** True when the original could not be decoded for the canvas (e.g. RAW before Stage 25). */
    val previewUnavailable: Boolean = false,
    /** Sharp tiles have been loading for more than 300 ms: show the subtle spinner. */
    val showSpinner: Boolean = false,
    /** The edit the engine renders (in memory until projects are saved, Stage 8). */
    val graph: EditGraph = EditGraph(),
    /** The Light sliders (Stage 5); the background layer's ops are built from them. */
    val light: LightAdjustments = LightAdjustments(),
    /** The Light parameter the collapsed panel and swipe mode adjust. */
    val lightParam: LightParam = LightParam.Exposure,
    /** Swipe mode: on the canvas, vertical swipes pick [lightParam], horizontal ones change it. */
    val swipeMode: Boolean = false,
    /** A swipe-mode gesture is in progress: the HUD shows the parameter and its value. */
    val swiping: Boolean = false,
    /** A second finger is down on a slider: clipping shown while it stays. */
    val clippingPreview: Boolean = false,
    /** Holding the canvas shows the original with a "Before" label. */
    val showingOriginal: Boolean = false,
    val compare: CompareMode = CompareMode.Off,
    /** Split mode: where the original ends, as a fraction of the canvas width. */
    val split: Float = DEFAULT_SPLIT,
    /** Debug builds only: the render-stats overlay (More → Render stats). */
    val debuggable: Boolean = false,
    val renderStats: Boolean = false,
    val category: ToolCategory = DEFAULT_CATEGORY,
    val panel: EditorPanel? = null,
    val historyTab: HistoryTab = HistoryTab.History,
    val menu: EditorMenu? = null,
    val searchOpen: Boolean = false,
    val query: String = "",
    /** Ids of the canvas overlays switched on in the More menu. */
    val overlays: Set<String> = emptySet(),
    val favorites: List<String> = emptyList(),
    /** Step of the first-open coach marks, or null when they are not shown. */
    val coachStep: Int? = null,
    val surround: CanvasSurround = CanvasSurround.Dark,
) {
    val selectedToolId: String? get() = (panel as? EditorPanel.Tool)?.id

    /** Clipping warnings are drawn: the overlay is on, or a slider's second finger is down. */
    val clippingShown: Boolean get() = CLIPPING_TOOL in overlays || clippingPreview

    val histogramShown: Boolean get() = HISTOGRAM_TOOL in overlays

    /** The tools in the strip of the selected category. */
    val strip: List<ToolSpec>
        get() =
            if (category == ToolCategory.Favorites) {
                ToolRegistry.strip(ToolCategory.Favorites) + favorites.mapNotNull { ToolRegistry[it] }
            } else {
                ToolRegistry.strip(category)
            }

    val searchResults: List<ToolSpec> get() = ToolSearch.search(query)

    companion object {
        val DEFAULT_CATEGORY = ToolCategory.Light
        const val COACH_STEPS = 3
        const val DEFAULT_SPLIT = 0.5f
        const val HISTOGRAM_TOOL = "light.histogram"
        const val CLIPPING_TOOL = "light.clipping_warnings"
        const val COMPARE_TOOL = "history.before_after"
    }
}

sealed interface EditorIntent {
    data class SelectCategory(
        val category: ToolCategory,
    ) : EditorIntent

    /** Opens a tool's panel (from the strip, a menu, a sheet or the search). */
    data class SelectTool(
        val id: String,
    ) : EditorIntent

    data class OpenPanel(
        val panel: EditorPanel,
    ) : EditorIntent

    data object ClosePanel : EditorIntent

    data class SelectHistoryTab(
        val tab: HistoryTab,
    ) : EditorIntent

    data class OpenMenu(
        val menu: EditorMenu?,
    ) : EditorIntent

    data class ToggleOverlay(
        val id: String,
    ) : EditorIntent

    data class ToggleFavorite(
        val id: String,
    ) : EditorIntent

    data object OpenSearch : EditorIntent

    data object CloseSearch : EditorIntent

    data class Query(
        val text: String,
    ) : EditorIntent

    data object NextCoachMark : EditorIntent

    data object DismissCoachMarks : EditorIntent

    /** More → Help: shows the coach marks again. */
    data object ShowHelp : EditorIntent

    data class SetLight(
        val param: LightParam,
        val value: Float,
    ) : EditorIntent

    /** Light → Auto: a percentile stretch of the preview (Exposure and Blacks). */
    data object AutoLight : EditorIntent

    data class ClippingPreview(
        val active: Boolean,
    ) : EditorIntent

    /** Compare button: off → split → side by side → off. */
    data object CycleCompare : EditorIntent

    data class SetSplit(
        val fraction: Float,
    ) : EditorIntent

    data object ToggleSwipeMode : EditorIntent

    data object ToggleRenderStats : EditorIntent

    /** Canvas gestures (from CanvasView, see [EditorViewModel.gestures]). */
    data class HoldOriginal(
        val holding: Boolean,
    ) : EditorIntent

    data class SwipeSelect(
        val steps: Int,
    ) : EditorIntent

    data class SwipeAdjust(
        val fraction: Float,
    ) : EditorIntent

    data class SwipeActive(
        val active: Boolean,
    ) : EditorIntent
}

sealed interface EditorEffect {
    data class Message(
        val text: String,
    ) : EditorEffect
}

/**
 * The editor of one project. Stage 3 is the shell: every tool has its slot and opens a
 * placeholder; the engine (Stage 4) renders the edit, the Light tools (Stage 5) are the
 * first that change it.
 */
class EditorViewModel(
    projectId: Long,
    private val library: ProjectLibrary,
    private val preferences: AppPreferences,
    private val canvases: EditorCanvasFactory,
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Default,
    debuggable: Boolean = false,
) : MviViewModel<EditorState, EditorIntent, EditorEffect>(
        EditorState(
            debuggable = debuggable,
            category = toolCategoryOf(preferences.lastCategory.value) ?: EditorState.DEFAULT_CATEGORY,
            favorites = preferences.favorites.value,
            coachStep = if (preferences.coachMarksSeen.value) null else 0,
            surround = preferences.surround.value,
        ),
    ) {
    init {
        viewModelScope.launch { library.markOpened(projectId) }
        viewModelScope.launch {
            library.observe(projectId).collect { project -> setState { copy(project = project, loaded = true) } }
        }
        viewModelScope.launch { openCanvas(library.observe(projectId).filterNotNull().first()) }
        viewModelScope.launch { preferences.favorites.collect { setState { copy(favorites = it) } } }
        viewModelScope.launch { preferences.surround.collect { setState { copy(surround = it) } } }
    }

    private var scheduler: RenderScheduler? = null

    private suspend fun openCanvas(project: dev.mark.lineover.core.model.Project) {
        val canvas = canvases.open(project)
        if (canvas == null) {
            setState { copy(previewUnavailable = true) }
            return
        }
        setState { copy(canvas = canvas, image = canvas.summary, previewUnavailable = false) }
        // Edits reach the engine through the scheduler: the latest graph wins.
        val renderScheduler = RenderScheduler({ graph -> canvas.setGraph(graph) }, viewModelScope, renderDispatcher)
        scheduler = renderScheduler
        renderScheduler.submit(state.value.graph)
        syncCanvas(null, state.value)
        canvas.loading.collectLatest { loading ->
            if (loading) {
                delay(SPINNER_DELAY_MILLIS)
                setState { copy(showSpinner = true) }
            } else {
                setState { copy(showSpinner = false) }
            }
        }
    }

    override fun onCleared() {
        scheduler?.cancel()
        // Closing joins the engine's threads: never on the main thread.
        state.value.canvas?.let { canvas -> Thread({ canvas.close() }, "LineOverEngineClose").start() }
    }

    override fun onIntent(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.SelectCategory -> selectCategory(intent.category)
            is EditorIntent.SelectTool -> selectTool(intent.id)
            is EditorIntent.OpenPanel -> setState { copy(panel = if (panel == intent.panel) null else intent.panel, menu = null) }
            is EditorIntent.ClosePanel -> setState { copy(panel = null) }
            is EditorIntent.SelectHistoryTab -> setState { copy(historyTab = intent.tab, panel = EditorPanel.History) }
            is EditorIntent.OpenMenu -> setState { copy(menu = intent.menu) }
            is EditorIntent.ToggleOverlay -> reduce { copy(overlays = overlays.toggle(intent.id), menu = null) }
            is EditorIntent.ToggleFavorite -> toggleFavorite(intent.id)
            is EditorIntent.OpenSearch, is EditorIntent.CloseSearch, is EditorIntent.Query -> search(intent)
            is EditorIntent.NextCoachMark, is EditorIntent.DismissCoachMarks, is EditorIntent.ShowHelp -> coach(intent)
            else -> onEditIntent(intent)
        }
    }

    /** The Light tools, compare and the canvas gestures (Stage 5). */
    private fun onEditIntent(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.SetLight -> {
                reduce { withLight(light.with(intent.param, intent.value)) }
            }

            is EditorIntent.AutoLight -> {
                autoLight()
            }

            is EditorIntent.ClippingPreview -> {
                reduce { copy(clippingPreview = intent.active) }
            }

            is EditorIntent.CycleCompare -> {
                reduce { copy(compare = compare.next(), menu = null) }
            }

            is EditorIntent.SetSplit -> {
                reduce { copy(split = intent.fraction.coerceIn(0f, 1f)) }
            }

            is EditorIntent.ToggleSwipeMode -> {
                setState { copy(swipeMode = !swipeMode) }
            }

            is EditorIntent.ToggleRenderStats -> {
                setState { copy(renderStats = debuggable && !renderStats, menu = null) }
            }

            else -> {
                onGestureIntent(intent)
            }
        }
    }

    private fun onGestureIntent(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.HoldOriginal -> {
                setState { copy(showingOriginal = intent.holding) }
            }

            is EditorIntent.SwipeActive -> {
                setState { copy(swiping = intent.active && swipeMode) }
            }

            is EditorIntent.SwipeSelect -> {
                swipeSelect(intent.steps)
            }

            is EditorIntent.SwipeAdjust -> {
                reduce {
                    val param = lightParam
                    withLight(light.with(param, light[param] + intent.fraction * (param.max - param.min)))
                }
            }

            else -> {
                Unit
            }
        }
    }

    /** What CanvasView reports, as intents. */
    val gestures: CanvasGestureListener =
        object : CanvasGestureListener {
            override fun onHoldChanged(holding: Boolean) = onIntent(EditorIntent.HoldOriginal(holding))

            override fun onSwipeSelect(steps: Int) = onIntent(EditorIntent.SwipeSelect(steps))

            override fun onSwipeAdjust(fraction: Float) = onIntent(EditorIntent.SwipeAdjust(fraction))

            override fun onSwipeActive(active: Boolean) = onIntent(EditorIntent.SwipeActive(active))
        }

    private fun EditorState.withLight(next: LightAdjustments): EditorState =
        copy(light = next, graph = graph.withBackgroundOps(next.toOps()))

    private fun autoLight() {
        val result = state.value.canvas?.autoLight()
        if (result == null) {
            sendEffect(EditorEffect.Message("Auto needs the photo to finish loading"))
            return
        }
        val (exposure, blacks) = result
        reduce { withLight(light.with(LightParam.Exposure, exposure).with(LightParam.Blacks, blacks)) }
    }

    private fun swipeSelect(steps: Int) {
        val params = LightParam.entries
        val next = params[(state.value.lightParam.ordinal + steps).mod(params.size)]
        setState {
            // The open Light panel follows the parameter being swiped.
            val lightPanel = (panel as? EditorPanel.Tool)?.let { LightParam.forTool(it.id) } != null
            copy(lightParam = next, panel = if (lightPanel) EditorPanel.Tool(next.toolId) else panel)
        }
    }

    /** setState, then hands what changed to the engine. */
    private fun reduce(block: EditorState.() -> EditorState) {
        val before = state.value
        setState(block)
        syncCanvas(before, state.value)
    }

    private fun syncCanvas(
        before: EditorState?,
        after: EditorState,
    ) {
        val canvas = after.canvas ?: return
        if (before == null || before.graph != after.graph) scheduler?.submit(after.graph)
        if (before == null || before.clippingShown != after.clippingShown) canvas.setClipping(after.clippingShown)
        if (before == null || before.compare != after.compare || before.split != after.split) {
            canvas.setCompare(after.compare, after.split)
        }
        if (before == null || before.histogramShown != after.histogramShown) canvas.setHistogramEnabled(after.histogramShown)
    }

    private fun CompareMode.next(): CompareMode = CompareMode.entries[(ordinal + 1) % CompareMode.entries.size]

    private fun coach(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.NextCoachMark -> nextCoachMark()
            is EditorIntent.DismissCoachMarks -> dismissCoachMarks()
            is EditorIntent.ShowHelp -> setState { copy(coachStep = 0, menu = null) }
            else -> Unit
        }
    }

    private fun search(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.OpenSearch -> setState { copy(searchOpen = true, query = "", menu = null) }
            is EditorIntent.CloseSearch -> setState { copy(searchOpen = false) }
            is EditorIntent.Query -> setState { copy(query = intent.text) }
            else -> Unit
        }
    }

    private fun selectCategory(category: ToolCategory) {
        preferences.setLastCategory(category.id)
        setState {
            // Switching category closes a tool panel of the old category; sheets stay open.
            val keep = panel !is EditorPanel.Tool || ToolRegistry[(panel as EditorPanel.Tool).id]?.category == category
            copy(category = category, panel = if (keep) panel else null, menu = null)
        }
    }

    private fun selectTool(id: String) {
        val tool = ToolRegistry[id] ?: return
        LightParam.forTool(id)?.let { param -> setState { copy(lightParam = param) } }
        val category = tool.category?.takeIf { tool.place == ToolPlace.Strip }
        if (category != null && state.value.category != ToolCategory.Favorites) preferences.setLastCategory(category.id)
        setState {
            val sameTool = selectedToolId == id
            copy(
                // Opening a tool from Favorites keeps the Favorites strip in view.
                category = if (category != null && this.category != ToolCategory.Favorites) category else this.category,
                panel = if (sameTool) null else EditorPanel.Tool(id),
                menu = null,
                searchOpen = false,
            )
        }
    }

    private fun toggleFavorite(id: String) {
        val tool = ToolRegistry[id] ?: return
        if (tool.id == FAVORITES_TOOL) return
        val current = preferences.favorites.value
        if (id in current) {
            preferences.setFavorites(current - id)
            sendEffect(EditorEffect.Message("Removed ${tool.name} from Favorites"))
        } else {
            preferences.setFavorites(current + id)
            sendEffect(EditorEffect.Message("Added ${tool.name} to Favorites"))
        }
    }

    private fun nextCoachMark() {
        val step = state.value.coachStep ?: return
        if (step + 1 >= EditorState.COACH_STEPS) dismissCoachMarks() else setState { copy(coachStep = step + 1) }
    }

    private fun dismissCoachMarks() {
        preferences.setCoachMarksSeen(true)
        setState { copy(coachStep = null) }
    }

    private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

    companion object {
        const val FAVORITES_TOOL = "favorites.favorites"

        /** The spinner appears only when sharp tiles take longer than this (Stage 4 UX). */
        const val SPINNER_DELAY_MILLIS = 300L
    }
}
