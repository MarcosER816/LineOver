package dev.mark.lineover.core.model

import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Layer operations on an [EditGraph] (Stage 13). The graph's [EditGraph.layers] list is bottom
 * to top; groups hold their children the same way. Every operation returns a new graph; the
 * background (the photo) stays first and cannot be moved, grouped or deleted.
 *
 * Merge down and flatten are non-destructive: they put the layers into a pass-through group
 * (which looks exactly like the layers on their own); pixels are only combined when exporting.
 */
object LayerMath {
    fun newId(): String =
        "l" +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(ID_LENGTH)

    /** Every layer above the background, depth first (bottom to top), with its depth. */
    fun flatten(graph: EditGraph): List<Pair<Layer, Int>> {
        val out = ArrayList<Pair<Layer, Int>>()

        fun walk(
            layers: List<Layer>,
            depth: Int,
        ) {
            for (l in layers) {
                if (l.isBackground) continue
                out += l to depth
                if (l.type == LayerType.Group) walk(l.children, depth + 1)
            }
        }
        walk(graph.layers, 0)
        return out
    }

    fun find(
        graph: EditGraph,
        id: String,
    ): Layer? = flatten(graph).firstOrNull { it.first.id == id }?.first

    /** True when the graph draws something above the photo (the canvas then shows the composite). */
    fun hasLayers(graph: EditGraph): Boolean = graph.layers.any { !it.isBackground && draws(it) }

    private fun draws(l: Layer): Boolean =
        l.visible &&
            when (l.type) {
                LayerType.Group -> l.children.any(::draws)
                LayerType.Raster, LayerType.SmartObject, LayerType.Adjustment -> true
                else -> false
            }

    /** [layer] added above [aboveId]'s layer (same parent), or at the top of the document. */
    fun add(
        graph: EditGraph,
        layer: Layer,
        aboveId: String? = null,
    ): EditGraph {
        if (aboveId != null && find(graph, aboveId) != null) {
            return graph.copy(layers = insertAbove(graph.layers, aboveId, layer))
        }
        val base = if (graph.layers.isEmpty()) listOf(Layer(EditGraph.BACKGROUND)) else graph.layers
        return graph.copy(layers = base + layer)
    }

    private fun insertAbove(
        layers: List<Layer>,
        aboveId: String,
        layer: Layer,
    ): List<Layer> {
        val i = layers.indexOfFirst { it.id == aboveId }
        if (i >= 0) return layers.subList(0, i + 1) + layer + layers.subList(i + 1, layers.size)
        return layers.map { if (it.type == LayerType.Group) it.copy(children = insertAbove(it.children, aboveId, layer)) else it }
    }

    fun update(
        graph: EditGraph,
        id: String,
        change: (Layer) -> Layer,
    ): EditGraph = graph.copy(layers = mapTree(graph.layers) { if (it.id == id && !it.isBackground) change(it) else it })

    /** [f] on every layer, children first (so [f] can wrap a layer without seeing it again). */
    private fun mapTree(
        layers: List<Layer>,
        f: (Layer) -> Layer,
    ): List<Layer> =
        layers.map { l ->
            f(if (l.type == LayerType.Group) l.copy(children = mapTree(l.children, f)) else l)
        }

    fun remove(
        graph: EditGraph,
        id: String,
    ): EditGraph = graph.copy(layers = removeFrom(graph.layers, id))

    private fun removeFrom(
        layers: List<Layer>,
        id: String,
    ): List<Layer> =
        layers
            .filterNot { it.id == id && !it.isBackground }
            .map { if (it.type == LayerType.Group) it.copy(children = removeFrom(it.children, id)) else it }

    /** Where [id] sits: its parent group's id (null at the top level) and its index there. */
    fun parentOf(
        graph: EditGraph,
        id: String,
    ): Pair<String?, Int>? {
        fun search(
            parent: String?,
            layers: List<Layer>,
        ): Pair<String?, Int>? {
            val i = layers.indexOfFirst { it.id == id }
            if (i >= 0) return parent to i
            return layers.filter { it.type == LayerType.Group }.firstNotNullOfOrNull { search(it.id, it.children) }
        }
        return search(null, graph.layers)
    }

    /** [layer] put back at [index] of [parentId]'s children (null = the top level, above the photo). */
    fun insertAt(
        graph: EditGraph,
        layer: Layer,
        parentId: String?,
        index: Int,
    ): EditGraph {
        fun into(list: List<Layer>): List<Layer> {
            val floor = if (list.firstOrNull()?.isBackground == true) 1 else 0
            val at = index.coerceIn(floor, list.size)
            return list.subList(0, at) + layer + list.subList(at, list.size)
        }
        if (parentId == null) return graph.copy(layers = into(graph.layers))
        if (find(graph, parentId)?.type != LayerType.Group) return add(graph, layer)
        return graph.copy(layers = mapTree(graph.layers) { if (it.id == parentId) it.copy(children = into(it.children)) else it })
    }

    /** The siblings of [id] (bottom to top) and its index among them, or null. */
    fun siblings(
        graph: EditGraph,
        id: String,
    ): Pair<List<Layer>, Int>? {
        fun search(layers: List<Layer>): Pair<List<Layer>, Int>? {
            val i = layers.indexOfFirst { it.id == id }
            if (i >= 0) return layers to i
            return layers.filter { it.type == LayerType.Group }.firstNotNullOfOrNull { search(it.children) }
        }
        return search(graph.layers)
    }

    /** Moves [id] to position [to] among its siblings (0 = lowest above the photo at the top level). */
    fun move(
        graph: EditGraph,
        id: String,
        to: Int,
    ): EditGraph {
        fun reorder(layers: List<Layer>): List<Layer> {
            val i = layers.indexOfFirst { it.id == id }
            if (i < 0) return layers.map { if (it.type == LayerType.Group) it.copy(children = reorder(it.children)) else it }
            val list = layers.toMutableList()
            val moving = list.removeAt(i)
            val floor = if (list.firstOrNull()?.isBackground == true) 1 else 0
            list.add(to.coerceIn(floor, list.size), moving)
            return list
        }
        return graph.copy(layers = reorder(graph.layers))
    }

    /** A copy of [id] (new ids throughout) right above it. */
    fun duplicate(
        graph: EditGraph,
        id: String,
    ): EditGraph {
        val layer = find(graph, id) ?: return graph
        val copy = renew(layer).copy(name = "${layer.name.ifBlank { layer.type.label }} copy")
        return graph.copy(layers = insertAbove(graph.layers, id, copy))
    }

    private fun renew(layer: Layer): Layer = layer.copy(id = newId(), children = layer.children.map(::renew))

    /** False when [id] is the lowest layer above the photo: the photo itself is never merged into. */
    fun canMergeDown(
        graph: EditGraph,
        id: String,
    ): Boolean {
        val (list, i) = siblings(graph, id) ?: return false
        return i > 0 && !list[i - 1].isBackground
    }

    /** [id] and the layer under it, together in a pass-through group (looks the same; merged at export). */
    fun mergeDown(
        graph: EditGraph,
        id: String,
    ): EditGraph {
        if (!canMergeDown(graph, id)) return graph

        fun merge(layers: List<Layer>): List<Layer> {
            val i = layers.indexOfFirst { it.id == id }
            if (i < 0) return layers.map { if (it.type == LayerType.Group) it.copy(children = merge(it.children)) else it }
            val below = layers[i - 1]
            val group =
                Layer(
                    id = newId(),
                    type = LayerType.Group,
                    name = "${below.name.ifBlank { below.type.label }} + ${layers[i].name.ifBlank { layers[i].type.label }}",
                    children = listOf(below, layers[i]),
                )
            return layers.subList(0, i - 1) + group + layers.subList(i + 1, layers.size)
        }
        return graph.copy(layers = merge(graph.layers))
    }

    /** Every layer above the photo in one pass-through group "Flattened" (looks the same; merged at export). */
    fun flattenAll(graph: EditGraph): EditGraph {
        val above = graph.layers.filterNot { it.isBackground }
        if (above.isEmpty()) return graph
        val group = Layer(id = newId(), type = LayerType.Group, name = FLATTENED, children = above)
        return graph.copy(layers = graph.layers.filter { it.isBackground } + group)
    }

    /** [id] wrapped in a new pass-through group. */
    fun group(
        graph: EditGraph,
        id: String,
    ): EditGraph {
        val layer = find(graph, id) ?: return graph
        val group = Layer(id = newId(), type = LayerType.Group, name = "Group", children = listOf(layer))
        return graph.copy(layers = mapTree(graph.layers) { if (it.id == id) group else it })
    }

    /** A group's children put back in its place. */
    fun ungroup(
        graph: EditGraph,
        id: String,
    ): EditGraph {
        fun open(layers: List<Layer>): List<Layer> =
            layers.flatMap { l ->
                when {
                    l.id == id && l.type == LayerType.Group -> l.children
                    l.type == LayerType.Group -> listOf(l.copy(children = open(l.children)))
                    else -> listOf(l)
                }
            }
        return graph.copy(layers = open(graph.layers))
    }

    /** The ops of every layer, the background's first (LUTs they name are registered with the engine). */
    fun allOps(graph: EditGraph): List<OpParams> =
        graph.layers
            .firstOrNull()
            ?.ops
            .orEmpty() + flatten(graph).flatMap { it.first.ops }

    /** Layer images the graph names (to register them with the engine). */
    fun sources(graph: EditGraph): Set<String> =
        flatten(graph)
            .map { it.first }
            .filter { it.type == LayerType.Raster || it.type == LayerType.SmartObject }
            .mapNotNull { it.source.ifBlank { null } }
            .toSet()

    const val FLATTENED = "Flattened"
    private const val ID_LENGTH = 12
}

/**
 * Where a placed layer sits (smart objects can be moved, scaled and turned; Stage 13): its
 * centre in document pixels, its scale (document px per source px) and its rotation in degrees.
 */
data class LayerPlacement(
    val centerX: Float,
    val centerY: Float,
    val scale: Float,
    val degrees: Float,
) {
    /** The layer's transform (source px -> document px) for a source of [sourceW] x [sourceH]. */
    fun transform(
        sourceW: Float,
        sourceH: Float,
    ): List<Float> {
        val t = Math.toRadians(degrees.toDouble())
        val a = (scale * cos(t)).toFloat()
        val b = (scale * sin(t)).toFloat()
        val c = -b
        val d = a
        // The source centre lands on the placement's centre.
        val e = centerX - (a * sourceW / 2 + c * sourceH / 2)
        val f = centerY - (b * sourceW / 2 + d * sourceH / 2)
        return listOf(a, b, c, d, e, f)
    }

    companion object {
        /** The placement of [transform] (identity when empty). */
        fun of(
            transform: List<Float>,
            sourceW: Float,
            sourceH: Float,
        ): LayerPlacement {
            val t = transform.takeIf { it.size == TRANSFORM_SIZE } ?: listOf(1f, 0f, 0f, 1f, 0f, 0f)
            val (a, b, c, d) = t
            val e = t[4]
            val f = t[5]
            val cx = a * sourceW / 2 + c * sourceH / 2 + e
            val cy = b * sourceW / 2 + d * sourceH / 2 + f
            return LayerPlacement(cx, cy, hypot(a, b), Math.toDegrees(atan2(b, a).toDouble()).toFloat())
        }

        /** A source of [sourceW] x [sourceH] fitted inside a [docW] x [docH] document, centred. */
        fun fit(
            sourceW: Float,
            sourceH: Float,
            docW: Float,
            docH: Float,
        ): LayerPlacement = LayerPlacement(docW / 2, docH / 2, min(docW / sourceW, docH / sourceH), 0f)

        const val TRANSFORM_SIZE = 6
    }
}
