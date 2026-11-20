package dev.mark.lineover.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * A layer mask (Stage 14): a base value (1 = white, the layer shows; 0 = black, hidden) and the
 * paint on it, in document pixels. The engine rasterises it per region into R16F tiles,
 * inverts it, feathers it (Gaussian of sigma [feather] document pixels, like OpenCV's
 * GaussianBlur) and scales it by [density]: shown = 1 - density (1 - mask). The paint is the
 * mask's history: undoable, non-destructive and the same at any resolution.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LayerMask(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val base: Float = WHITE,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val enabled: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val inverted: Boolean = false,
    /** 0..100 %: how much the black hides. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val density: Float = FULL,
    /** Gaussian sigma in document pixels. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val feather: Float = 0f,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val paint: List<MaskPaint> = emptyList(),
    /**
     * A local mask's group (Stage 17): when not empty it is the mask ([base] and [paint] are
     * ignored), its components combined in order from black ([MaskComponent]).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val components: List<MaskComponent> = emptyList(),
) {
    /** True for a local mask (a group of components). */
    val isLocal: Boolean get() = components.isNotEmpty()

    /** The mask store's rasters this mask names (saved selections, model masks, depth maps, their guides). */
    val rasterNames: Set<String>
        get() =
            (
                paint.filterIsInstance<MaskPaint.Raster>().flatMap { listOfNotNull(it.source, it.guide) } +
                    components.flatMap {
                        when (it) {
                            is MaskComponent.Raster -> listOfNotNull(it.source, it.guide)
                            is MaskComponent.DepthRange -> listOfNotNull(it.source, it.guide)
                            else -> emptyList()
                        }
                    }
            ).toSet()

    /** [paint] with one more operation; a gradient replaces everything before it. */
    fun with(op: MaskPaint): LayerMask = copy(paint = if (op is MaskPaint.Gradient || op is MaskPaint.Raster) listOf(op) else paint + op)

    /** The last operation replaced (a stroke that grows while the finger moves). */
    fun withLast(op: MaskPaint): LayerMask = copy(paint = paint.dropLast(1) + op)

    companion object {
        const val WHITE = 1f
        const val BLACK = 0f
        const val FULL = 100f
        const val MAX_FEATHER = 250f
    }
}

/** Paint on a mask, in document pixels. Serialised with a "kind" (the graph's ops use "op"). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface MaskPaint {
    /**
     * A round brush along a polyline ([points] = x0, y0, x1, y1, ...). [value] 1 paints white,
     * 0 black; [hardness] 0..1 is where the falloff starts; [opacity] 0..100 %.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @SerialName("stroke")
    data class Stroke(
        val value: Float,
        val radius: Float,
        val hardness: Float,
        val opacity: Float,
        val points: List<Float>,
        /** Stage 17, a local brush's auto-mask: OKLab x 100 of the photo under the first point. */
        @EncodeDefault(EncodeDefault.Mode.NEVER) val color: List<Float>? = null,
    ) : MaskPaint {
        fun plus(
            x: Float,
            y: Float,
        ): Stroke = copy(points = points + x + y)
    }

    /**
     * Replaces the mask with a saved selection (Stage 15): the raster [source] (a file of the
     * app's mask store) of [width] x [height] pixels covering a [docWidth] x [docHeight]
     * document, sampled bilinearly. With a [guide] (Stage 16: a raster of the image it was made
     * on, 3 planes of sqrt(rgb), [width] x 3 [height]) an export upsamples it to full resolution
     * by joint bilateral filtering against the photo, so its edges follow the photo's.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @SerialName("raster")
    data class Raster(
        val source: String,
        val width: Float,
        val height: Float,
        val docWidth: Float,
        val docHeight: Float,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val guide: String? = null,
    ) : MaskPaint

    /** Replaces the mask with a linear ramp: [from] at (x0, y0) to [to] at (x1, y1). */
    @Serializable
    @SerialName("gradient")
    data class Gradient(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val from: Float,
        val to: Float,
    ) : MaskPaint
}
