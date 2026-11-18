package dev.mark.lineover.feature.editor

import androidx.lifecycle.viewModelScope
import dev.mark.lineover.core.engine.RenderScheduler
import dev.mark.lineover.core.io.prefs.AppPreferences
import dev.mark.lineover.core.io.prefs.CanvasSurround
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.Project
import dev.mark.lineover.core.model.ToolCategory
import dev.mark.lineover.core.ui.mvi.MviViewModel
import dev.mark.lineover.core.ui.tools.ToolPlace
import dev.mark.lineover.core.ui.tools.ToolRegistry
import dev.mark.lineover.core.ui.tools.ToolSearch
import dev.mark.lineover.core.ui.tools.ToolSpec
import dev.mark.lineover.core.ui.tools.toolCategoryOf
import dev.mark.lineover.feature.editor.canvas.EditorCanvas
import dev.mark.lineover.feature.editor.canvas.EditorCanvasFactory
import dev.mark.lineover.feature.editor.canvas.ImageSummary
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
    /** The edit; identity until the first tools (Stage 5). */
    val graph: EditGraph = EditGraph(),
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
}

sealed interface EditorEffect {
    data class Message(
        val text: String,
    ) : EditorEffect
}

/**
 * The editor of one project. Stage 3 is the shell: every tool has its slot and opens a
 * placeholder; the engine (Stage 4) brings the edits.
 */
class EditorViewModel(
    projectId: Long,
    private val library: ProjectLibrary,
    private val preferences: AppPreferences,
    private val canvases: EditorCanvasFactory,
) : MviViewModel<EditorState, EditorIntent, EditorEffect>(
        EditorState(
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
        scheduler = RenderScheduler({ graph -> canvas.setGraph(graph) }, viewModelScope).also { it.submit(state.value.graph) }
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
            is EditorIntent.ToggleOverlay -> setState { copy(overlays = overlays.toggle(intent.id), menu = null) }
            is EditorIntent.ToggleFavorite -> toggleFavorite(intent.id)
            is EditorIntent.OpenSearch, is EditorIntent.CloseSearch, is EditorIntent.Query -> search(intent)
            is EditorIntent.NextCoachMark -> nextCoachMark()
            is EditorIntent.DismissCoachMarks -> dismissCoachMarks()
            is EditorIntent.ShowHelp -> setState { copy(coachStep = 0, menu = null) }
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
