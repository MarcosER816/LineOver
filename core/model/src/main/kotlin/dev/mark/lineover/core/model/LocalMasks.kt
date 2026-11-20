package dev.mark.lineover.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** How a mask component joins the group (as selections combine, Stage 15). */
@Serializable
enum class CombineMode {
    /** max(M, A) */
    @SerialName("add")
    Add,

    /** min(M, 1 - A) */
    @SerialName("subtract")
    Subtract,

    /** min(M, A) */
    @SerialName("intersect")
    Intersect,
}

/** What made a model raster (Stage 17's AI masks). */
@Serializable
enum class AiMaskKind {
    @SerialName("subject")
    Subject,

    @SerialName("sky")
    Sky,

    @SerialName("background")
    Background,

    @SerialName("people")
    People,
}

/**
 * One component of a local mask (Stage 17), in document pixels; the engine's masks.cpp
 * evaluates it (tools/golden/local_mask_reference.py is the reference). A component's value A
 * (0..1) is flipped by [invert] and joins the group by [mode]. Serialised with a "kind"; the
 * field order matches graph.cpp's serializeMask.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface MaskComponent {
    val mode: CombineMode
    val invert: Boolean

    /** This component with another [mode] / [invert]. */
    fun with(
        mode: CombineMode = this.mode,
        invert: Boolean = this.invert,
    ): MaskComponent =
        when (this) {
            is Linear -> copy(mode = mode, invert = invert)
            is Radial -> copy(mode = mode, invert = invert)
            is Brush -> copy(mode = mode, invert = invert)
            is ColorRange -> copy(mode = mode, invert = invert)
            is LuminanceRange -> copy(mode = mode, invert = invert)
            is DepthRange -> copy(mode = mode, invert = invert)
            is Raster -> copy(mode = mode, invert = invert)
        }

    /** 1 at (x0, y0) falling linearly to 0 at (x1, y1). */
    @Serializable
    @SerialName("linear")
    data class Linear(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        @EncodeDefault(EncodeDefault.Mode.NEVER) override val mode: CombineMode = CombineMode.Add,
        @EncodeDefault(EncodeDefault.Mode.NEVER) override val invert: Boolean = false,
    ) : MaskComponent

    /** An ellipse at (cx, cy) with radii (rx, ry) rotated [angle] degrees; [feather] 0..100 % of the radius. */
    @Serializable
    @SerialName("radial")
    data class Radial(
        val cx: Float,
        val cy: Float,
        val rx: Float,
        val ry: Float,
        val angle: Float,
        val feather: Float,
        @EncodeDefault(EncodeDefault.Mode.NEVER) override val mode: CombineMode = CombineMode.Add,
        @EncodeDefault(EncodeDefault.Mode.NEVER) override val invert: Boolean = false,
    ) : MaskComponent

    /**
     * Round-brush strokes over black (value 1 paints, 0 erases; hardness = 1 - feather, opacity =
     * flow). With [autoMask] each painting stroke keeps to colours like the one under its first
     * point ([MaskPaint.Stroke.color]), edge-aware (a colour guided filter).
     */
    @Serializable
    @SerialName("brush")
    data class Brush(
        val strokes: List<MaskPaint.Stroke>,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val autoMask: Boolean = false,
        @EncodeDefault(EncodeDefault.Mode.NEVER) override val mode: CombineMode = CombineMode.Add,
        @EncodeDefault(EncodeDefault.Mode.NEVER) override val invert: Boolean = false,
    ) : MaskComponent

    /** 1 - (delta E to the nearest picked colour, OKLab x 100 triples in [colors]) / [fuzziness]. */
    @Serializable
    @SerialName("color_range")
    data class ColorRange(
        val colors: List<Float>,
        val fuzziness: Float,
        @EncodeDefault(EncodeDefault.Mode.NEVER) override val mode: CombineMode = CombineMode.Add,
        @EncodeDefault(EncodeDefault.Mode.NEVER) override val invert: Boolean = false,
    ) : MaskComponent

    /** OKLab lightness (0..1) inside [lo]..[hi], with [smooth] edges. */
    @Serializable
    @SerialName("luminance_range")
    data class LuminanceRange(
        val lo: Float,
        val hi: Float,
        val smooth: Float,
        @EncodeDefault(EncodeDefault.Mode.NEVER) override val mode: CombineMode = CombineMode.Add,
        @EncodeDefault(EncodeDefault.Mode.NEVER) override val invert: Boolean = false,
    ) : MaskComponent

    /** A depth raster (1 = near; [source] in the mask store) inside [lo]..[hi], with [smooth] edges. */
    @Serializable
    @SerialName("depth_range")
    data class DepthRange(
        val source: String,
        val width: Float,
        val height: Float,
        val docWidth: Float,
        val docHeight: Float,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val guide: String? = null,
        val lo: Float,
        val hi: Float,
        val smooth: Float,
        @EncodeDefault(EncodeDefault.Mode.NEVER) override val mode: CombineMode = CombineMode.Add,
        @EncodeDefault(EncodeDefault.Mode.NEVER) override val invert: Boolean = false,
    ) : MaskComponent

    /** A model's mask ([tool]) saved in the mask store, with its colour guide for exports. */
    @Serializable
    @SerialName("raster")
    data class Raster(
        val source: String,
        val width: Float,
        val height: Float,
        val docWidth: Float,
        val docHeight: Float,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val guide: String? = null,
        val tool: AiMaskKind,
        @EncodeDefault(EncodeDefault.Mode.NEVER) override val mode: CombineMode = CombineMode.Add,
        @EncodeDefault(EncodeDefault.Mode.NEVER) override val invert: Boolean = false,
    ) : MaskComponent
}
