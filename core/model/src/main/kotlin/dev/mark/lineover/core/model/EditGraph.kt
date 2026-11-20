package dev.mark.lineover.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * A layer and the ordered, immutable list of ops applied to it. The first [LayerType.Background]
 * layer is the photo (its ops are the develop edit); Stage 13 added the other layer types and
 * their properties. Fields after [ops] are written only when not their default, so graphs
 * without layers keep the exact JSON they had (core/engine-native graph.cpp writes the same).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Layer(
    val id: String,
    val ops: List<OpParams> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val type: LayerType = LayerType.Background,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val name: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val visible: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val locked: Boolean = false,
    /** 0..100 %: the whole layer. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val opacity: Float = FULL,
    /** 0..100 %: the layer's content (layer styles, when they arrive, ignore it). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fill: Float = FULL,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val blend: BlendMode = BlendMode.Normal,
    /** Groups: isolated (composited on their own, then blended), else pass-through. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val isolated: Boolean = false,
    /** Raster / smart object: the layer image's file in the app's layers folder. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val source: String = "",
    /** Its full pixel size (oriented): width, height. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sourceSize: List<Float> = emptyList(),
    /** Source px -> document (output) px: a, b, c, d, e, f (x = a sx + c sy + e, y = b sx + d sy + f). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val transform: List<Float> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val children: List<Layer> = emptyList(),
    /** Stage 14: shown only where the layer below (its clipping base) covers. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val clip: Boolean = false,
    /** Stage 14: the layer mask, or none. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mask: LayerMask? = null,
) {
    val isBackground: Boolean get() = type == LayerType.Background

    companion object {
        const val FULL = 100f
    }
}

/**
 * The whole edit of one document: layers from bottom to top. The graph is an
 * immutable value; every edit produces a new graph, which is what the undo stack
 * stores (graph snapshots, never pixels).
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class EditGraph(
    val schemaVersion: Int = SCHEMA_VERSION,
    val layers: List<Layer> = emptyList(),
    /** Stage 13: where layers blend; written only when linear. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val blendSpace: BlendSpace = BlendSpace.Gamma,
    /** Stage 15: the selection, or none (the engine evaluates it apart from the image). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val selection: Selection? = null,
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
