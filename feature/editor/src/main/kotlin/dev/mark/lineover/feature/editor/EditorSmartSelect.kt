package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.engine.SelectionImage
import dev.mark.lineover.core.io.masks.MaskRaster
import dev.mark.lineover.core.io.masks.MaskRasters
import dev.mark.lineover.core.ml.ModelError
import dev.mark.lineover.core.ml.ModelImage
import dev.mark.lineover.core.ml.ModelInfo
import dev.mark.lineover.core.ml.ModelMask
import dev.mark.lineover.core.ml.ModelState
import dev.mark.lineover.core.ml.SegmentPrompt
import dev.mark.lineover.core.ml.SmartModels
import dev.mark.lineover.core.ml.SmartTool
import dev.mark.lineover.core.ml.message
import dev.mark.lineover.core.ml.sizeText
import dev.mark.lineover.core.model.EditGraphJson
import dev.mark.lineover.core.model.Selection
import dev.mark.lineover.core.model.SelectionMode
import dev.mark.lineover.core.model.SelectionOp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.hypot

/** The Select tools of Stage 16 (smart selections). */
internal object SmartTools {
    const val MAGNETIC = "select.magnetic_lasso"
    const val QUICK = "select.quick_selection"
    const val OBJECT = "select.object_select"
    const val SUBJECT = "select.subject_select"
    const val SKY = "select.sky_select"
    const val REFINE = "select.refine_edge"
    val ALL = setOf(MAGNETIC, QUICK, OBJECT, SUBJECT, SKY, REFINE)

    /** Tools whose drag paints with the add / subtract brush. */
    val BRUSHING = setOf(SUBJECT, SKY, REFINE)
}

/** The first-use sheet of a tool's model (Stage 16, rule D3). */
data class ModelSheet(
    val tool: SmartTool,
    val info: ModelInfo,
    val state: ModelState,
    val wifiOnly: Boolean,
)

/** The smart selection tools' settings and progress (Stage 16). */
data class SmartSelectState(
    /** A model is running for this tool: the canvas shimmers. */
    val busy: SmartTool? = null,
    val sheet: ModelSheet? = null,
    /** The add / subtract brush (document px) of Select subject / sky and Refine edge. */
    val brushRadius: Float = DEFAULT_BRUSH,
    val brushSubtract: Boolean = false,
    /** Object select: taps and scribbles remove instead of add. */
    val objectSubtract: Boolean = false,
    val quickRadius: Float = DEFAULT_QUICK,
    val refineRadius: Float = DEFAULT_REFINE_RADIUS,
    val refineContrast: Float = DEFAULT_REFINE_CONTRAST,
    /** The last run, e.g. "Select subject: 0.62 s (model 0.41 s on GPU)". */
    val lastRun: String? = null,
) {
    companion object {
        const val DEFAULT_BRUSH = 40f
        const val DEFAULT_QUICK = 30f
        const val DEFAULT_REFINE_RADIUS = 8f
        const val DEFAULT_REFINE_CONTRAST = 20f
        const val MAX_BRUSH = 400f
        const val MAX_REFINE_RADIUS = 60f
        const val MAX_CONTRAST = 100f
    }
}

/** What the Stage 16 tools do (part of [SelectAction]). */
sealed interface SmartAction : SelectAction {
    /** The one-tap buttons: Select subject / Select sky. */
    data class Run(
        val tool: SmartTool,
    ) : SmartAction

    /** The sheet's Download ([allowMetered]: on mobile data although Wi-Fi only is on). */
    data class Download(
        val allowMetered: Boolean = false,
    ) : SmartAction

    data object DismissSheet : SmartAction

    data class Configure(
        val smart: SmartSelectState,
    ) : SmartAction

    /** Refine edge: the sliders re-run the last refine (a live preview); Apply adds one. */
    data class SetRefine(
        val radius: Float,
        val contrast: Float,
    ) : SmartAction

    data object ApplyRefine : SmartAction
}

/** The open tool's model, for the Stage 16 tools that have one. */
internal fun smartToolOf(toolId: String?): SmartTool? =
    when (toolId) {
        SmartTools.SUBJECT -> SmartTool.Subject
        SmartTools.SKY -> SmartTool.Sky
        SmartTools.OBJECT -> SmartTool.Object
        else -> null
    }

/**
 * The Stage 16 tools of [EditorSelectionActions]: models run on a background thread with the
 * canvas shimmering; their masks are saved as pixels (MaskRasters) and become a selection op
 * (SelectionOp.Raster) the engine upsamples against the image. A model that is not on the device
 * first shows its sheet (name, size, licence, Download), then runs once it has arrived.
 */
@Suppress("TooManyFunctions") // one function per gesture and action
internal class EditorSmartActions(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val host: Host,
    private val models: SmartModels?,
    private val rasters: MaskRasters?,
    private val wifiOnly: () -> Boolean,
) {
    /** What the actions need of [EditorSelectionActions]. */
    interface Host {
        val state: EditorState

        fun reduce(
            label: String?,
            block: EditorState.() -> EditorState,
        )

        fun setState(block: EditorState.() -> EditorState)

        fun message(text: String)

        fun endEdit()

        /** Adds [op] to the selection (a new shape in mode New starts again). */
        fun addShape(
            label: String?,
            op: SelectionOp.Shape,
        )

        /** Replaces the selection's last op with [op]. */
        fun replaceLast(
            label: String?,
            op: SelectionOp,
        )

        /** Remembers a mask raster the engine now has (so it is not registered again). */
        fun registered(name: String)

        fun doc(
            u: Float,
            v: Float,
        ): Pair<Float, Float>?

        fun longEdge(): Float
    }

    private val state get() = host.state
    private val smart get() = state.smart
    private val settings get() = state.selectionSettings

    // The gesture in progress.
    private var points = mutableListOf<Float>()
    private var stroke = mutableListOf<Pair<Float, Float>>()
    private var strokeMode = SelectionMode.New

    // Object select: the prompts of the object being built, and the op they made.
    private val prompts = mutableListOf<SegmentPrompt>()
    private var objectOp: SelectionOp.Raster? = null

    // The magnetic lasso: the path fixed so far, and the live-wire calls in order.
    private val wire = Mutex()
    private var committed = listOf<Float>()
    private var anchor: Pair<Float, Float>? = null
    private var pendingTarget: Pair<Float, Float>? = null
    private var pathJob: Job? = null

    init {
        models?.let { m ->
            scope.launch { m.states.collect { states -> onModelStates(states) } }
        }
    }

    fun onAction(action: SmartAction) {
        when (action) {
            is SmartAction.Run -> run(action.tool)
            is SmartAction.Download -> download(action.allowMetered)
            is SmartAction.DismissSheet -> host.setState { copy(smart = smart.copy(sheet = null)) }
            is SmartAction.Configure -> host.setState { copy(smart = action.smart) }
            is SmartAction.SetRefine -> setRefine(action.radius, action.contrast)
            is SmartAction.ApplyRefine -> applyRefine()
        }
    }

    // --- Models ---

    private fun onModelStates(states: Map<SmartTool, ModelState>) {
        val sheet = smart.sheet ?: return
        val next = states[sheet.tool] ?: return
        host.setState { copy(smart = smart.copy(sheet = smart.sheet?.copy(state = next))) }
    }

    /** Runs [tool] on the photo, or shows its download sheet first. */
    fun run(
        tool: SmartTool,
        prompt: List<SegmentPrompt> = emptyList(),
    ) {
        val m = models ?: return host.message("${tool.label} isn't available in this build")
        if (smart.busy != null) return
        val ready = m.states.value[tool] == ModelState.Ready
        if (!ready) {
            host.setState {
                copy(
                    smart = smart.copy(sheet = ModelSheet(tool, m.info(tool), m.states.value[tool] ?: ModelState.Missing, wifiOnly())),
                )
            }
            return
        }
        segment(m, tool, prompt)
    }

    private fun download(allowMetered: Boolean) {
        val m = models ?: return
        val sheet = smart.sheet ?: return
        scope.launch {
            val result = m.prepare(sheet.tool, allowMetered)
            host.setState { copy(smart = smart.copy(sheet = smart.sheet?.copy(state = result))) }
            if (result == ModelState.Ready) {
                host.setState { copy(smart = smart.copy(sheet = null)) }
                // Then the tool runs, as if tapped again.
                if (sheet.tool == SmartTool.Object && prompts.isNotEmpty()) segment(m, sheet.tool, prompts.toList()) else run(sheet.tool)
            }
        }
    }

    private fun segment(
        m: SmartModels,
        tool: SmartTool,
        prompt: List<SegmentPrompt>,
    ) {
        val canvas = state.canvas ?: return
        val store = rasters ?: return host.message("Selections can't be saved here")
        host.setState { copy(smart = smart.copy(busy = tool)) }
        scope.launch {
            val start = System.nanoTime()
            try {
                val image = withContext(dispatcher) { canvas.selectionImage(MODEL_EDGE) }
                val mask = image?.let { m.segment(tool, it.toModelImage(), prompt) }
                val op = mask?.let { withContext(dispatcher) { save(store, it, tool) } }
                if (op == null) {
                    host.message(if (mask == null) "${tool.label} found nothing here" else "The selection could not be saved")
                } else {
                    commit(tool, op)
                    // Evaluate here (the engine skips the same recipe again), so the time is what you wait for.
                    withContext(dispatcher) { canvas.setSelection(EditGraphJson.encodeSelection(state.graph.selection)) }
                    report(m, tool, start)
                }
            } finally {
                host.setState { copy(smart = smart.copy(busy = null)) }
            }
        }
    }

    private fun report(
        m: SmartModels,
        tool: SmartTool,
        start: Long,
    ) {
        val total = (System.nanoTime() - start) / NANOS_PER_S
        val run = m.lastRun.value?.takeIf { it.tool == tool }
        val model = run?.let { " (model ${seconds(it.millis / MS_PER_S)}${it.accelerator?.let { a -> " on $a" } ?: ""})" } ?: ""
        host.setState { copy(smart = smart.copy(lastRun = "${tool.label}: ${seconds(total)}$model")) }
    }

    private fun seconds(s: Double) = String.format(Locale.ROOT, "%.2f s", s)

    /** Saves [mask] (16-bit, content-hash name), registers it and returns the op naming it. */
    private fun save(
        store: MaskRasters,
        mask: ModelMask,
        tool: SmartTool,
    ): SelectionOp.Raster? {
        if (mask.values.none { it >= HALF }) return null
        val raster = MaskRaster(mask.width, mask.height, mask.values)
        val name = store.save(raster) ?: return null
        state.canvas?.registerMaskRaster(name, raster)
        host.registered(name)
        val mode =
            if (tool == SmartTool.Object && settings.mode == SelectionMode.New &&
                objectOp != null
            ) {
                SelectionMode.Add
            } else {
                settings.mode
            }
        return SelectionOp.Raster(mode, name, mask.width.toFloat(), mask.height.toFloat(), tool.opTool())
    }

    private fun commit(
        tool: SmartTool,
        op: SelectionOp.Raster,
    ) {
        host.endEdit()
        val last =
            state.graph.selection
                ?.ops
                ?.lastOrNull()
        if (tool == SmartTool.Object && objectOp != null && last == objectOp) {
            // The same object, refined by another tap: one op, one History step per tap.
            val next = op.copy(mode = (last as SelectionOp.Raster).mode)
            host.replaceLast(next.label, next)
            objectOp = next
        } else {
            host.addShape(op.label, op)
            objectOp = op.takeIf { tool == SmartTool.Object }
        }
    }

    // --- Gestures of the Stage 16 tools ---

    fun start(
        tool: String,
        u: Float,
        v: Float,
    ) {
        points = mutableListOf()
        stroke = mutableListOf(u to v)
        val (x, y) = host.doc(u, v) ?: return
        points += listOf(x, y)
        if (tool == SmartTools.MAGNETIC) startWire(x, y)
    }

    /** A drag: true when this tool handled it. */
    fun move(
        tool: String,
        u: Float,
        v: Float,
        first: Boolean,
    ) {
        val (x, y) = host.doc(u, v) ?: return
        stroke += u to v
        when (tool) {
            SmartTools.MAGNETIC -> {
                moveWire(x, y)
            }

            SmartTools.QUICK -> {
                if (first) strokeMode = quickMode()
                brushLike(first, x, y) { SelectionOp.Quick(strokeMode, smart.quickRadius, it) }
            }

            in SmartTools.BRUSHING -> {
                brushLike(first, x, y) { SelectionOp.Brush(brushMode(), smart.brushRadius, BRUSH_HARDNESS, it) }
            }

            else -> {
                Unit
            }
        }
    }

    fun end(
        tool: String,
        moved: Boolean,
    ) {
        when (tool) {
            SmartTools.MAGNETIC -> endWire()
            SmartTools.OBJECT -> objectPrompt(moved)
            else -> if (moved) host.endEdit()
        }
    }

    private fun quickMode(): SelectionMode =
        // After the first stroke, quick selection adds (as in Photoshop) unless a mode is chosen.
        if (settings.mode == SelectionMode.New && state.graph.selection != null) SelectionMode.Add else settings.mode

    private fun brushMode(): SelectionMode = if (smart.brushSubtract) SelectionMode.Subtract else SelectionMode.Add

    /** A brush or quick-selection stroke that grows under the finger. */
    private fun brushLike(
        first: Boolean,
        x: Float,
        y: Float,
        make: (List<Float>) -> SelectionOp.Shape,
    ) {
        if (first) {
            host.endEdit()
            host.addShape(null, make(points.toList()))
        }
        val n = points.size
        if (n >= 2 && hypot(x - points[n - 2], y - points[n - 1]) < host.longEdge() * SPACING) return
        points += listOf(x, y)
        host.replaceLast(null, make(points.toList()))
    }

    private fun objectPrompt(moved: Boolean) {
        if (moved || stroke.isNotEmpty()) {
            val last =
                state.graph.selection
                    ?.ops
                    ?.lastOrNull()
            if (objectOp == null || last != objectOp) {
                prompts.clear()
                objectOp = null
            }
            val pts = if (moved) thin(stroke) else listOf(stroke.last())
            prompts += SegmentPrompt(pts, positive = !smart.objectSubtract)
            run(SmartTool.Object, prompts.toList())
        }
    }

    /** At most [MAX_PROMPT_POINTS] points of a scribble, evenly spread. */
    private fun thin(path: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (path.size <= MAX_PROMPT_POINTS) return path
        val step = path.size.toFloat() / MAX_PROMPT_POINTS
        return List(MAX_PROMPT_POINTS) { path[(it * step).toInt()] }
    }

    // --- Magnetic lasso ---

    private fun reach(): Float = host.longEdge() * WIRE_REACH

    private fun startWire(
        x: Float,
        y: Float,
    ) {
        committed = listOf(x, y)
        anchor = x to y
        host.endEdit()
        host.addShape(null, SelectionOp.Magnetic(settings.mode, settings.antiAlias, committed))
        setAnchor(x, y)
    }

    private fun setAnchor(
        x: Float,
        y: Float,
    ) {
        val canvas = state.canvas ?: return
        scope.launch { withContext(dispatcher) { wire.withLock { canvas.liveWireAnchor(x, y, reach()) } } }
    }

    private fun moveWire(
        x: Float,
        y: Float,
    ) {
        pendingTarget = x to y
        if (pathJob?.isActive == true) return
        val canvas = state.canvas ?: return
        pathJob =
            scope.launch {
                while (true) {
                    val target = pendingTarget ?: break
                    pendingTarget = null
                    val path = withContext(dispatcher) { wire.withLock { canvas.liveWirePath(target.first, target.second) } }
                    onPath(path, target)
                }
            }
    }

    /** The live path from the anchor: shown after the fixed part; far from the anchor it is fixed and a new anchor set. */
    private fun onPath(
        path: FloatArray,
        target: Pair<Float, Float>,
    ) {
        val last =
            state.graph.selection
                ?.ops
                ?.lastOrNull() as? SelectionOp.Magnetic ?: return
        val live = if (path.size >= 2) path.drop(2) else listOf(target.first, target.second)
        host.replaceLast(null, last.copy(points = committed + live))
        val a = anchor ?: return
        if (path.size >= 2 && hypot(target.first - a.first, target.second - a.second) > reach() * COMMIT_FRACTION) {
            committed = committed + live
            val n = committed.size
            anchor = committed[n - 2] to committed[n - 1]
            setAnchor(committed[n - 2], committed[n - 1])
        }
    }

    private fun endWire() {
        scope.launch {
            pathJob?.join()
            host.endEdit()
            anchor = null
        }
    }

    // --- Refine edge ---

    private fun setRefine(
        radius: Float,
        contrast: Float,
    ) {
        val r = radius.coerceIn(1f, SmartSelectState.MAX_REFINE_RADIUS)
        val c = contrast.coerceIn(0f, SmartSelectState.MAX_CONTRAST)
        host.setState { copy(smart = smart.copy(refineRadius = r, refineContrast = c)) }
        val last =
            state.graph.selection
                ?.ops
                ?.lastOrNull() as? SelectionOp.Refine ?: return
        host.replaceLast(null, last.copy(px = r, contrast = c))
    }

    private fun applyRefine() {
        val selection: Selection = state.graph.selection ?: return host.message("Select something first")
        host.endEdit()
        val op = SelectionOp.Refine(smart.refineRadius, smart.refineContrast)
        host.reduce(op.label) { copy(graph = graph.copy(selection = selection.copy(ops = selection.ops + op))) }
    }

    private fun SmartTool.opTool(): String =
        when (this) {
            SmartTool.Subject -> SelectionOp.Raster.TOOL_SUBJECT
            SmartTool.Sky -> SelectionOp.Raster.TOOL_SKY
            SmartTool.Object -> SelectionOp.Raster.TOOL_OBJECT
            SmartTool.Depth -> "depth"
        }

    companion object {
        /** The longest edge the models see (the grid is <= 2048; the masks are upsampled against it). */
        const val MODEL_EDGE = 1024
        const val HALF = 0.5f
        const val SPACING = 0.004f
        const val BRUSH_HARDNESS = 0.6f
        const val MAX_PROMPT_POINTS = 32
        const val WIRE_REACH = 0.1f
        const val COMMIT_FRACTION = 0.6f
        const val NANOS_PER_S = 1e9
        const val MS_PER_S = 1000.0
    }
}

/** The engine's image as a model's input. */
internal fun SelectionImage.toModelImage(): ModelImage = ModelImage(width, height, rgba)

/** The sheet's message for its state, or null while it only asks. */
internal fun ModelSheet.statusText(): String? {
    val size = info.sizeBytes?.let { sizeText(it) } ?: "size shown by Google Play services"
    return when (val s = state) {
        is ModelState.Failed -> s.error.message(info.name, size)
        is ModelState.Downloading -> "Downloading… ${(s.fraction * PERCENT).toInt()} %"
        ModelState.Verifying -> "Checking the download…"
        ModelState.Ready -> "Ready"
        ModelState.Missing -> null
    }
}

/** True when the sheet should offer "Download on mobile data". */
internal val ModelSheet.needsWifi: Boolean get() = (state as? ModelState.Failed)?.error == ModelError.NeedsWifi

private const val PERCENT = 100
