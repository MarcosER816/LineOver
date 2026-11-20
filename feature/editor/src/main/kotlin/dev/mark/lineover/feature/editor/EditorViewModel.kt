package dev.mark.lineover.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.viewModelScope
import dev.mark.lineover.core.engine.CompareMode
import dev.mark.lineover.core.engine.RenderScheduler
import dev.mark.lineover.core.io.layers.LayerSources
import dev.mark.lineover.core.io.lens.LensProfileState
import dev.mark.lineover.core.io.lens.LensProfileStore
import dev.mark.lineover.core.io.looks.LooksStore
import dev.mark.lineover.core.io.masks.MaskRasters
import dev.mark.lineover.core.io.prefs.AppPreferences
import dev.mark.lineover.core.io.prefs.CanvasSurround
import dev.mark.lineover.core.io.prefs.TapGestures
import dev.mark.lineover.core.io.project.DocumentLoad
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.core.model.ColorAdjustments
import dev.mark.lineover.core.model.CropOverlay
import dev.mark.lineover.core.model.DetailAdjustments
import dev.mark.lineover.core.model.DetailParam
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.EditHistory
import dev.mark.lineover.core.model.GeometryAdjustments
import dev.mark.lineover.core.model.GradeAdjustments
import dev.mark.lineover.core.model.HslBand
import dev.mark.lineover.core.model.HslChannel
import dev.mark.lineover.core.model.LightAdjustments
import dev.mark.lineover.core.model.LightParam
import dev.mark.lineover.core.model.LookAdjustments
import dev.mark.lineover.core.model.LooksState
import dev.mark.lineover.core.model.Preset
import dev.mark.lineover.core.model.Project
import dev.mark.lineover.core.model.ProjectDocument
import dev.mark.lineover.core.model.SettingsGroup
import dev.mark.lineover.core.model.ToolCategory
import dev.mark.lineover.core.model.editOps
import dev.mark.lineover.core.ui.mvi.MviViewModel
import dev.mark.lineover.core.ui.tools.ToolPlace
import dev.mark.lineover.core.ui.tools.ToolRegistry
import dev.mark.lineover.core.ui.tools.ToolSearch
import dev.mark.lineover.core.ui.tools.ToolSpec
import dev.mark.lineover.core.ui.tools.toolCategoryOf
import dev.mark.lineover.feature.editor.canvas.CanvasGestureListener
import dev.mark.lineover.feature.editor.canvas.CanvasOpenException
import dev.mark.lineover.feature.editor.canvas.EditorCanvas
import dev.mark.lineover.feature.editor.canvas.EditorCanvasFactory
import dev.mark.lineover.feature.editor.canvas.ImageSummary
import dev.mark.lineover.feature.editor.canvas.PickMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** What History shows on the canvas without changing the edit until Restore. */
sealed interface HistoryPreview {
    data class Step(
        val index: Int,
    ) : HistoryPreview

    data class SnapshotState(
        val id: String,
    ) : HistoryPreview
}

/** The naming dialog of History: a new snapshot or version, or a version's new name. */
sealed interface NameDialog {
    val initial: String

    data class NewSnapshot(
        override val initial: String,
    ) : NameDialog

    data class NewVersion(
        override val initial: String,
    ) : NameDialog

    data class RenameVersion(
        val id: String,
        override val initial: String,
    ) : NameDialog

    /** Presets → Save current as preset (Stage 11). */
    data class NewPreset(
        override val initial: String,
    ) : NameDialog
}

/** Menus that drop down from a button. */
enum class EditorMenu { More, AddLayer, AddMask }

/** The eyedropper loupe: where the finger is and the 9 x 9 original pixels under it. */
class Loupe(
    val viewX: Float,
    val viewY: Float,
    /** RGBA working space, top row first; null until sampled. */
    val pixels: FloatArray?,
)

/** What a targeted drag changes: the main band under the finger and its value. */
data class TargetedHud(
    val band: HslBand,
    val channel: HslChannel,
    val value: Float,
)

data class EditorState(
    val project: Project? = null,
    val loaded: Boolean = false,
    /** The engine drawing the canvas, once the original is open. */
    val canvas: EditorCanvas? = null,
    val image: ImageSummary? = null,
    /** True when the original could not be decoded for the canvas (e.g. RAW before Stage 25). */
    val previewUnavailable: Boolean = false,
    /** Why the image engine could not start (shown instead of the photo), or null. */
    val engineError: String? = null,
    /** Sharp tiles have been loading for more than 300 ms: show the subtle spinner. */
    val showSpinner: Boolean = false,
    /** The edit: the active version's current graph (saved to the project's .loproj, Stage 8). */
    val graph: EditGraph = EditGraph(),
    /** Undo / redo of the active version: graph snapshots, never pixels. */
    val history: EditHistory = EditHistory.start(),
    /** Versions (each with its own history) and snapshots; the active version's history is [history]. */
    val document: ProjectDocument = ProjectDocument(),
    /** A History row or snapshot previewed on the canvas; Restore applies it. */
    val preview: HistoryPreview? = null,
    val nameDialog: NameDialog? = null,
    /** Thumbnails of the versions (History → Versions), by version id. */
    val versionThumbnails: Map<String, Bitmap> = emptyMap(),
    /** The saved project could not be read (e.g. a newer format): edits are not saved over it. */
    val saveBlocked: String? = null,
    val tapGestures: TapGestures = TapGestures.Standard,
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
    /** The Color basics (Stage 6). */
    val color: ColorAdjustments = ColorAdjustments(),
    /** Curves and grading (Stage 7). */
    val grade: GradeAdjustments = GradeAdjustments(),
    /** Crop and Optics (Stage 9): one geometry op, applied first. */
    val geometry: GeometryAdjustments = GeometryAdjustments(),
    /** Detail and Effects (Stage 10). */
    val detail: DetailAdjustments = DetailAdjustments(),
    /** A second finger is down on Sharpen's Masking slider: the canvas shows the mask in grey. */
    val sharpenMaskPreview: Boolean = false,
    /** Each increment asks the canvas for the 1:1 toggle (100 % at the middle, or back to fit). */
    val oneToOneRequest: Int = 0,
    /** Profile and 3D LUT (Stage 11). */
    val looks: LookAdjustments = LookAdjustments(),
    /** Imported LUTs, favourite presets, copied settings. */
    val looksLibrary: LooksState = LooksState(),
    val userPresets: List<Preset> = emptyList(),
    /** The preset whose Amount the slider moves (null once something else is edited). */
    val appliedPreset: AppliedPreset? = null,
    /** A thumbnail held down: the canvas shows its look until released. */
    val compareGraph: EditGraph? = null,
    /** Strip thumbnails (presets, profiles, LUTs) by [LookTarget.key], rendered from the 256 px proxy. */
    val lookThumbnails: Map<String, Bitmap> = emptyMap(),
    /** The copy / paste checklist. */
    val copyGroups: Set<SettingsGroup> = SettingsGroup.DEFAULTS,
    /** The composition overlay shown while a crop handle or the straighten ruler is dragged. */
    val cropOverlay: CropOverlay = CropOverlay.Thirds,
    val cropDragging: Boolean = false,
    /** The lens profile database (downloaded on request) and the lenses it has for this photo. */
    val lensProfiles: LensProfileState = LensProfileState.Missing,
    val lensCandidates: List<String> = emptyList(),
    /** What the HSL panel's sliders change. */
    val hslChannel: HslChannel = HslChannel.Hue,
    /** Eyedropper or targeted adjustment armed: one finger picks on the canvas. */
    val pickMode: PickMode = PickMode.None,
    /** The eyedropper loupe while the finger is down. */
    val loupe: Loupe? = null,
    /** The targeted adjustment's band under the finger while dragging. */
    val targeted: TargetedHud? = null,
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
    /** The layer the Layers panel edits (Stage 13); null = the photo. */
    val selectedLayerId: String? = null,
    /** Stage 14: the selected layer's mask is the target (one finger paints it). */
    val maskTarget: Boolean = false,
    val maskBrush: MaskBrush = MaskBrush(),
    val maskView: MaskView = MaskView.None,
    /** Stage 15: the Select tools' settings (options bar and tool panels). */
    val selectionSettings: SelectionSettings = SelectionSettings(),
    /** Pictures of saved selections (mask thumbnails), by raster name. */
    val maskThumbnails: Map<String, ImageBitmap> = emptyMap(),
) {
    val selectedToolId: String? get() = (panel as? EditorPanel.Tool)?.id

    /** The graph on the canvas: the previewed History state, or the edit. */
    val shownGraph: EditGraph
        get() =
            when (val p = preview) {
                is HistoryPreview.Step -> history.entries.getOrNull(p.index)?.graph ?: graph
                is HistoryPreview.SnapshotState -> document.snapshots.firstOrNull { it.id == p.id }?.graph ?: graph
                null -> graph
            }

    /** Clipping warnings are drawn: the overlay is on, or a slider's second finger is down. */
    val clippingShown: Boolean get() = CLIPPING_TOOL in overlays || clippingPreview

    val histogramShown: Boolean get() = HISTOGRAM_TOOL in overlays

    /** The engine's histogram is needed: the overlay is on, or a curve / levels panel draws it. */
    val histogramNeeded: Boolean get() = histogramShown || selectedToolId in HISTOGRAM_PANELS

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
        val HISTOGRAM_PANELS = setOf("light.curves_point", "light.curves_parametric", "light.levels")
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

    /** Color (Stage 6). */
    data class SetWhiteBalance(
        val kelvin: Float,
        val tint: Float,
    ) : EditorIntent

    /** White balance → As shot: the camera's own balance (no change). */
    data object AsShot : EditorIntent

    data class SetVibrance(
        val amount: Float,
    ) : EditorIntent

    data class SetSaturation(
        val amount: Float,
    ) : EditorIntent

    data class SetHsl(
        val channel: HslChannel,
        val band: HslBand,
        val value: Float,
    ) : EditorIntent

    data class SelectHslChannel(
        val channel: HslChannel,
    ) : EditorIntent

    data object ToggleEyedropper : EditorIntent

    data object ToggleTargeted : EditorIntent

    data class SetInvert(
        val on: Boolean,
    ) : EditorIntent

    /** Posterize levels 2..32, 0 = off. */
    data class SetPosterize(
        val levels: Int,
    ) : EditorIntent

    /** Threshold level 0..1, null = off. */
    data class SetThreshold(
        val level: Float?,
    ) : EditorIntent

    data class EyedropperMove(
        val viewX: Float,
        val viewY: Float,
        val u: Float,
        val v: Float,
    ) : EditorIntent

    data class EyedropperEnd(
        val u: Float?,
        val v: Float?,
    ) : EditorIntent

    data class TargetedStart(
        val u: Float,
        val v: Float,
    ) : EditorIntent

    data class TargetedDrag(
        val fraction: Float,
    ) : EditorIntent

    data object TargetedEnd : EditorIntent

    /** Detail and Effects (Stage 10): one slider. */
    data class SetDetail(
        val param: DetailParam,
        val value: Float,
    ) : EditorIntent

    /** The second finger on Sharpen's Masking slider: the grey mask preview while it stays. */
    data class SharpenMaskPreview(
        val on: Boolean,
    ) : EditorIntent

    /** The detail tools' 1:1 button. */
    data object ToggleOneToOne : EditorIntent

    /** Curves and grading (Stage 7): any change of [GradeAdjustments]. */
    class EditGrade(
        val edit: (GradeAdjustments) -> GradeAdjustments,
    ) : EditorIntent

    /** Levels → Auto: per-channel black and white points from the preview. */
    data object AutoLevels : EditorIntent

    /** History (Stage 8). */
    data object Undo : EditorIntent

    data object Redo : EditorIntent

    /** A slider, wheel or curve point was released: the drag becomes one History step. */
    data object EndEdit : EditorIntent

    /** A Crop / Optics change (Stage 9). */
    data class Geometry(
        val action: GeometryAction,
    ) : EditorIntent

    /** Presets, Profiles, 3D LUTs, Copy / paste, Auto (Stage 11). */
    data class Looks(
        val action: LooksAction,
    ) : EditorIntent

    /** The Layers panel (Stage 13). */
    data class Layers(
        val action: LayerAction,
    ) : EditorIntent

    /** The Select tools (Stage 15). */
    data class Select(
        val action: SelectAction,
    ) : EditorIntent

    /** Two- or three-finger tap on the canvas (Settings → Gestures maps it to undo / redo). */
    data class MultiFingerTap(
        val fingers: Int,
    ) : EditorIntent

    /** The app goes to the background (onStop): save now. */
    data object SaveNow : EditorIntent

    data class Preview(
        val preview: HistoryPreview?,
    ) : EditorIntent

    /** Applies the previewed History row or snapshot. */
    data object RestorePreview : EditorIntent

    /** Undo of a Restore (from its snackbar): back to step [index]. */
    data class JumpTo(
        val index: Int,
    ) : EditorIntent

    data class OpenNameDialog(
        val dialog: NameDialog?,
    ) : EditorIntent

    /** The name dialog's OK. */
    data class ConfirmName(
        val name: String,
    ) : EditorIntent

    data class DeleteSnapshot(
        val id: String,
    ) : EditorIntent

    data class SwitchVersion(
        val id: String,
    ) : EditorIntent

    data class DeleteVersion(
        val id: String,
    ) : EditorIntent

    /** Undo of a deletion (from its snackbar): the document as it was. */
    class RestoreDocument(
        val document: ProjectDocument,
        val history: EditHistory,
    ) : EditorIntent
}

sealed interface EditorEffect {
    data class Message(
        val text: String,
    ) : EditorEffect

    /** A destructive action's snackbar: its "Undo" sends [undo]. */
    class Undoable(
        val text: String,
        val undo: EditorIntent,
    ) : EditorEffect
}

/**
 * The editor of one project. Stage 3 is the shell: every tool has its slot and opens a
 * placeholder; the engine (Stage 4) renders the edit, the Light tools (Stage 5) are the
 * first that change it.
 */
@Suppress("TooManyFunctions") // MVI: the single ViewModel of the editor screen (ARCHITECTURE.md)
class EditorViewModel(
    private val projectId: Long,
    private val library: ProjectLibrary,
    private val preferences: AppPreferences,
    private val canvases: EditorCanvasFactory,
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Default,
    debuggable: Boolean = false,
    /** Saves that must finish after the screen is gone (the app scope): onStop and close. */
    private val saveScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
    private val autosaveMillis: Long = AUTOSAVE_MILLIS,
    lensProfiles: LensProfileStore? = null,
    looks: LooksStore? = null,
    layers: LayerSources? = null,
    maskRasters: MaskRasters? = null,
) : MviViewModel<EditorState, EditorIntent, EditorEffect>(
        EditorState(
            debuggable = debuggable,
            category = toolCategoryOf(preferences.lastCategory.value) ?: EditorState.DEFAULT_CATEGORY,
            favorites = preferences.favorites.value,
            coachStep = if (preferences.coachMarksSeen.value) null else 0,
            surround = preferences.surround.value,
            tapGestures = preferences.tapGestures.value,
        ),
    ) {
    private val geometryActions =
        EditorGeometryActions(
            scope = viewModelScope,
            dispatcher = renderDispatcher,
            state = { state.value },
            reduce = { label, block -> reduce(label, block) },
            endEdit = { onIntent(EditorIntent.EndEdit) },
            message = { sendEffect(EditorEffect.Message(it)) },
            setState = { block -> setState(block) },
            lensProfiles = lensProfiles,
        )

    /** What the Stage 11 and Stage 13 actions need of this ViewModel. */
    private val actionsHost =
        object : EditorLooksActions.Host, EditorLayersActions.Host, EditorSelectionActions.Host {
            override val state get() = this@EditorViewModel.state.value

            override fun reduce(
                label: String?,
                block: EditorState.() -> EditorState,
            ) = this@EditorViewModel.reduce(label, block)

            override fun reduceStep(
                label: String?,
                key: String?,
                block: EditorState.() -> EditorState,
            ) = this@EditorViewModel.reduceStep(label, key, block)

            override fun setState(block: EditorState.() -> EditorState) = this@EditorViewModel.setState(block)

            override fun message(text: String) = sendEffect(EditorEffect.Message(text))

            override fun undoable(
                text: String,
                undo: EditorIntent,
            ) = sendEffect(EditorEffect.Undoable(text, undo))

            override fun resubmit() {
                scheduler?.submit(this@EditorViewModel.state.value.canvasGraph)
            }

            override fun endEdit() = onIntent(EditorIntent.EndEdit)
        }

    private val looksActions = EditorLooksActions(viewModelScope, renderDispatcher, actionsHost, looks)

    private val layersActions = EditorLayersActions(viewModelScope, renderDispatcher, actionsHost, layers)

    private val selectionActions = EditorSelectionActions(viewModelScope, renderDispatcher, actionsHost, maskRasters, layers)

    /** Crop handles, perspective corners and guide lines from CanvasView. */
    val cropGestures get() = geometryActions.gestures

    private val autosave =
        EditorAutosave(
            projectId,
            library,
            saveScope,
            renderDispatcher,
            autosaveMillis,
            object : EditorAutosave.Host {
                override fun current() = state.value

                override fun saveFailed(message: String) = sendEffect(EditorEffect.Message(message))
            },
        )

    init {
        viewModelScope.launch { library.markOpened(projectId) }
        viewModelScope.launch {
            library.observe(projectId).collect { project -> setState { copy(project = project, loaded = true) } }
        }
        viewModelScope.launch {
            val project = library.observe(projectId).filterNotNull().first()
            loadDocument(project)
            openCanvas(project)
        }
        viewModelScope.launch { preferences.favorites.collect { setState { copy(favorites = it) } } }
        viewModelScope.launch { preferences.surround.collect { setState { copy(surround = it) } } }
        viewModelScope.launch { preferences.tapGestures.collect { setState { copy(tapGestures = it) } } }
    }

    /** Reads the saved edit (versions, histories, snapshots) before the canvas opens. */
    private suspend fun loadDocument(project: Project) {
        when (val load = library.loadDocument(project)) {
            is DocumentLoad.Loaded -> {
                val document = load.document
                setState { copy(document = document, history = document.active.history).showingGraph(document.graph) }
                autosave.loaded(if (load.fresh) null else document)
            }

            is DocumentLoad.Failed -> {
                setState { copy(saveBlocked = load.reason) }
                sendEffect(EditorEffect.Message("${load.reason}. Edits will not be saved."))
            }
        }
    }

    private var scheduler: RenderScheduler? = null

    private suspend fun openCanvas(project: dev.mark.lineover.core.model.Project) {
        val canvas =
            try {
                canvases.open(project)
            } catch (e: CanvasOpenException) {
                setState { copy(previewUnavailable = true, engineError = e.message) }
                return
            }
        if (canvas == null) {
            setState { copy(previewUnavailable = true) }
            return
        }
        setState { copy(canvas = canvas, image = canvas.summary, previewUnavailable = false) }
        // Edits reach the engine through the scheduler: the latest graph wins.
        val renderScheduler = RenderScheduler({ graph -> canvas.setGraph(graph) }, viewModelScope, renderDispatcher)
        scheduler = renderScheduler
        looksActions.onCanvasGraph(state.value.canvasGraph)
        layersActions.onCanvasGraph(state.value.canvasGraph)
        selectionActions.onCanvasGraph(state.value.canvasGraph)
        renderScheduler.submit(state.value.shownGraph)
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
        autosave.finish(state.value.canvas)
    }

    /** Autosave: 5 s after the last change; an idle drag's step closes before the save. */
    private fun scheduleSave() = autosave.schedule(viewModelScope) { setState { copy(history = history.close()) } }

    override fun onIntent(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.SelectCategory -> {
                selectCategory(intent.category)
            }

            is EditorIntent.SelectTool -> {
                selectTool(intent.id)
            }

            is EditorIntent.OpenPanel -> {
                reduce { copy(panel = if (panel == intent.panel) null else intent.panel, menu = null, preview = null) }
                refreshVersionThumbnails()
            }

            is EditorIntent.ClosePanel -> {
                reduce {
                    copy(
                        panel = null,
                        pickMode = PickMode.None,
                        loupe = null,
                        targeted = null,
                        preview = null,
                        cropDragging = false,
                        history = history.close(),
                        appliedPreset = null,
                        compareGraph = null,
                    )
                }
            }

            is EditorIntent.SelectHistoryTab -> {
                setState { copy(historyTab = intent.tab, panel = EditorPanel.History, preview = null) }
                refreshVersionThumbnails()
            }

            is EditorIntent.OpenMenu -> {
                setState { copy(menu = intent.menu) }
            }

            is EditorIntent.ToggleOverlay -> {
                reduce { copy(overlays = overlays.toggle(intent.id), menu = null) }
            }

            is EditorIntent.ToggleFavorite -> {
                toggleFavorite(intent.id)
            }

            is EditorIntent.OpenSearch, is EditorIntent.CloseSearch, is EditorIntent.Query -> {
                search(intent)
            }

            is EditorIntent.NextCoachMark, is EditorIntent.DismissCoachMarks, is EditorIntent.ShowHelp -> {
                coach(intent)
            }

            else -> {
                onOtherIntent(intent)
            }
        }
    }

    /** Crop / Optics (Stage 9), History (Stage 8), then the editing tools. */
    private fun onOtherIntent(intent: EditorIntent) {
        when {
            intent is EditorIntent.Geometry -> geometryActions.onAction(intent.action)
            intent is EditorIntent.Looks -> looksActions.onAction(intent.action)
            intent is EditorIntent.Layers -> layersActions.onAction(intent.action)
            intent is EditorIntent.Select -> selectionActions.onAction(intent.action)
            !onHistoryIntent(intent) -> onEditIntent(intent)
        }
    }

    /** Undo / redo, History rows, snapshots, versions and saving (Stage 8); false if not one. */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // one branch per History intent
    private fun onHistoryIntent(intent: EditorIntent): Boolean {
        when (intent) {
            is EditorIntent.Undo -> {
                undo(fromGesture = false)
            }

            is EditorIntent.Redo -> {
                redo(fromGesture = false)
            }

            is EditorIntent.MultiFingerTap -> {
                multiFingerTap(intent.fingers)
            }

            is EditorIntent.EndEdit -> {
                setState { copy(history = history.close()) }
            }

            is EditorIntent.SaveNow -> {
                setState { copy(history = history.close()) }
                autosave.saveNow()
            }

            is EditorIntent.Preview -> {
                reduceHistory { copy(preview = intent.preview, history = history.close()) }
            }

            is EditorIntent.RestorePreview -> {
                restorePreview()
            }

            is EditorIntent.JumpTo -> {
                reduceHistory { jumpedTo(intent.index) }
            }

            is EditorIntent.OpenNameDialog -> {
                setState { copy(nameDialog = intent.dialog) }
            }

            is EditorIntent.ConfirmName -> {
                confirmName(intent.name.trim())
            }

            is EditorIntent.DeleteSnapshot -> {
                deleteSnapshot(intent.id)
            }

            is EditorIntent.SwitchVersion -> {
                reduceHistory { withActiveVersion(intent.id) }
            }

            is EditorIntent.DeleteVersion -> {
                deleteVersion(intent.id)
            }

            is EditorIntent.RestoreDocument -> {
                reduceHistory { copy(document = intent.document, history = intent.history).showingGraph(intent.history.current) }
            }

            else -> {
                return false
            }
        }
        return true
    }

    /** Deletes a snapshot; its snackbar's Undo puts the document back. */
    private fun deleteSnapshot(id: String) {
        val before = state.value
        val name =
            before.document.snapshots
                .firstOrNull { it.id == id }
                ?.name ?: return
        reduceHistory { withoutSnapshot(id) }
        sendEffect(EditorEffect.Undoable("Deleted snapshot \"$name\"", EditorIntent.RestoreDocument(before.documentNow(), before.history)))
    }

    private fun deleteVersion(id: String) {
        val before = state.value
        val name =
            before.document.versions
                .firstOrNull { it.id == id }
                ?.name ?: return
        val after = before.withoutVersion(id)
        if (after == null) {
            sendEffect(EditorEffect.Message("A project keeps at least one version"))
            return
        }
        reduceHistory { after }
        sendEffect(EditorEffect.Undoable("Deleted version \"$name\"", EditorIntent.RestoreDocument(before.documentNow(), before.history)))
    }

    /** setState for History changes: the canvas follows, thumbnails refresh, the autosave restarts. */
    private fun reduceHistory(block: EditorState.() -> EditorState) {
        reduce(block = block)
        refreshVersionThumbnails()
        scheduleSave()
    }

    private fun undo(fromGesture: Boolean) {
        val label = state.value.history.undoLabel
        val next = state.value.undone()
        if (next == null) {
            if (fromGesture) sendEffect(EditorEffect.Message("Nothing to undo"))
            return
        }
        reduceHistory { next }
        if (fromGesture) sendEffect(EditorEffect.Message("Undo: $label"))
    }

    private fun redo(fromGesture: Boolean) {
        val label = state.value.history.redoLabel
        val next = state.value.redone()
        if (next == null) {
            if (fromGesture) sendEffect(EditorEffect.Message("Nothing to redo"))
            return
        }
        reduceHistory { next }
        if (fromGesture) sendEffect(EditorEffect.Message("Redo: $label"))
    }

    private fun multiFingerTap(fingers: Int) {
        val undoFingers =
            when (state.value.tapGestures) {
                TapGestures.Standard -> TWO_FINGERS
                TapGestures.Swapped -> THREE_FINGERS
                TapGestures.Off -> return
            }
        when (fingers) {
            undoFingers -> undo(fromGesture = true)
            TWO_FINGERS, THREE_FINGERS -> redo(fromGesture = true)
        }
    }

    private fun restorePreview() {
        val before = state.value
        val (after, label) = before.restored() ?: return
        reduceHistory { after }
        val previousIndex = before.history.index
        if (before.preview is HistoryPreview.Step) {
            sendEffect(EditorEffect.Undoable("Restored \"$label\"", EditorIntent.JumpTo(previousIndex)))
        } else {
            sendEffect(EditorEffect.Undoable("Applied snapshot \"$label\"", EditorIntent.Undo))
        }
    }

    private fun confirmName(name: String) {
        val dialog = state.value.nameDialog ?: return
        val chosen = name.ifEmpty { dialog.initial }.take(MAX_NAME)
        setState { copy(nameDialog = null) }
        when (dialog) {
            is NameDialog.NewSnapshot -> reduceHistory { withSnapshot(chosen, clock()) }
            is NameDialog.NewVersion -> reduceHistory { withNewVersion(chosen, clock()) }
            is NameDialog.RenameVersion -> reduceHistory { withVersionName(dialog.id, chosen) }
            is NameDialog.NewPreset -> looksActions.savePreset(chosen)
        }
    }

    /** Save current as preset: every group but crop and optics. */
    private var thumbnailJob: Job? = null

    /** Renders the versions' thumbnails while the Versions tab is open (off the main thread). */
    private fun refreshVersionThumbnails() {
        val current = state.value
        val canvas = current.canvas ?: return
        if (current.panel != EditorPanel.History || current.historyTab != HistoryTab.Versions) return
        thumbnailJob?.cancel()
        val versions = current.documentNow().versions
        thumbnailJob =
            viewModelScope.launch {
                val thumbs =
                    withContext(renderDispatcher) { versions.mapNotNull { v -> canvas.thumbnail(v.graph)?.let { v.id to it } }.toMap() }
                setState { copy(versionThumbnails = thumbs) }
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

    @Suppress("CyclomaticComplexMethod") // one line per Color intent
    private fun onColorIntent(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.SetWhiteBalance -> reduce { withColor(color.withWhiteBalance(intent.kelvin, intent.tint)) }
            is EditorIntent.AsShot -> reduce(AS_SHOT) { withColor(color.withWhiteBalance(ColorAdjustments.DEFAULT_KELVIN, 0f)) }
            is EditorIntent.SetVibrance -> reduce { withColor(color.copy(vibrance = intent.amount.coerceIn(-RANGE, RANGE))) }
            is EditorIntent.SetSaturation -> reduce { withColor(color.copy(saturation = intent.amount.coerceIn(-RANGE, RANGE))) }
            is EditorIntent.SetHsl -> reduce { withColor(color.withHsl(intent.channel, intent.band, intent.value)) }
            is EditorIntent.SelectHslChannel -> setState { copy(hslChannel = intent.channel) }
            is EditorIntent.ToggleEyedropper -> setState { copy(pickMode = pickMode.toggle(PickMode.Eyedropper), loupe = null) }
            is EditorIntent.ToggleTargeted -> setState { copy(pickMode = pickMode.toggle(PickMode.Targeted), targeted = null) }
            is EditorIntent.SetInvert -> reduce { withColor(color.copy(invert = intent.on)) }
            is EditorIntent.SetPosterize -> reduce { withColor(color.copy(posterize = intent.levels.coerceIn(0, MAX_POSTERIZE))) }
            is EditorIntent.SetThreshold -> reduce { withColor(color.copy(threshold = intent.level?.coerceIn(0f, 1f))) }
            is EditorIntent.EditGrade -> reduce { withGrade(intent.edit(grade)) }
            is EditorIntent.SetDetail -> reduce { withDetail(detail.with(intent.param, intent.value)) }
            is EditorIntent.SharpenMaskPreview -> reduce { copy(sharpenMaskPreview = intent.on) }
            is EditorIntent.ToggleOneToOne -> setState { copy(oneToOneRequest = oneToOneRequest + 1) }
            is EditorIntent.AutoLevels -> autoLevels()
            else -> onPickIntent(intent)
        }
    }

    /** The eyedropper and the targeted adjustment (finger on the canvas). */
    private fun onPickIntent(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.EyedropperMove -> eyedropperMove(intent)
            is EditorIntent.EyedropperEnd -> eyedropperEnd(intent)
            is EditorIntent.TargetedStart -> targetedStart(intent.u, intent.v)
            is EditorIntent.TargetedDrag -> targetedDrag(intent.fraction)
            is EditorIntent.TargetedEnd -> setState { copy(targeted = null, history = history.close()) }
            else -> Unit
        }
    }

    private fun PickMode.toggle(mode: PickMode) = if (this == mode) PickMode.None else mode

    private fun EditorState.withColor(next: ColorAdjustments): EditorState =
        copy(color = next, graph = graph.withBackgroundOps(editOps(light, next, grade, geometryOp, detail, looks = looks)))

    private fun EditorState.withGrade(next: GradeAdjustments): EditorState =
        copy(grade = next, graph = graph.withBackgroundOps(editOps(light, color, next, geometryOp, detail, looks = looks)))

    private fun EditorState.withDetail(next: DetailAdjustments): EditorState =
        copy(detail = next, graph = graph.withBackgroundOps(editOps(light, color, grade, geometryOp, next, looks = looks)))

    private fun autoLevels() {
        val percentiles = state.value.canvas?.autoLevels()
        if (percentiles == null) {
            sendEffect(EditorEffect.Message("Auto needs the photo to finish loading"))
            return
        }
        reduce("Auto levels") { withGrade(grade.withAutoLevels(percentiles)) }
    }

    // Sampling blocks on the render thread: off the main thread, the latest request wins.
    private var loupeJob: Job? = null
    private var targetedWeights: FloatArray? = null

    private fun eyedropperMove(move: EditorIntent.EyedropperMove) {
        val canvas = state.value.canvas
        setState { copy(loupe = Loupe(move.viewX, move.viewY, loupe?.pixels)) }
        if (canvas == null || !inImage(move.u, move.v)) return
        loupeJob?.cancel()
        loupeJob =
            viewModelScope.launch {
                val pixels = withContext(renderDispatcher) { canvas.loupeAt(move.u, move.v) }
                setState { if (loupe == null) this else copy(loupe = Loupe(move.viewX, move.viewY, pixels)) }
            }
    }

    private fun eyedropperEnd(end: EditorIntent.EyedropperEnd) {
        loupeJob?.cancel()
        setState { copy(loupe = null) }
        val canvas = state.value.canvas
        // A cancel (second finger, null point) or a lift outside the photo picks nothing.
        val u = end.u?.takeIf { it in 0f..1f }
        val v = end.v?.takeIf { it in 0f..1f }
        if (u == null || v == null || canvas == null) return
        viewModelScope.launch {
            val wb = withContext(renderDispatcher) { canvas.whiteBalanceAt(u, v) }
            if (wb == null) {
                sendEffect(EditorEffect.Message("Pick something lighter: black has no colour to balance"))
            } else {
                // One pick, then the eyedropper puts itself away (Lightroom-like).
                reduce(
                    "White balance: eyedropper",
                ) { withColor(color.withWhiteBalance(wb.first, wb.second)).copy(pickMode = PickMode.None) }
            }
        }
    }

    private fun targetedStart(
        u: Float,
        v: Float,
    ) {
        targetedWeights = null
        val canvas = state.value.canvas ?: return
        if (!inImage(u, v)) return
        viewModelScope.launch {
            val weights = withContext(renderDispatcher) { canvas.hslWeightsAt(u, v) } ?: return@launch
            targetedWeights = weights
            updateTargetedHud(weights)
        }
    }

    private fun targetedDrag(fraction: Float) {
        val weights = targetedWeights ?: return
        val channel = state.value.hslChannel
        reduce { withColor(color.nudgeHsl(channel, weights, fraction * TARGETED_RANGE_PER_HEIGHT)) }
        updateTargetedHud(weights)
    }

    private fun updateTargetedHud(weights: FloatArray) {
        val main = weights.indices.maxBy { weights[it] }
        if (weights[main] <= 0f) {
            setState { copy(targeted = null) }
            return
        }
        val band = HslBand.entries[main]
        setState { copy(targeted = TargetedHud(band, hslChannel, color.hsl(hslChannel)[main])) }
    }

    private fun inImage(
        u: Float,
        v: Float,
    ) = u in 0f..1f && v in 0f..1f

    private fun onGestureIntent(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.HoldOriginal -> {
                setState { copy(showingOriginal = intent.holding) }
            }

            is EditorIntent.SwipeActive -> {
                setState { copy(swiping = intent.active && swipeMode, history = if (intent.active) history else history.close()) }
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
                onColorIntent(intent)
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

            override fun onEyedropperMove(
                viewX: Float,
                viewY: Float,
                u: Float,
                v: Float,
            ) = onIntent(EditorIntent.EyedropperMove(viewX, viewY, u, v))

            override fun onEyedropperEnd(
                u: Float?,
                v: Float?,
            ) = onIntent(EditorIntent.EyedropperEnd(u, v))

            override fun onTargetedStart(
                u: Float,
                v: Float,
            ) = onIntent(EditorIntent.TargetedStart(u, v))

            override fun onTargetedDrag(fraction: Float) = onIntent(EditorIntent.TargetedDrag(fraction))

            override fun onTargetedEnd() = onIntent(EditorIntent.TargetedEnd)

            override fun onMultiFingerTap(fingers: Int) = onIntent(EditorIntent.MultiFingerTap(fingers))

            // One finger paints a targeted mask (Stage 14) or makes a selection (Stage 15).
            private val selecting get() = state.value.selectTool != null

            override fun onPaintStart(
                u: Float,
                v: Float,
            ) = onIntent(
                if (selecting) EditorIntent.Select(SelectAction.Start(u, v)) else EditorIntent.Layers(LayerAction.PaintStart(u, v)),
            )

            override fun onPaintMove(
                u: Float,
                v: Float,
            ) = onIntent(if (selecting) EditorIntent.Select(SelectAction.Move(u, v)) else EditorIntent.Layers(LayerAction.PaintMove(u, v)))

            override fun onPaintEnd() =
                onIntent(if (selecting) EditorIntent.Select(SelectAction.End) else EditorIntent.Layers(LayerAction.PaintEnd))

            override fun onPaintLoupe(
                viewX: Float,
                viewY: Float,
                u: Float,
                v: Float,
            ) {
                if (selecting) onIntent(EditorIntent.Select(SelectAction.Loupe(viewX, viewY, u, v)))
            }
        }

    private fun EditorState.withLight(next: LightAdjustments): EditorState =
        copy(light = next, graph = graph.withBackgroundOps(editOps(next, color, grade, geometryOp, detail, looks = looks)))

    private fun autoLight() {
        val result = state.value.canvas?.autoLight()
        if (result == null) {
            sendEffect(EditorEffect.Message("Auto needs the photo to finish loading"))
            return
        }
        val (exposure, blacks) = result
        reduce("Auto light") { withLight(light.with(LightParam.Exposure, exposure).with(LightParam.Blacks, blacks)) }
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

    /**
     * setState, then hands what changed to the engine. A changed edit becomes a History step
     * (a drag of one control coalesces; [label] names a one-off action) and restarts the autosave.
     */
    private fun reduce(
        label: String? = null,
        block: EditorState.() -> EditorState,
    ) = reduceStep(label, null, block)

    /** [reduce] as a keyed History step ([recordEdit]'s stepKey). */
    private fun reduceStep(
        label: String?,
        stepKey: String?,
        block: EditorState.() -> EditorState,
    ) {
        val before = state.value
        setState { block().let { if (it.graph != it.history.current) it.recordEdit(label, stepKey = stepKey) else it } }
        val after = state.value
        syncCanvas(before, after)
        if (after.history != before.history && after.document == before.document) scheduleSave()
    }

    private fun syncCanvas(
        before: EditorState?,
        after: EditorState,
    ) {
        val canvas = after.canvas ?: return
        // While cropping the canvas shows the whole frame (canvasGraph), else the shown edit.
        if (before == null || before.canvasGraph != after.canvasGraph) {
            looksActions.onCanvasGraph(after.canvasGraph)
            layersActions.onCanvasGraph(after.canvasGraph)
            selectionActions.onCanvasGraph(after.canvasGraph)
            scheduler?.submit(after.canvasGraph)
        }
        looksActions.afterSync(before, after)
        layersActions.afterSync(before, after)
        selectionActions.afterSync(before, after)
        if (before == null || before.clippingShown != after.clippingShown) canvas.setClipping(after.clippingShown)
        if (before == null || before.compare != after.compare || before.split != after.split) {
            canvas.setCompare(after.compare, after.split)
        }
        if (before == null || before.histogramNeeded != after.histogramNeeded) canvas.setHistogramEnabled(after.histogramNeeded)
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
        // reduce: opening a curves / levels panel switches the engine's histogram on.
        reduce {
            val sameTool = selectedToolId == id
            copy(
                // Opening a tool from Favorites keeps the Favorites strip in view.
                category = if (category != null && this.category != ToolCategory.Favorites) category else this.category,
                panel = if (sameTool) null else EditorPanel.Tool(id),
                menu = null,
                pickMode = PickMode.None,
                searchOpen = false,
                // A preset's Amount belongs to its panel; a held thumbnail never outlives it.
                appliedPreset = null,
                compareGraph = null,
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
        const val MAX_POSTERIZE = 32
        private const val RANGE = ColorAdjustments.RANGE

        /** A targeted drag over the whole canvas height moves the band by 200 (-100 → +100). */
        const val TARGETED_RANGE_PER_HEIGHT = 200f

        /** The spinner appears only when sharp tiles take longer than this (Stage 4 UX). */
        const val SPINNER_DELAY_MILLIS = 300L

        /** Autosave once the editor has been idle this long (and on onStop). */
        const val AUTOSAVE_MILLIS = 5_000L
        private const val AS_SHOT = "White balance: as shot"
        const val TWO_FINGERS = 2
        const val THREE_FINGERS = 3
        const val MAX_NAME = 40
    }
}
