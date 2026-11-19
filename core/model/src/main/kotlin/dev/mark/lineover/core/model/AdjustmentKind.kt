package dev.mark.lineover.core.model

import kotlin.math.roundToInt

/**
 * The adjustment layers the Layers panel offers (Stage 13): any point op of the develop
 * pipeline can sit in an adjustment layer (the engine runs every op but the regional ones);
 * these are the ones with one control each. "From current edit" copies the photo's own
 * point ops into a layer instead.
 */
enum class AdjustmentKind(
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
) {
    Exposure("Exposure", -5f, 5f, 1f),
    Contrast("Contrast", -100f, 100f, 30f),
    Brightness("Brightness", -100f, 100f, 30f),
    Vibrance("Vibrance", -100f, 100f, 40f),
    Saturation("Saturation", -100f, 100f, -100f),
    Temperature("Temperature", 2000f, 50000f, 4000f),
    Posterize("Posterize", 2f, 32f, 6f),
    Threshold("Threshold", 0f, 1f, 0.5f),
    Invert("Invert", 0f, 0f, 0f),
    ;

    val hasValue: Boolean get() = this != Invert

    fun op(value: Float): OpParams =
        when (this) {
            Exposure -> OpParams.Exposure(value)
            Contrast -> OpParams.Contrast(value)
            Brightness -> OpParams.Brightness(value)
            Vibrance -> OpParams.Vibrance(value)
            Saturation -> OpParams.Saturation(value)
            Temperature -> OpParams.WhiteBalance(value, 0f)
            Posterize -> OpParams.Posterize(value.roundToInt().toFloat())
            Threshold -> OpParams.Threshold(value)
            Invert -> OpParams.Invert
        }

    /** This kind's value in [ops], or null when they hold no op of this kind. */
    fun valueIn(ops: List<OpParams>): Float? =
        ops.firstNotNullOfOrNull { op ->
            when {
                this == Exposure && op is OpParams.Exposure -> op.ev
                this == Contrast && op is OpParams.Contrast -> op.amount
                this == Brightness && op is OpParams.Brightness -> op.amount
                this == Vibrance && op is OpParams.Vibrance -> op.amount
                this == Saturation && op is OpParams.Saturation -> op.amount
                this == Temperature && op is OpParams.WhiteBalance -> op.temperature
                this == Posterize && op is OpParams.Posterize -> op.levels
                this == Threshold && op is OpParams.Threshold -> op.level
                this == Invert && op is OpParams.Invert -> 0f
                else -> null
            }
        }

    /** [ops] with this kind's op set to [value] (added at the end when missing). */
    fun withValue(
        ops: List<OpParams>,
        value: Float,
    ): List<OpParams> {
        val v = value.coerceIn(min, max)
        val i = ops.indexOfFirst { valueIn(listOf(it)) != null }
        return if (i < 0) ops + op(v) else ops.toMutableList().also { it[i] = op(v) }
    }

    companion object {
        /** The kind of a one-op adjustment layer, or null for anything else ("From current edit"). */
        fun of(ops: List<OpParams>): AdjustmentKind? =
            ops.singleOrNull()?.let { op -> entries.firstOrNull { it.valueIn(listOf(op)) != null } }

        /** The quick controls of a smart object's own edit. */
        val SMART = listOf(Exposure, Contrast, Saturation)

        /** Ops an adjustment layer can hold: the engine runs only those that read their own pixel. */
        fun isPointOp(op: OpParams): Boolean =
            op !is OpParams.HighlightsShadows &&
                op !is OpParams.Geometry &&
                op !is OpParams.Sharpen &&
                op !is OpParams.NoiseReduction &&
                op !is OpParams.Clarity &&
                op !is OpParams.Texture &&
                op !is OpParams.Dehaze &&
                op !is OpParams.PostCropVignette &&
                op !is OpParams.Grain
    }
}
