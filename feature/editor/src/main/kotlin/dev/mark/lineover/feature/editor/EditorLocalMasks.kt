package dev.mark.lineover.feature.editor

import android.graphics.BitmapFactory
import dev.mark.lineover.core.io.depth.EmbeddedDepth
import dev.mark.lineover.core.io.masks.MaskRaster
import dev.mark.lineover.core.io.masks.MaskRasters
import dev.mark.lineover.core.ml.ModelMask
import dev.mark.lineover.core.ml.ModelState
import dev.mark.lineover.core.ml.SmartModels
import dev.mark.lineover.core.ml.SmartTool
import dev.mark.lineover.core.model.AiMaskKind
import dev.mark.lineover.core.model.CombineMode
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.Layer
import dev.mark.lineover.core.model.LayerMask
import dev.mark.lineover.core.model.LayerMath
import dev.mark.lineover.core.model.LayerType
import dev.mark.lineover.core.model.LocalAdjustments
import dev.mark.lineover.core.model.LocalParam
import dev.mark.lineover.core.model.MaskComponent
import dev.mark.lineover.core.model.MaskPaint
import dev.mark.lineover.core.model.OpParams
import dev.mark.lineover.core.model.ToolCategory
import dev.mark.lineover.core.model.componentLabel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The Masks category's tools (Stage 17), slots cat/Masks/01-10. */
internal object MaskTools {
    const val LINEAR = "masks.linear_gradient_mask"
    const val RADIAL = "masks.radial_gradient_mask"
    const val BRUSH = "masks.brush_mask"
    const val COLOR = "masks.color_range_mask"
    const val LUMINANCE = "masks.luminance_range_mask"
    const val DEPTH = "masks.depth_range_mask"
    const val SUBJECT = "masks.subject_mask"
    const val SKY = "masks.sky_mask"
    const val BACKGROUND = "masks.background_mask"
    const val PEOPLE = "masks.people_mask"
    val ALL = listOf(LINEAR, RADIAL, BRUSH, COLOR, LUMINANCE, DEPTH, SUBJECT, SKY, BACKGROUND, PEOPLE)

    /** The tools whose component comes from a model (or the photo's depth map). */
    val MODELS = setOf(DEPTH, SUBJECT, SKY, BACKGROUND, PEOPLE)

    /** The tool that makes [c] (its panel shows that component's settings). */
    fun of(c: MaskComponent): String =
        when (c) {
            is MaskComponent.Linear -> {
                LINEAR
            }

            is MaskComponent.Radial -> {
                RADIAL
            }

            is MaskComponent.Brush -> {
                BRUSH
            }

            is MaskComponent.ColorRange -> {
                COLOR
            }

            is MaskComponent.LuminanceRange -> {
                LUMINANCE
            }

            is MaskComponent.DepthRange -> {
                DEPTH
            }

            is MaskComponent.Raster -> {
                when (c.tool) {
                    AiMaskKind.Subject -> SUBJECT
                    AiMaskKind.Sky -> SKY
                    AiMaskKind.Background -> BACKGROUND
                    AiMaskKind.People -> PEOPLE
                }
            }
        }

    /** The model a tool needs (Background and People share Subject's and Sky's). */
    fun model(tool: String): SmartTool? =
        when (tool) {
            SUBJECT, BACKGROUND -> SmartTool.Subject
            SKY, PEOPLE -> SmartTool.Sky
            DEPTH -> SmartTool.Depth
            else -> null
        }
}

/** The local brush (Stage 17): size in document px (0 = 4 % of the long edge), feather and flow in %. */
data class LocalBrush(
    val size: Float = 0f,
    val feather: Float = DEFAULT_FEATHER,
    val flow: Float = LayerMask.FULL,
    val autoMask: Boolean = false,
    val erase: Boolean = false,
) {
    fun radiusFor(longEdge: Int): Float = if (size > 0f) size else (longEdge * MaskBrush.AUTO_RADIUS).coerceAtLeast(1f)

    companion object {
        const val DEFAULT_FEATHER = 50f
        const val MAX_SIZE = 2000f
    }
}

/** The Masks category's state: which mask and component are selected, the overlay, the brush. */
data class LocalMaskState(
    val selectedId: String? = null,
    val component: Int = 0,
    /** The red overlay shows where the selected mask applies. */
    val overlay: Boolean = false,
    /** Set by Add / Subtract / Intersect: the next tool joins the selected mask in this mode. */
    val joinMode: CombineMode? = null,
    val brush: LocalBrush = LocalBrush(),
    /** A model is making this tool's component (the canvas shimmers). */
    val busy: String? = null,
)

/** What the Masks category does (Stage 17). */
sealed interface MaskAction {
    /** A tool from the strip or the Add menu: a new mask, or a component joining the selected one. */
    data class Add(
        val tool: String,
    ) : MaskAction

    /** Select a mask (its pin or row); null deselects. */
    data class Select(
        val id: String?,
    ) : MaskAction

    data class SelectComponent(
        val index: Int,
    ) : MaskAction

    /** Add / Subtract / Intersect: the next tool joins the selected mask so (null cancels). */
    data class Join(
        val mode: CombineMode?,
    ) : MaskAction

    data class SetComponentMode(
        val index: Int,
        val mode: CombineMode,
    ) : MaskAction

    data class InvertComponent(
        val index: Int,
    ) : MaskAction

    data class DeleteComponent(
        val index: Int,
    ) : MaskAction

    /** A component's own settings changed (feather, fuzziness, ranges, auto-mask). */
    data class EditComponent(
        val index: Int,
        val component: MaskComponent,
    ) : MaskAction

    data class SetParam(
        val param: LocalParam,
        val value: Float,
    ) : MaskAction

    /** The amount slider: the whole mask's strength (the layer's opacity). */
    data class SetAmount(
        val value: Float,
    ) : MaskAction

    data object DuplicateAndInvert : MaskAction

    data object ToggleOverlay : MaskAction

    data object DeleteMask : MaskAction

    data class SetBrush(
        val brush: LocalBrush,
    ) : MaskAction

    data class Start(
        val u: Float,
        val v: Float,
    ) : MaskAction

    data class Move(
        val u: Float,
        val v: Float,
    ) : MaskAction

    data object End : MaskAction
}

/** The local masks: adjustment layers whose mask is a group of components, bottom to top. */
internal val EditorState.localMasks: List<Layer>
    get() = graph.layers.filter { it.type == LayerType.Adjustment && it.mask?.isLocal == true }

internal val EditorState.selectedLocalMask: Layer?
    get() = localMask.selectedId?.let { id -> localMasks.firstOrNull { it.id == id } }

/** The open Masks tool, if any. */
internal val EditorState.maskTool: String? get() = selectedToolId?.takeIf { it in MaskTools.ALL }

/** One finger works on the local masks: picks pins, and draws the selected component's shape. */
internal val EditorState.localPainting: Boolean
    get() = category == ToolCategory.Masks && (localMasks.isNotEmpty() || maskTool != null) && canvasSize != null

/** The overlay the engine draws for the selected local mask. */
internal val EditorState.localMaskView: Pair<String, MaskView>?
    get() =
        selectedLocalMask?.takeIf { category == ToolCategory.Masks && localMask.overlay }?.let { it.id to MaskView.Overlay }

/** A pin on the canvas: a mask's handle, at image point (u, v) 0..1. */
data class MaskPin(
    val id: String,
    val u: Float,
    val v: Float,
    val selected: Boolean,
)

/**
 * The pins of the local masks: at the first component with a place (a gradient's centre, a
 * brush's first point); masks made only of ranges and models stack near the top-left corner.
 */
internal val EditorState.maskPins: List<MaskPin>
    get() {
        val (w, h) = canvasSize ?: return emptyList()
        var stacked = 0
        return localMasks.map { layer ->
            val place = layer.mask?.components?.firstNotNullOfOrNull { placeOf(it) }
            val (x, y) = place ?: (PIN_STACK_X * w to (PIN_STACK_Y + PIN_STACK_STEP * stacked++) * h)
            MaskPin(layer.id, (x / w).coerceIn(0f, 1f), (y / h).coerceIn(0f, 1f), layer.id == localMask.selectedId)
        }
    }

private fun placeOf(c: MaskComponent): Pair<Float, Float>? =
    when (c) {
        is MaskComponent.Linear -> {
            (c.x0 + c.x1) / 2f to (c.y0 + c.y1) / 2f
        }

        is MaskComponent.Radial -> {
            c.cx to c.cy
        }

        is MaskComponent.Brush -> {
            c.strokes
                .firstOrNull()
                ?.points
                ?.takeIf { it.size >= 2 }
                ?.let { it[0] to it[1] }
        }

        else -> {
            null
        }
    }

private const val PIN_STACK_X = 0.08f
private const val PIN_STACK_Y = 0.08f
private const val PIN_STACK_STEP = 0.07f

/**
 * The Stage 17 intents of [EditorViewModel]. A local mask is an adjustment layer (Stage 13) whose
 * mask is a group of components (MaskComponent), so the engine runs the Stage 5-10 ops through
 * it. Models run off the main thread; their masks are upsampled against the photo by the engine,
 * saved as pixels with their colour guide (MaskRasters) and registered before a graph names them.
 */
@Suppress("TooManyFunctions") // one function per action and gesture
internal class EditorLocalMaskActions(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val host: Host,
    private val models: SmartModels?,
    private val rasters: MaskRasters?,
    /** The photo's bytes (for a depth map stored in it), off the main thread; null when unknown. */
    private val photoBytes: suspend () -> ByteArray?,
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

        fun endEdit()

        /** Remembers a mask raster the engine now has (so it is not registered again). */
        fun registered(name: String)

        /** Shows [tool]'s download sheet; [then] runs once it is ready. */
        fun needModel(
            tool: SmartTool,
            then: () -> Unit,
        )
    }

    private val state get() = host.state
    private val local get() = state.localMask

    // The gesture in progress: what the finger moves.
    private var drag: Drag? = null

    private enum class Drag { Create, MoveCentre, Resize, Paint, Pick, None }

    private var dragStart: Pair<Float, Float>? = null

    @Suppress("CyclomaticComplexMethod") // a table: one branch per action
    fun onAction(action: MaskAction) {
        when (action) {
            is MaskAction.Add -> add(action.tool)
            is MaskAction.Select -> select(action.id)
            is MaskAction.SelectComponent -> selectComponent(action.index)
            is MaskAction.Join -> host.setState { copy(localMask = localMask.copy(joinMode = action.mode)) }
            is MaskAction.SetComponentMode -> editComponent(action.index) { it.with(mode = action.mode) }
            is MaskAction.InvertComponent -> editComponent(action.index) { it.with(invert = !it.invert) }
            is MaskAction.DeleteComponent -> deleteComponent(action.index)
            is MaskAction.EditComponent -> editComponent(action.index) { action.component }
            is MaskAction.SetParam -> setParam(action.param, action.value)
            is MaskAction.SetAmount -> change(null) { it.copy(opacity = action.value.coerceIn(0f, Layer.FULL)) }
            is MaskAction.DuplicateAndInvert -> duplicateAndInvert()
            is MaskAction.ToggleOverlay -> host.setState { copy(localMask = localMask.copy(overlay = !localMask.overlay)) }
            is MaskAction.DeleteMask -> deleteMask()
            is MaskAction.SetBrush -> host.setState { copy(localMask = localMask.copy(brush = action.brush)) }
            is MaskAction.Start -> start(action.u, action.v)
            is MaskAction.Move -> move(action.u, action.v)
            is MaskAction.End -> end()
        }
    }

    // --- Masks and components ---

    private fun select(id: String?) {
        val layer = id?.let { i -> state.localMasks.firstOrNull { it.id == i } }
        val tool =
            layer
                ?.mask
                ?.components
                ?.firstOrNull()
                ?.let(MaskTools::of)
        host.reduce(null) {
            copy(
                localMask = localMask.copy(selectedId = layer?.id, component = 0, joinMode = null),
                selectedLayerId = layer?.id ?: selectedLayerId,
                panel = tool?.let { EditorPanel.Tool(it) } ?: panel.takeUnless { it is EditorPanel.Tool && it.id in MaskTools.ALL },
            )
        }
    }

    private fun selectComponent(index: Int) {
        val c =
            state.selectedLocalMask
                ?.mask
                ?.components
                ?.getOrNull(index) ?: return
        host.setState { copy(localMask = localMask.copy(component = index), panel = EditorPanel.Tool(MaskTools.of(c))) }
    }

    /** Opens [tool]: a component made now (a gradient, a brush, a range) or once its model has run. */
    @Suppress("ReturnCount") // guard clauses
    fun add(tool: String) {
        if (tool !in MaskTools.ALL) return
        val (w, h) = state.canvasSize ?: return host.message("Wait for the photo to finish loading")
        if (local.busy != null) return
        if (tool in MaskTools.MODELS) return addFromModel(tool)
        val component =
            when (tool) {
                MaskTools.LINEAR -> MaskComponent.Linear(w / 2f, 0f, w / 2f, h / 2f)
                MaskTools.RADIAL -> MaskComponent.Radial(w / 2f, h / 2f, w / RADIAL_FRACTION, h / RADIAL_FRACTION, 0f, DEFAULT_FEATHER)
                MaskTools.BRUSH -> MaskComponent.Brush(emptyList(), autoMask = local.brush.autoMask)
                MaskTools.COLOR -> MaskComponent.ColorRange(emptyList(), DEFAULT_FUZZINESS)
                else -> MaskComponent.LuminanceRange(LUMA_LO, 1f, LUMA_SMOOTH)
            }
        commitComponent(tool, component)
        when (tool) {
            MaskTools.BRUSH -> host.message("Paint the area to adjust")
            MaskTools.COLOR -> host.message("Tap the colours to adjust")
            MaskTools.LINEAR, MaskTools.RADIAL -> host.message("Drag on the photo to place it")
        }
    }

    /** Adds [component] to the selected mask in the join mode, or makes a new mask of it. */
    private fun commitComponent(
        tool: String,
        component: MaskComponent,
    ) {
        host.endEdit()
        val target = state.selectedLocalMask
        val mode = local.joinMode
        if (target != null && mode != null) {
            val mask = requireNotNull(target.mask)
            val joined = component.with(mode = mode)
            host.reduce(null) {
                copy(
                    graph = LayerMath.update(graph, target.id) { it.copy(mask = mask.copy(components = mask.components + joined)) },
                    localMask = localMask.copy(component = mask.components.size, joinMode = null),
                    panel = EditorPanel.Tool(tool),
                )
            }
        } else {
            val n = state.localMasks.size + 1
            val layer =
                Layer(
                    LayerMath.newId(),
                    type = LayerType.Adjustment,
                    name = "Mask $n",
                    mask = LayerMask(components = listOf(component.with(mode = CombineMode.Add))),
                )
            host.reduce("Add ${componentLabel(component)} mask") {
                copy(
                    graph = LayerMath.add(graph, layer),
                    localMask = localMask.copy(selectedId = layer.id, component = 0, joinMode = null),
                    selectedLayerId = layer.id,
                    panel = EditorPanel.Tool(tool),
                )
            }
        }
    }

    @Suppress("ReturnCount") // guard clauses
    private fun editComponent(
        index: Int,
        f: (MaskComponent) -> MaskComponent,
    ) {
        val layer = state.selectedLocalMask ?: return
        val mask = layer.mask ?: return
        val c = mask.components.getOrNull(index) ?: return
        val next = f(c)
        if (next == c) return
        change(null) { it.copy(mask = mask.copy(components = mask.components.toMutableList().also { l -> l[index] = next })) }
    }

    @Suppress("ReturnCount") // guard clauses
    private fun deleteComponent(index: Int) {
        val layer = state.selectedLocalMask ?: return
        val mask = layer.mask ?: return
        if (index !in mask.components.indices) return
        if (mask.components.size == 1) return deleteMask()
        host.endEdit()
        val rest = mask.components.filterIndexed { i, _ -> i != index }
        // The first component always adds (it starts from black).
        val fixed = rest.mapIndexed { i, c -> if (i == 0 && c.mode != CombineMode.Add) c.with(mode = CombineMode.Add) else c }
        host.reduce(null) {
            copy(
                graph = LayerMath.update(graph, layer.id) { it.copy(mask = mask.copy(components = fixed)) },
                localMask = localMask.copy(component = 0),
            )
        }
    }

    private fun setParam(
        param: LocalParam,
        value: Float,
    ) {
        val layer = state.selectedLocalMask ?: return
        val adjustments = LocalAdjustments.fromOps(layer.ops).with(param, value)
        change(null) { it.copy(ops = adjustments.toOps()) }
    }

    private fun duplicateAndInvert() {
        val layer = state.selectedLocalMask ?: return
        val mask = layer.mask ?: return
        host.endEdit()
        val copy = layer.copy(id = LayerMath.newId(), name = "${layer.name} inverted", mask = mask.copy(inverted = !mask.inverted))
        host.reduce("Duplicate and invert ${layer.name}") {
            copy(
                graph = LayerMath.add(graph, copy, aboveId = layer.id),
                localMask = localMask.copy(selectedId = copy.id, component = 0),
                selectedLayerId = copy.id,
            )
        }
    }

    private fun deleteMask() {
        val layer = state.selectedLocalMask ?: return
        host.endEdit()
        host.reduce("Delete ${layer.name}") {
            copy(
                graph = LayerMath.remove(graph, layer.id),
                localMask = localMask.copy(selectedId = null, component = 0, overlay = false),
                selectedLayerId = selectedLayerId.takeIf { it != layer.id },
                panel = panel.takeUnless { it is EditorPanel.Tool && it.id in MaskTools.ALL },
            )
        }
    }

    /** [f] on the selected mask's layer (one History step per drag: EndEdit closes it). */
    private fun change(
        label: String?,
        f: (Layer) -> Layer,
    ) {
        val layer = state.selectedLocalMask ?: return
        host.reduce(label) { copy(graph = LayerMath.update(graph, layer.id, f)) }
    }

    // --- Models ---

    private fun addFromModel(tool: String) {
        val model = requireNotNull(MaskTools.model(tool))
        // The panel opens at once and says what is happening; the component arrives with the model.
        host.setState { copy(panel = EditorPanel.Tool(tool), localMask = localMask.copy(component = 0)) }
        scope.launch {
            val depth = if (tool == MaskTools.DEPTH) embeddedDepth() else null
            if (depth != null) return@launch saveModelMask(tool, depth, "the photo's depth map")
            val m = models ?: return@launch host.message("${model.label} isn't available in this build")
            if (m.states.value[model] != ModelState.Ready) {
                host.needModel(model) { addFromModel(tool) }
                return@launch
            }
            runModel(m, tool, model)
        }
    }

    private suspend fun runModel(
        m: SmartModels,
        tool: String,
        model: SmartTool,
    ) {
        val canvas = state.canvas ?: return
        host.setState { copy(localMask = localMask.copy(busy = tool)) }
        try {
            val image = withContext(dispatcher) { canvas.selectionImage(EditorSmartActions.MODEL_EDGE) } ?: return
            val mask = if (tool == MaskTools.PEOPLE) m.people(image.toModelImage()) else m.segment(model, image.toModelImage())
            if (mask == null) {
                host.message("Nothing found for this mask")
                return
            }
            saveModelMask(tool, mask, null)
        } finally {
            host.setState { copy(localMask = localMask.copy(busy = null)) }
        }
    }

    /** Upsamples [mask] against the photo, saves it and its guide, and adds the component. */
    private suspend fun saveModelMask(
        tool: String,
        mask: ModelMask,
        source: String?,
    ) {
        val canvas = state.canvas ?: return
        val store = rasters ?: return host.message("Masks can't be saved here")
        val component =
            withContext(dispatcher) {
                val up = canvas.upsampleMask(mask.values, mask.width, mask.height) ?: return@withContext null
                val values = MaskRaster(up.mask.width, up.mask.height, up.mask.values)
                val planes = MaskRaster(up.guide.width, up.guide.height, up.guide.values)
                val name = store.save(values) ?: return@withContext null
                val guide = store.save(planes)
                canvas.registerMaskRaster(name, values)
                host.registered(name)
                guide?.let {
                    canvas.registerMaskRaster(it, planes)
                    host.registered(it)
                }
                component(tool, name, guide, up.mask)
            }
        if (component == null) return host.message("The mask could not be saved")
        commitComponent(tool, component)
        if (source != null) host.message("Depth from $source")
    }

    private fun component(
        tool: String,
        name: String,
        guide: String?,
        doc: dev.mark.lineover.core.engine.SelectionRaster,
    ): MaskComponent {
        val w = doc.width.toFloat()
        val h = doc.height.toFloat()
        val dw = doc.docWidth.toFloat()
        val dh = doc.docHeight.toFloat()
        return when (tool) {
            MaskTools.DEPTH -> MaskComponent.DepthRange(name, w, h, dw, dh, guide, DEPTH_LO, 1f, DEPTH_SMOOTH)
            MaskTools.SKY -> MaskComponent.Raster(name, w, h, dw, dh, guide, AiMaskKind.Sky)
            MaskTools.PEOPLE -> MaskComponent.Raster(name, w, h, dw, dh, guide, AiMaskKind.People)
            MaskTools.BACKGROUND -> MaskComponent.Raster(name, w, h, dw, dh, guide, AiMaskKind.Background, invert = true)
            else -> MaskComponent.Raster(name, w, h, dw, dh, guide, AiMaskKind.Subject)
        }
    }

    /**
     * The depth map stored in the photo (Dynamic Depth), near = 1, when there is one and the photo
     * is not cropped or rotated (the map covers the uncropped frame); else null and the model runs.
     */
    private suspend fun embeddedDepth(): ModelMask? =
        withContext(dispatcher) {
            val geometry =
                state.graph.layers
                    .firstOrNull()
                    ?.ops
                    ?.any { it is OpParams.Geometry && !it.isIdentity } == true
            if (geometry) return@withContext null
            val bytes = photoBytes() ?: return@withContext null
            val item = EmbeddedDepth.find(bytes) ?: return@withContext null
            val bitmap = BitmapFactory.decodeByteArray(item.bytes, 0, item.bytes.size) ?: return@withContext null
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            bitmap.recycle()
            ModelMask(w, h, FloatArray(w * h) { 1f - (pixels[it] and BYTE) / BYTE.toFloat() })
        }

    // --- Canvas gestures ---

    private fun doc(
        u: Float,
        v: Float,
    ): Pair<Float, Float>? = state.canvasSize?.let { (w, h) -> u * w to v * h }

    @Suppress("ReturnCount") // guard clauses
    private fun start(
        u: Float,
        v: Float,
    ) {
        drag = Drag.None
        val point = doc(u, v) ?: return
        dragStart = point
        val tapped = pinAt(u, v)
        if (tapped != null && tapped != local.selectedId) {
            select(tapped)
            return
        }
        val layer = state.selectedLocalMask ?: return
        if (state.maskTool == null) {
            if (tapped != null) select(tapped)
            return
        }
        val components = layer.mask?.components ?: return
        val c = components.getOrNull(local.component) ?: return
        host.endEdit()
        when (c) {
            is MaskComponent.Linear -> startLinear(c, point)
            is MaskComponent.Radial -> startRadial(c, point)
            is MaskComponent.Brush -> startStroke(c, point)
            is MaskComponent.ColorRange -> pickColor(c, point)
            else -> Unit
        }
    }

    private fun pinAt(
        u: Float,
        v: Float,
    ): String? {
        val (w, h) = state.canvasSize ?: return null
        val reach = PIN_REACH * max(w, h)
        return state.maskPins
            .minByOrNull {
                hypot(
                    (it.u - u) * w,
                    (it.v - v) * h,
                )
            }?.takeIf { hypot((it.u - u) * w, (it.v - v) * h) <= reach }
            ?.id
    }

    private fun startLinear(
        c: MaskComponent.Linear,
        p: Pair<Float, Float>,
    ) {
        drag = Drag.Create
        replace(c.copy(x0 = p.first, y0 = p.second, x1 = p.first, y1 = p.second))
    }

    private fun startRadial(
        c: MaskComponent.Radial,
        p: Pair<Float, Float>,
    ) {
        val near = hypot(p.first - c.cx, p.second - c.cy) <= MOVE_REACH * min(c.rx, c.ry)
        drag = if (near) Drag.MoveCentre else Drag.Resize
    }

    private fun startStroke(
        c: MaskComponent.Brush,
        p: Pair<Float, Float>,
    ) {
        val (w, h) = state.canvasSize ?: return
        val brush = local.brush
        val stroke =
            MaskPaint.Stroke(
                if (brush.erase) 0f else 1f,
                brush.radiusFor(max(w, h)),
                1f - brush.feather / LayerMask.FULL,
                brush.flow,
                listOf(p.first, p.second),
            )
        drag = Drag.Paint
        replace(c.copy(strokes = c.strokes + stroke, autoMask = brush.autoMask))
        if (brush.autoMask && !brush.erase) {
            // The colour under the stroke's first point (the photo under the layers), filled in when known.
            val index = local.component
            val strokeIndex = c.strokes.size
            scope.launch {
                val lab = withContext(dispatcher) { state.canvas?.photoLabAt(p.first, p.second) } ?: return@launch
                val now =
                    state.selectedLocalMask
                        ?.mask
                        ?.components
                        ?.getOrNull(index) as? MaskComponent.Brush ?: return@launch
                val s = now.strokes.getOrNull(strokeIndex) ?: return@launch
                editComponent(index) {
                    now.copy(strokes = now.strokes.toMutableList().also { l -> l[strokeIndex] = s.copy(color = lab.toList()) })
                }
            }
        }
    }

    private fun pickColor(
        c: MaskComponent.ColorRange,
        p: Pair<Float, Float>,
    ) {
        drag = Drag.Pick
        val index = local.component
        scope.launch {
            val lab = withContext(dispatcher) { state.canvas?.photoLabAt(p.first, p.second) } ?: return@launch
            editComponent(index) {
                (it as? MaskComponent.ColorRange)?.copy(colors = (it.colors + lab.toList()).takeLast(MAX_COLORS * LAB))
                    ?: it
            }
            host.endEdit()
        }
        if (c.colors.size >= MAX_COLORS * LAB) host.message("Up to $MAX_COLORS colours: the oldest is replaced")
    }

    private fun move(
        u: Float,
        v: Float,
    ) {
        val p = doc(u, v) ?: return
        val c =
            state.selectedLocalMask
                ?.mask
                ?.components
                ?.getOrNull(local.component) ?: return
        when {
            c is MaskComponent.Linear && drag == Drag.Create -> {
                replace(c.copy(x1 = p.first, y1 = p.second))
            }

            c is MaskComponent.Radial && drag == Drag.MoveCentre -> {
                replace(c.copy(cx = p.first, cy = p.second))
            }

            c is MaskComponent.Radial && drag == Drag.Resize -> {
                replace(c.copy(rx = max(MIN_RADIUS, abs(p.first - c.cx)), ry = max(MIN_RADIUS, abs(p.second - c.cy))))
            }

            c is MaskComponent.Brush && drag == Drag.Paint -> {
                growStroke(c, p)
            }

            else -> {
                Unit
            }
        }
    }

    private fun growStroke(
        c: MaskComponent.Brush,
        p: Pair<Float, Float>,
    ) {
        val last = c.strokes.lastOrNull() ?: return
        val n = last.points.size
        if (n >= 2 && hypot(p.first - last.points[n - 2], p.second - last.points[n - 1]) < last.radius * MIN_SPACING) return
        replace(c.copy(strokes = c.strokes.dropLast(1) + last.plus(p.first, p.second)))
    }

    private fun end() {
        if (drag != Drag.Pick) host.endEdit()
        drag = null
        dragStart = null
    }

    private fun replace(next: MaskComponent) = editComponent(local.component) { next }

    /** A graph is about to reach the engine: nothing to register beyond the mask rasters (the selection actions do it). */
    fun onCanvasGraph(
        @Suppress("UNUSED_PARAMETER") graph: EditGraph,
    ) = Unit

    private companion object {
        const val RADIAL_FRACTION = 4f
        const val DEFAULT_FEATHER = 50f
        const val DEFAULT_FUZZINESS = 20f
        const val LUMA_LO = 0.5f
        const val LUMA_SMOOTH = 0.1f
        const val DEPTH_LO = 0.5f
        const val DEPTH_SMOOTH = 0.15f
        const val MIN_RADIUS = 4f
        const val MIN_SPACING = 0.1f
        const val MOVE_REACH = 0.3f
        const val PIN_REACH = 0.035f
        const val MAX_COLORS = 5
        const val LAB = 3
        const val BYTE = 0xFF
    }
}
