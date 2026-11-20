package dev.mark.lineover.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.mark.lineover.core.io.layers.LayerSources
import dev.mark.lineover.core.io.masks.MaskRaster
import dev.mark.lineover.core.io.masks.MaskRasters
import dev.mark.lineover.core.model.AdjustmentKind
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.EditGraphJson
import dev.mark.lineover.core.model.Layer
import dev.mark.lineover.core.model.LayerMask
import dev.mark.lineover.core.model.LayerMath
import dev.mark.lineover.core.model.LayerType
import dev.mark.lineover.core.model.MaskPaint
import dev.mark.lineover.core.model.OpParams
import dev.mark.lineover.core.model.Selection
import dev.mark.lineover.core.model.SelectionMode
import dev.mark.lineover.core.model.SelectionOp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/** The Select tools of Stage 15 (the others of the category are Stage 16). */
internal object SelectTools {
    const val MARQUEE = "select.marquee"
    const val LASSO = "select.lasso"
    const val POLYGON = "select.polygonal_lasso"
    const val WAND = "select.magic_wand"
    const val COLOR_RANGE = "select.color_range"
    val ALL = setOf(MARQUEE, LASSO, POLYGON, WAND, COLOR_RANGE)
}

enum class MarqueeShape { Rectangle, Ellipse }

/** Fixed marquee ratios (width : height); null = free. */
internal val MARQUEE_RATIOS: List<Pair<String, Float?>> =
    listOf(
        "Free" to null,
        "1:1" to 1f,
        "4:3" to 4f / 3f,
        "3:2" to 1.5f,
        "16:9" to 16f / 9f,
    )

/**
 * The Select tools' settings (Stage 15): the options bar's mode and anti-alias, each tool's
 * own options, and the amount the selection operations use.
 */
data class SelectionSettings(
    val mode: SelectionMode = SelectionMode.New,
    val antiAlias: Boolean = true,
    val marquee: MarqueeShape = MarqueeShape.Rectangle,
    val ratio: Float? = null,
    val tolerance: Float = DEFAULT_TOLERANCE,
    val contiguous: Boolean = true,
    val sampleAllLayers: Boolean = true,
    val fuzziness: Float = DEFAULT_FUZZINESS,
    val pickSubtract: Boolean = false,
    /** The loupe above the finger while lassoing (Mark's question: on by default). */
    val loupe: Boolean = true,
    /** Pixels for grow / shrink / feather / smooth / border. */
    val amount: Float = DEFAULT_AMOUNT,
    /** Taps add points to the last polygon. */
    val polygonOpen: Boolean = false,
) {
    companion object {
        const val DEFAULT_TOLERANCE = 15f
        const val DEFAULT_FUZZINESS = 25f
        const val DEFAULT_AMOUNT = 10f
        const val MAX_TOLERANCE = 100f
        const val MAX_AMOUNT = 250f
    }
}

/** What the Select tools, the options bar and the canvas do (Stage 15). */
sealed interface SelectAction {
    data class SetSettings(
        val settings: SelectionSettings,
    ) : SelectAction

    /** The wand tolerance or color range fuzziness: also re-runs the last wand / color range (live preview). */
    data class SetTolerance(
        val value: Float,
    ) : SelectAction

    data class SetFuzziness(
        val value: Float,
    ) : SelectAction

    data object Deselect : SelectAction

    data object SelectAll : SelectAction

    data object Invert : SelectAction

    data class Apply(
        val op: SelectionOp,
    ) : SelectAction

    data object ClosePolygon : SelectAction

    data object ToMask : SelectAction

    data object ToNewLayer : SelectAction

    /** Undo of a tap-to-deselect. */
    class Restore(
        val selection: Selection?,
    ) : SelectAction

    /** One finger on the canvas: image points 0..1 (and the view point, for the loupe). */
    data class Start(
        val u: Float,
        val v: Float,
    ) : SelectAction

    data class Move(
        val u: Float,
        val v: Float,
    ) : SelectAction

    data class Loupe(
        val viewX: Float,
        val viewY: Float,
        val u: Float,
        val v: Float,
    ) : SelectAction

    data object End : SelectAction
}

/** The open Select tool of Stage 15, or null. */
internal val EditorState.selectTool: String? get() = selectedToolId?.takeIf { it in SelectTools.ALL }

/** The selection the canvas shows (the previewed History state's, or the edit's). */
internal val EditorState.selection: Selection? get() = shownGraph.selection

/**
 * The Stage 15 intents of [EditorViewModel]. The selection is part of the graph (a History
 * step each); the engine evaluates it on the render dispatcher, in order, the latest winning.
 * A mask made from it saves its pixels (MaskRasters), registered with the engine before a graph
 * names them.
 */
@Suppress("TooManyFunctions") // one function per selection action, like the ViewModel it serves
internal class EditorSelectionActions(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val host: Host,
    private val rasters: MaskRasters?,
    private val sources: LayerSources?,
) {
    /** What the actions need of [EditorViewModel]. */
    interface Host {
        val state: EditorState

        fun reduce(
            label: String?,
            block: EditorState.() -> EditorState,
        )

        fun setState(block: EditorState.() -> EditorState)

        fun message(text: String)

        fun undoable(
            text: String,
            undo: EditorIntent,
        )

        fun endEdit()

        fun resubmit()
    }

    private fun state() = host.state

    private val settings get() = state().selectionSettings

    // The gesture in progress (one finger, from Start to End).
    private var startU = 0f
    private var startV = 0f
    private var lastU = 0f
    private var lastV = 0f
    private var moved = false

    @Suppress("CyclomaticComplexMethod") // a table: one branch per action
    fun onAction(action: SelectAction) {
        when (action) {
            is SelectAction.SetSettings -> host.setState { copy(selectionSettings = action.settings) }
            is SelectAction.SetTolerance -> setTolerance(action.value)
            is SelectAction.SetFuzziness -> setFuzziness(action.value)
            is SelectAction.Deselect -> deselect(undoable = false)
            is SelectAction.SelectAll -> setSelection("Select all", Selection(listOf(SelectionOp.All)))
            is SelectAction.Invert -> apply(SelectionOp.Invert)
            is SelectAction.Apply -> apply(action.op)
            is SelectAction.ClosePolygon -> closePolygon()
            is SelectAction.ToMask -> toMask()
            is SelectAction.ToNewLayer -> toNewLayer()
            is SelectAction.Restore -> setSelection(if (action.selection == null) "Deselect" else "Restore selection", action.selection)
            is SelectAction.Start -> start(action.u, action.v)
            is SelectAction.Move -> move(action.u, action.v)
            is SelectAction.Loupe -> loupe(action)
            is SelectAction.End -> end()
        }
    }

    private fun setSelection(
        label: String?,
        selection: Selection?,
    ) {
        reduce(label) { copy(graph = graph.copy(selection = selection)) }
    }

    private fun reduce(
        label: String?,
        block: EditorState.() -> EditorState,
    ) = host.reduce(label, block)

    private fun apply(op: SelectionOp) {
        val current = state().graph.selection ?: return host.message("Select something first")
        setSelection(SelectionOp.label(op), current.with(op))
    }

    private fun deselect(undoable: Boolean) {
        val before = state().graph.selection ?: return
        setSelection("Deselect", null)
        host.setState { copy(selectionSettings = selectionSettings.copy(polygonOpen = false)) }
        if (undoable) host.undoable("Deselected", EditorIntent.Select(SelectAction.Restore(before)))
    }

    private fun setTolerance(value: Float) {
        val tolerance = value.coerceIn(0f, SelectionSettings.MAX_TOLERANCE)
        host.setState { copy(selectionSettings = selectionSettings.copy(tolerance = tolerance)) }
        val selection = state().graph.selection ?: return
        val last = selection.ops.lastOrNull() as? SelectionOp.Wand ?: return
        reduce(null) { copy(graph = graph.copy(selection = selection.withLast(last.copy(tolerance = tolerance)))) }
    }

    private fun setFuzziness(value: Float) {
        val fuzziness = value.coerceIn(1f, SelectionSettings.MAX_TOLERANCE)
        host.setState { copy(selectionSettings = selectionSettings.copy(fuzziness = fuzziness)) }
        val selection = state().graph.selection ?: return
        val last = selection.ops.lastOrNull() as? SelectionOp.ColorRange ?: return
        reduce(null) { copy(graph = graph.copy(selection = selection.withLast(last.copy(fuzziness = fuzziness)))) }
    }

    /** The document point of image point (u, v), or null before the photo is open. */
    private fun doc(
        u: Float,
        v: Float,
    ): Pair<Float, Float>? {
        val (w, h) = state().canvasSize ?: return null
        return u.coerceIn(0f, 1f) * w to v.coerceIn(0f, 1f) * h
    }

    private fun longEdge(): Float = state().canvasSize?.let { (w, h) -> max(w, h).toFloat() } ?: 1f

    // --- Gestures ---

    private fun start(
        u: Float,
        v: Float,
    ) {
        startU = u
        startV = v
        lastU = u
        lastV = v
        moved = false
    }

    private fun move(
        u: Float,
        v: Float,
    ) {
        val tool = state().selectTool
        val point = doc(u, v)
        if (tool == null || point == null) return
        lastU = u
        lastV = v
        if (!moved) {
            moved = true
            beginDrag(tool)
        }
        val selection = state().graph.selection
        val next = selection?.ops?.lastOrNull()?.let { grown(it, point.first, point.second) } ?: return
        reduce(null) { copy(graph = graph.copy(selection = selection.withLast(next))) }
    }

    /** The shape being dragged, reaching (x, y); null when nothing changes. */
    private fun grown(
        last: SelectionOp,
        x: Float,
        y: Float,
    ): SelectionOp? =
        when (last) {
            is SelectionOp.Rect -> last.copy(x1 = constrainX(last.x0, last.y0, x, y), y1 = constrainY(last.x0, last.y0, x, y))
            is SelectionOp.Ellipse -> last.copy(x1 = constrainX(last.x0, last.y0, x, y), y1 = constrainY(last.x0, last.y0, x, y))
            is SelectionOp.Lasso -> lassoPoint(last, x, y)
            else -> null
        }

    /** A drag with the marquee or the lasso starts a shape at the finger's first point. */
    private fun beginDrag(tool: String) {
        val (x, y) = doc(startU, startV) ?: return
        val s = settings
        val op =
            when (tool) {
                SelectTools.MARQUEE -> {
                    if (s.marquee == MarqueeShape.Ellipse) {
                        SelectionOp.Ellipse(s.mode, s.antiAlias, x, y, x, y)
                    } else {
                        SelectionOp.Rect(s.mode, s.antiAlias, x, y, x, y)
                    }
                }

                SelectTools.LASSO -> {
                    SelectionOp.Lasso(s.mode, s.antiAlias, listOf(x, y))
                }

                else -> {
                    return
                }
            }
        host.endEdit()
        addShape(null, op)
    }

    private fun addShape(
        label: String?,
        op: SelectionOp.Shape,
    ) {
        val current = state().graph.selection
        // Subtract / intersect with nothing selected: start from the whole photo or nothing.
        val base = current ?: Selection()
        reduce(label) { copy(graph = graph.copy(selection = base.with(op))) }
    }

    private fun constrainX(
        x0: Float,
        y0: Float,
        x: Float,
        y: Float,
    ): Float {
        val ratio = settings.ratio ?: return x
        return x0 + ratioBox(x - x0, y - y0, ratio).first
    }

    private fun constrainY(
        x0: Float,
        y0: Float,
        x: Float,
        y: Float,
    ): Float {
        val ratio = settings.ratio ?: return y
        return y0 + ratioBox(x - x0, y - y0, ratio).second
    }

    /** The largest box of [ratio] inside the drag (dx, dy), keeping the drag's direction. */
    private fun ratioBox(
        dx: Float,
        dy: Float,
        ratio: Float,
    ): Pair<Float, Float> {
        val w = min(abs(dx), abs(dy) * ratio)
        return w * sign(dx).let { if (it == 0f) 1f else it } to w / ratio * sign(dy).let { if (it == 0f) 1f else it }
    }

    private fun lassoPoint(
        last: SelectionOp.Lasso,
        x: Float,
        y: Float,
    ): SelectionOp.Lasso? {
        val n = last.points.size
        val spacing = longEdge() * LASSO_SPACING
        return if (n >= 2 &&
            hypot(x - last.points[n - 2], y - last.points[n - 1]) < spacing
        ) {
            null
        } else {
            last.copy(points = last.points + x + y)
        }
    }

    private fun loupe(action: SelectAction.Loupe) {
        if (state().selectTool != SelectTools.LASSO || !settings.loupe) return
        val canvas = state().canvas ?: return
        host.setState { copy(loupe = Loupe(action.viewX, action.viewY, loupe?.pixels)) }
        scope.launch {
            val pixels = withContext(dispatcher) { canvas.loupeAt(action.u, action.v) }
            host.setState { if (loupe == null) this else copy(loupe = Loupe(action.viewX, action.viewY, pixels)) }
        }
    }

    private fun end() {
        host.setState { copy(loupe = null) }
        val tool = state().selectTool ?: return
        if (moved) {
            host.endEdit()
            if (tool != SelectTools.POLYGON) return
        }
        tap(tool)
    }

    /** A tap (or the end of a polygon drag) at the gesture's point. */
    private fun tap(tool: String) {
        val (x, y) = doc(lastU, lastV) ?: return
        val s = settings
        when (tool) {
            SelectTools.MARQUEE, SelectTools.LASSO -> {
                deselectOutside(startU, startV)
            }

            SelectTools.POLYGON -> {
                polygonPoint(x, y)
            }

            SelectTools.WAND -> {
                host.endEdit()
                addShape("Magic wand", SelectionOp.Wand(s.mode, x, y, s.tolerance, s.contiguous, s.sampleAllLayers))
            }

            SelectTools.COLOR_RANGE -> {
                colorPick(x, y)
            }
        }
    }

    /** Tapping outside the selection deselects (with Undo). */
    private fun deselectOutside(
        u: Float,
        v: Float,
    ) {
        if (state().graph.selection == null) return
        val canvas = state().canvas ?: return deselect(undoable = true)
        scope.launch {
            val value = withContext(dispatcher) { canvas.selectionAt(u, v) }
            if (value == null || value < INSIDE) deselect(undoable = true)
        }
    }

    private fun polygonPoint(
        x: Float,
        y: Float,
    ) {
        val s = settings
        val selection = state().graph.selection
        val last = selection?.ops?.lastOrNull() as? SelectionOp.Polygon
        if (!s.polygonOpen || last == null) {
            host.endEdit()
            addShape("Polygonal lasso", SelectionOp.Polygon(s.mode, s.antiAlias, listOf(x, y)))
            host.setState { copy(selectionSettings = selectionSettings.copy(polygonOpen = true)) }
            return
        }
        val n = last.points.size
        // A tap near the first point closes the shape.
        if (n >= MIN_POLYGON && hypot(x - last.points[0], y - last.points[1]) < longEdge() * CLOSE_DISTANCE) return closePolygon()
        reduce(null) { copy(graph = graph.copy(selection = selection.withLast(last.copy(points = last.points + x + y)))) }
    }

    private fun closePolygon() {
        host.endEdit()
        host.setState { copy(selectionSettings = selectionSettings.copy(polygonOpen = false)) }
    }

    private fun colorPick(
        x: Float,
        y: Float,
    ) {
        val s = settings
        val selection = state().graph.selection
        val last = selection?.ops?.lastOrNull() as? SelectionOp.ColorRange
        if (last == null) {
            host.endEdit()
            val add = if (s.pickSubtract) emptyList() else listOf(x, y)
            val subtract = if (s.pickSubtract) listOf(x, y) else emptyList()
            return addShape("Color range", SelectionOp.ColorRange(s.mode, s.fuzziness, add, subtract))
        }
        val next = if (s.pickSubtract) last.copy(subtract = last.subtract + x + y) else last.copy(add = last.add + x + y)
        host.endEdit()
        reduce("Color range") { copy(graph = graph.copy(selection = selection.withLast(next))) }
    }

    // --- Selection -> mask / new layer ---

    /** Saves the selection's pixels and registers them; the mask paint that names them. */
    private suspend fun saveSelection(): MaskPaint.Raster? {
        val store = rasters ?: return null
        val canvas = state().canvas ?: return null
        return withContext(dispatcher) {
            val raster = canvas.selectionRaster() ?: return@withContext null
            val pixels = MaskRaster(raster.width, raster.height, raster.values)
            val name = store.save(pixels) ?: return@withContext null
            canvas.registerMaskRaster(name, pixels)
            synchronized(registered) { registered += name }
            synchronized(thumbnails) { thumbnails[name] = maskThumbnail(pixels) }
            MaskPaint.Raster(name, raster.width.toFloat(), raster.height.toFloat(), raster.docWidth.toFloat(), raster.docHeight.toFloat())
        }
    }

    private fun toMask() {
        if (state().graph.selection == null) return host.message("Select something first")
        scope.launch {
            val paint = saveSelection() ?: return@launch host.message("The selection could not be saved")
            publishThumbnails()
            val mask = LayerMask(base = LayerMask.BLACK, paint = listOf(paint))
            val target = state().selectedLayer?.takeIf { !it.isBackground && !it.locked }
            if (target != null) {
                reduce("${target.name.ifBlank { target.type.label }}: Mask from selection") {
                    copy(graph = LayerMath.update(graph, target.id) { it.copy(mask = mask) }.copy(selection = null), maskTarget = true)
                }
            } else {
                // With the photo selected: an Exposure layer shows the selection at work.
                val kind = AdjustmentKind.Exposure
                val layer =
                    Layer(
                        LayerMath.newId(),
                        ops = listOf(kind.op(kind.default)),
                        type = LayerType.Adjustment,
                        name = "Selection",
                        mask = mask,
                    )
                reduce("Mask from selection") {
                    copy(graph = LayerMath.add(graph, layer).copy(selection = null), selectedLayerId = layer.id, maskTarget = false)
                }
                host.message("Added an Exposure layer masked by the selection")
            }
        }
    }

    private fun toNewLayer() {
        val s = state()
        if (s.graph.selection == null) return host.message("Select something first")
        val selected = s.selectedLayer?.takeIf { it.type == LayerType.Raster || it.type == LayerType.SmartObject }
        scope.launch {
            val layer = selected?.let { copyOf(it) } ?: photoCopy() ?: return@launch
            val paint = saveSelection() ?: return@launch host.message("The selection could not be saved")
            publishThumbnails()
            val masked = layer.copy(mask = LayerMask(base = LayerMask.BLACK, paint = listOf(paint)))
            reduce("New layer from selection") {
                copy(graph = LayerMath.add(graph, masked, aboveId = selectedLayerId).copy(selection = null), selectedLayerId = masked.id)
            }
        }
    }

    private fun copyOf(layer: Layer): Layer =
        layer.copy(id = LayerMath.newId(), name = "${layer.name.ifBlank { layer.type.label }} (selection)", mask = null, clip = false)

    /** The photo as a smart object with its own colour edit, placed 1:1 (an uncropped photo only). */
    private suspend fun photoCopy(): Layer? {
        val s = state()
        val store = sources
        val uri = s.project?.sourceUri
        val size = s.canvasSize
        if (s.backgroundOps.any { it is OpParams.Geometry }) {
            host.message("Copying the photo to a layer works without crop or optics for now")
            return null
        }
        if (store == null || uri == null || size == null) return null.also { host.message("Layers cannot copy the photo here") }
        val source = withContext(dispatcher) { importPhoto(store, uri) }
        if (source == null) host.message("The photo could not be copied")
        return source?.let { photoLayer(it, size) }
    }

    private fun importPhoto(
        store: LayerSources,
        uri: String,
    ): String? {
        val src = store.import(uri) ?: return null
        store.file(src.name)?.let { state().canvas?.registerLayerImage(src.name, it) }
        return src.name
    }

    private fun photoLayer(
        source: String,
        size: Pair<Int, Int>,
    ) = Layer(
        LayerMath.newId(),
        ops = state().backgroundOps.filter(AdjustmentKind::isPointOp),
        type = LayerType.SmartObject,
        name = "Photo (selection)",
        source = source,
        sourceSize = listOf(size.first.toFloat(), size.second.toFloat()),
        transform = listOf(1f, 0f, 0f, 1f, 0f, 0f),
    )

    // --- Mask rasters the graph names ---

    private val registered = HashSet<String>()
    private val thumbnails = HashMap<String, ImageBitmap>()

    private fun publishThumbnails() {
        val snapshot = synchronized(thumbnails) { HashMap(thumbnails) }
        host.setState { copy(maskThumbnails = snapshot) }
    }

    /** A graph is about to reach the engine: register the saved selections it names that are new. */
    fun onCanvasGraph(graph: EditGraph) {
        val store = rasters ?: return
        val canvas = state().canvas ?: return
        val missing = rasterNames(graph).filter { synchronized(registered) { it !in registered } }
        if (missing.isEmpty()) return
        synchronized(registered) { registered += missing }
        scope.launch {
            withContext(dispatcher) {
                for (name in missing) {
                    val raster = store.load(name) ?: continue
                    canvas.registerMaskRaster(name, raster)
                    synchronized(thumbnails) { thumbnails[name] = maskThumbnail(raster) }
                }
            }
            publishThumbnails()
            host.resubmit() // the graph already reached the engine without these pixels
        }
    }

    private val selectionJson = MutableStateFlow<String?>(null)

    init {
        // The engine evaluates selections in order, one at a time: the latest one wins.
        scope.launch {
            selectionJson.collect { json ->
                val canvas = state().canvas
                if (json != null && canvas != null) withContext(dispatcher) { canvas.setSelection(json) }
            }
        }
    }

    /** After a state change: the engine evaluates a changed selection (or clears it). */
    fun afterSync(
        before: EditorState?,
        after: EditorState,
    ) {
        if (after.canvas == null) return
        if (before != null && before.canvas != null && before.selection == after.selection) return
        selectionJson.value = EditGraphJson.encodeSelection(after.selection)
    }

    companion object {
        const val INSIDE = 0.5f
        const val LASSO_SPACING = 0.002f
        const val CLOSE_DISTANCE = 0.02f
        const val MIN_POLYGON = 6
    }
}
