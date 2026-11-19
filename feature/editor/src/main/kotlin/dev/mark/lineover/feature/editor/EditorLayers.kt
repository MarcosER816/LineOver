package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.io.layers.LayerSources
import dev.mark.lineover.core.model.AdjustmentKind
import dev.mark.lineover.core.model.BlendMode
import dev.mark.lineover.core.model.BlendSpace
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.Layer
import dev.mark.lineover.core.model.LayerMath
import dev.mark.lineover.core.model.LayerPlacement
import dev.mark.lineover.core.model.LayerType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Layers panel does (Stage 13). Continuous changes end with [EditorIntent.EndEdit]. */
sealed interface LayerAction {
    /** A photo (a picker content URI) as a raster layer, or as a smart object ([smart]). */
    data class Import(
        val uri: String,
        val smart: Boolean,
    ) : LayerAction

    /** An adjustment layer of [kind]; null = the photo's own point ops ("From current edit"). */
    data class AddAdjustment(
        val kind: AdjustmentKind?,
    ) : LayerAction

    data object AddGroup : LayerAction

    /** Null selects the photo. */
    data class Select(
        val id: String?,
    ) : LayerAction

    data class SetVisible(
        val id: String,
        val visible: Boolean,
    ) : LayerAction

    data class SetLocked(
        val id: String,
        val locked: Boolean,
    ) : LayerAction

    data class SetIsolated(
        val id: String,
        val isolated: Boolean,
    ) : LayerAction

    data class SetOpacity(
        val id: String,
        val value: Float,
    ) : LayerAction

    data class SetFill(
        val id: String,
        val value: Float,
    ) : LayerAction

    /** The blend list is being scrolled: the canvas shows [mode] (null = back to the layer's own). */
    data class PreviewBlend(
        val id: String,
        val mode: BlendMode?,
    ) : LayerAction

    data class SetBlend(
        val id: String,
        val mode: BlendMode,
    ) : LayerAction

    /** [id] to [index] among its siblings (bottom to top). */
    data class Move(
        val id: String,
        val index: Int,
    ) : LayerAction

    data class Delete(
        val id: String,
    ) : LayerAction

    /** The undo of a delete: [layer] back where it was. */
    class Restore(
        val layer: Layer,
        val parentId: String?,
        val index: Int,
    ) : LayerAction

    data class Duplicate(
        val id: String,
    ) : LayerAction

    data class MergeDown(
        val id: String,
    ) : LayerAction

    data object Flatten : LayerAction

    data class Group(
        val id: String,
    ) : LayerAction

    data class Ungroup(
        val id: String,
    ) : LayerAction

    /** An adjustment layer's (or a smart object's quick) control. */
    data class SetAdjustment(
        val id: String,
        val kind: AdjustmentKind,
        val value: Float,
    ) : LayerAction

    data class SetPlacement(
        val id: String,
        val placement: LayerPlacement,
    ) : LayerAction

    data class SetBlendSpace(
        val space: BlendSpace,
    ) : LayerAction
}

/** The selected layer (null = the photo, or a layer that no longer exists). */
internal val EditorState.selectedLayer: Layer?
    get() = selectedLayerId?.let { LayerMath.find(graph, it) }

/**
 * The Stage 13 intents of [EditorViewModel]. Layer images are imported into the app's store
 * and registered with the engine (off the main thread) before a graph names them; merge down
 * and flatten only group layers (pixels are combined when exporting).
 */
@Suppress("TooManyFunctions") // one function per layer action, like the ViewModel it serves
internal class EditorLayersActions(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val host: Host,
    private val sources: LayerSources?,
) {
    /** What the actions need of [EditorViewModel]. */
    interface Host {
        val state: EditorState

        fun reduce(
            label: String?,
            block: EditorState.() -> EditorState,
        )

        fun message(text: String)

        fun undoable(
            text: String,
            undo: EditorIntent,
        )

        /** Sends the canvas graph to the engine again (a layer image it names was just registered). */
        fun resubmit()
    }

    private val registered = HashSet<String>()

    private fun state() = host.state

    private fun reduce(
        label: String?,
        block: EditorState.() -> EditorState,
    ) = host.reduce(label, block)

    @Suppress("CyclomaticComplexMethod") // a table: one branch per action
    fun onAction(action: LayerAction) {
        when (action) {
            is LayerAction.Import -> import(action)
            is LayerAction.AddAdjustment -> addAdjustment(action.kind)
            is LayerAction.AddGroup -> add(Layer(LayerMath.newId(), type = LayerType.Group, name = "Group"), "Add group")
            is LayerAction.Select -> reduce(null) { copy(selectedLayerId = action.id, compareGraph = null) }
            is LayerAction.SetVisible -> change(action.id, null, ignoreLock = true) { it.copy(visible = action.visible) }
            is LayerAction.SetLocked -> change(action.id, null, ignoreLock = true) { it.copy(locked = action.locked) }
            is LayerAction.SetIsolated -> change(action.id, null) { it.copy(isolated = action.isolated) }
            is LayerAction.SetOpacity -> change(action.id, null) { it.copy(opacity = action.value.coerceIn(0f, Layer.FULL)) }
            is LayerAction.SetFill -> change(action.id, null) { it.copy(fill = action.value.coerceIn(0f, Layer.FULL)) }
            is LayerAction.PreviewBlend -> previewBlend(action)
            is LayerAction.SetBlend -> setBlend(action)
            is LayerAction.Move -> move(action)
            is LayerAction.Delete -> delete(action.id)
            is LayerAction.Restore -> restore(action)
            is LayerAction.Duplicate -> structure(action.id, "Duplicate") { g -> LayerMath.duplicate(g, action.id) }
            is LayerAction.MergeDown -> mergeDown(action.id)
            is LayerAction.Flatten -> flatten()
            is LayerAction.Group -> structure(action.id, "Group") { g -> LayerMath.group(g, action.id) }
            is LayerAction.Ungroup -> structure(action.id, "Ungroup") { g -> LayerMath.ungroup(g, action.id) }
            is LayerAction.SetAdjustment -> change(action.id, null) { it.copy(ops = action.kind.withValue(it.ops, action.value)) }
            is LayerAction.SetPlacement -> setPlacement(action)
            is LayerAction.SetBlendSpace -> setBlendSpace(action.space)
        }
    }

    private fun nameOf(layer: Layer) = layer.name.ifBlank { layer.type.label }

    /** [f] on layer [id]: refused while it is locked (except its eye and lock). */
    private fun change(
        id: String,
        label: String?,
        ignoreLock: Boolean = false,
        f: (Layer) -> Layer,
    ) {
        val layer = LayerMath.find(state().graph, id) ?: return
        if (layer.locked && !ignoreLock) return host.message("${nameOf(layer)} is locked")
        reduce(label) { copy(graph = LayerMath.update(graph, id, f), compareGraph = null) }
    }

    private fun add(
        layer: Layer,
        label: String,
    ) {
        reduce(label) {
            copy(graph = LayerMath.add(graph, layer, aboveId = selectedLayerId), selectedLayerId = layer.id, compareGraph = null)
        }
    }

    private fun import(action: LayerAction.Import) {
        val store = sources ?: return host.message("Layers cannot import photos here")
        val (docW, docH) = state().canvasSize ?: return host.message("Wait for the photo to finish loading")
        scope.launch {
            val source = withContext(dispatcher) { store.import(action.uri)?.also { register(it.name) } }
            if (source == null) {
                host.message("This photo cannot be used as a layer")
                return@launch
            }
            val w = source.width.toFloat()
            val h = source.height.toFloat()
            val count =
                LayerMath
                    .flatten(
                        state().graph,
                    ).count { it.first.type == LayerType.Raster || it.first.type == LayerType.SmartObject }
            val layer =
                Layer(
                    id = LayerMath.newId(),
                    type = if (action.smart) LayerType.SmartObject else LayerType.Raster,
                    name = if (action.smart) "Smart object ${count + 1}" else "Photo ${count + 2}",
                    source = source.name,
                    sourceSize = listOf(w, h),
                    transform = LayerPlacement.fit(w, h, docW.toFloat(), docH.toFloat()).transform(w, h),
                )
            add(layer, if (action.smart) "Add smart object" else "Add photo layer")
        }
    }

    private fun addAdjustment(kind: AdjustmentKind?) {
        val ops =
            if (kind == null) {
                state().backgroundOps.filter(AdjustmentKind::isPointOp)
            } else {
                listOf(kind.op(kind.default))
            }
        if (ops.isEmpty()) return host.message("Edit the photo first: its edit has nothing to copy")
        val name = kind?.label ?: "Current edit"
        add(Layer(LayerMath.newId(), ops = ops, type = LayerType.Adjustment, name = name), "Add adjustment: $name")
    }

    private fun previewBlend(action: LayerAction.PreviewBlend) {
        val layer = LayerMath.find(state().graph, action.id) ?: return
        // reduce, not setState: the canvas follows canvasGraph; the edit itself is unchanged.
        reduce(null) {
            copy(
                compareGraph =
                    action.mode?.takeIf { it != layer.blend }?.let { m ->
                        LayerMath.update(graph, action.id) { it.copy(blend = m) }
                    },
            )
        }
    }

    private fun setBlend(action: LayerAction.SetBlend) {
        val layer = LayerMath.find(state().graph, action.id) ?: return
        change(action.id, "${nameOf(layer)}: ${action.mode.label}") { it.copy(blend = action.mode) }
    }

    private fun move(action: LayerAction.Move) {
        val (siblings, from) = LayerMath.siblings(state().graph, action.id) ?: return
        if (from == action.index) return
        reduce("Move ${nameOf(siblings[from])}") { copy(graph = LayerMath.move(graph, action.id, action.index)) }
    }

    private fun delete(id: String) {
        val layer = LayerMath.find(state().graph, id) ?: return
        if (layer.locked) return host.message("${nameOf(layer)} is locked")
        val (parent, index) = LayerMath.parentOf(state().graph, id) ?: return
        reduce("Delete ${nameOf(layer)}") {
            copy(graph = LayerMath.remove(graph, id), selectedLayerId = selectedLayerId.takeIf { it != id }, compareGraph = null)
        }
        host.undoable("Deleted ${nameOf(layer)}", EditorIntent.Layers(LayerAction.Restore(layer, parent, index)))
    }

    private fun restore(action: LayerAction.Restore) {
        if (LayerMath.find(state().graph, action.layer.id) != null) return
        reduce("Restore ${nameOf(action.layer)}") {
            copy(graph = LayerMath.insertAt(graph, action.layer, action.parentId, action.index), selectedLayerId = action.layer.id)
        }
    }

    private fun structure(
        id: String,
        verb: String,
        f: (EditGraph) -> EditGraph,
    ) {
        val layer = LayerMath.find(state().graph, id) ?: return
        val before = state().graph
        val after = f(before)
        if (after == before) return
        val created = LayerMath.flatten(after).map { it.first.id }.firstOrNull { LayerMath.find(before, it) == null }
        reduce(
            "$verb ${nameOf(layer)}",
        ) { copy(graph = after, selectedLayerId = created ?: id.takeIf { LayerMath.find(after, it) != null }) }
    }

    private fun mergeDown(id: String) {
        if (!LayerMath.canMergeDown(state().graph, id)) return host.message("The photo itself is never merged into")
        structure(id, "Merge down") { g -> LayerMath.mergeDown(g, id) }
    }

    private fun flatten() {
        if (LayerMath.flatten(state().graph).isEmpty()) return
        val before = state().graph
        val after = LayerMath.flattenAll(before)
        reduce("Flatten") { copy(graph = after, selectedLayerId = after.layers.last().id) }
    }

    private fun setPlacement(action: LayerAction.SetPlacement) {
        val layer = LayerMath.find(state().graph, action.id) ?: return
        val (w, h) = layer.sourceSize.takeIf { it.size == 2 } ?: return
        change(action.id, null) { it.copy(transform = action.placement.transform(w, h)) }
    }

    private fun setBlendSpace(space: BlendSpace) {
        if (state().graph.blendSpace == space) return
        reduce(if (space == BlendSpace.Linear) "Blend in linear light" else "Blend gamma-encoded") {
            copy(graph = graph.copy(blendSpace = space))
        }
    }

    private fun register(name: String): Boolean {
        val canvas = state().canvas ?: return false
        val file = sources?.file(name) ?: return false
        val ok = canvas.registerLayerImage(name, file)
        if (ok) synchronized(registered) { registered += name }
        return ok
    }

    /** A graph is about to reach the engine: register the layer images it names that are new. */
    fun onCanvasGraph(graph: EditGraph) {
        if (sources == null) return
        val missing = LayerMath.sources(graph).filter { synchronized(registered) { it !in registered } }
        if (missing.isEmpty()) return
        scope.launch {
            val any = withContext(dispatcher) { missing.map { register(it) }.any { it } }
            if (any) host.resubmit()
        }
    }
}
