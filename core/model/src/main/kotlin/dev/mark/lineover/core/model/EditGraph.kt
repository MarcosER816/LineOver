package dev.mark.lineover.core.model

import kotlinx.serialization.Serializable

/** A layer and the ordered, immutable list of ops applied to it. */
@Serializable
data class Layer(
    val id: String,
    val ops: List<OpParams> = emptyList(),
)

/**
 * The whole edit of one document: layers from bottom to top. The graph is an
 * immutable value; every edit produces a new graph, which is what the undo stack
 * stores (graph snapshots, never pixels).
 */
@Serializable
data class EditGraph(
    val schemaVersion: Int = SCHEMA_VERSION,
    val layers: List<Layer> = emptyList(),
) {
    /** A copy whose bottom layer ("background", created if missing) has [ops]. */
    fun withBackgroundOps(ops: List<OpParams>): EditGraph {
        val background = layers.firstOrNull() ?: Layer(BACKGROUND)
        return copy(layers = listOf(background.copy(ops = ops)) + layers.drop(1))
    }

    /**
     * This graph with every layer and op that equals one of [previous]'s reusing
     * [previous]'s instance (structural sharing): history entries then hold only what an
     * edit changed, the rest is shared with the entry before.
     */
    fun sharing(previous: EditGraph): EditGraph {
        if (this == previous) return previous
        val oldLayers = previous.layers.associateBy { it.id }
        return copy(
            layers =
                layers.map { layer ->
                    val old = oldLayers[layer.id] ?: return@map layer
                    when {
                        old == layer -> {
                            old
                        }

                        else -> {
                            val pool = old.ops
                            layer.copy(ops = layer.ops.map { op -> pool.firstOrNull { it == op } ?: op })
                        }
                    }
                },
        )
    }

    companion object {
        /** Id of the bottom layer: the photo. */
        const val BACKGROUND = "background"

        const val SCHEMA_VERSION = 1
    }
}
