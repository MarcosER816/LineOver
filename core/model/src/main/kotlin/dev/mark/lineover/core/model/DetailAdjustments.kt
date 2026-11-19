package dev.mark.lineover.core.model

/**
 * One slider of the Detail and Effects tools (Stage 10): the tool it belongs to, its label,
 * range and default. The panel of a tool shows its sliders in this order.
 */
enum class DetailParam(
    val toolId: String,
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float = 0f,
    val step: Float = 1f,
) {
    SharpenAmount(DetailTools.SHARPEN, "Amount", 0f, 150f),
    SharpenRadius(DetailTools.SHARPEN, "Radius", 0.5f, 3f, 1f, 0.1f),
    SharpenDetail(DetailTools.SHARPEN, "Detail", 0f, 100f, 25f),
    SharpenMasking(DetailTools.SHARPEN, "Masking", 0f, 100f),
    NoiseLuminance(DetailTools.NOISE, "Luminance", 0f, 100f),
    NoiseDetail(DetailTools.NOISE, "Detail", 0f, 100f, 50f),
    NoiseContrast(DetailTools.NOISE, "Contrast", 0f, 100f),
    NoiseColor(DetailTools.NOISE, "Color", 0f, 100f),
    Clarity(DetailTools.CLARITY, "Clarity", -100f, 100f),
    Texture(DetailTools.TEXTURE, "Texture", -100f, 100f),
    Dehaze(DetailTools.DEHAZE, "Dehaze", -100f, 100f),
    VignetteAmount(DetailTools.VIGNETTE, "Amount", -100f, 100f),
    VignetteMidpoint(DetailTools.VIGNETTE, "Midpoint", 0f, 100f, 50f),
    VignetteRoundness(DetailTools.VIGNETTE, "Roundness", -100f, 100f),
    VignetteFeather(DetailTools.VIGNETTE, "Feather", 0f, 100f, 50f),
    VignetteHighlights(DetailTools.VIGNETTE, "Highlights", 0f, 100f),
    GrainAmount(DetailTools.GRAIN, "Amount", 0f, 100f),
    GrainSize(DetailTools.GRAIN, "Size", 0f, 100f, 25f),
    GrainRoughness(DetailTools.GRAIN, "Roughness", 0f, 100f, 50f),
    ;

    fun coerce(value: Float): Float = value.coerceIn(min, max)

    /** True when the slider goes both ways around 0 (filled from the default). */
    val signed: Boolean get() = min < 0f

    companion object {
        fun forTool(id: String): List<DetailParam> = entries.filter { it.toolId == id }
    }
}

/** The Stage 10 tool ids (ToolRegistry). */
object DetailTools {
    const val SHARPEN = "detail.sharpen"
    const val NOISE = "detail.noise_reduction"
    const val CLARITY = "effects.clarity"
    const val TEXTURE = "effects.texture"
    const val DEHAZE = "effects.dehaze"
    const val VIGNETTE = "effects.vignette"
    const val GRAIN = "effects.grain"

    val ALL = setOf(SHARPEN, NOISE, CLARITY, TEXTURE, DEHAZE, VIGNETTE, GRAIN)

    /** The tools whose result is judged at 100 % (the canvas offers a 1:1 toggle). */
    val DETAIL = setOf(SHARPEN, NOISE, TEXTURE)
}

/**
 * The values of the Detail and Effects sliders. In [editOps] order: [sourceOps] (Noise
 * reduction, Clarity, Texture, Dehaze) run first after the geometry, on the image as decoded;
 * [sharpenOps] last in source space; [finishingOps] (post-crop Vignette, Grain) on the output.
 * A tool whose main slider is at its neutral value adds no op.
 */
data class DetailAdjustments(
    val values: Map<DetailParam, Float> = emptyMap(),
) {
    operator fun get(param: DetailParam): Float = values[param] ?: param.default

    /** Values at their default are not stored, so equal settings compare equal. */
    fun with(
        param: DetailParam,
        value: Float,
    ): DetailAdjustments {
        val v = param.coerce(value)
        return copy(values = if (v == param.default) values - param else values + (param to v))
    }

    val isIdentity: Boolean get() = (sourceOps() + sharpenOps() + finishingOps()).isEmpty()

    fun sourceOps(): List<OpParams> =
        listOf(
            OpParams.NoiseReduction(
                this[DetailParam.NoiseLuminance],
                this[DetailParam.NoiseDetail],
                this[DetailParam.NoiseContrast],
                this[DetailParam.NoiseColor],
            ),
            OpParams.Clarity(this[DetailParam.Clarity]),
            OpParams.Texture(this[DetailParam.Texture]),
            OpParams.Dehaze(this[DetailParam.Dehaze]),
        ).filterNot { it.isIdentity }

    /** Sharpen; [showMask] shows its Masking mask in grey (the canvas only). */
    fun sharpenOps(showMask: Boolean = false): List<OpParams> =
        listOf(
            OpParams.Sharpen(
                this[DetailParam.SharpenAmount],
                this[DetailParam.SharpenRadius],
                this[DetailParam.SharpenDetail],
                this[DetailParam.SharpenMasking],
                if (showMask) 1f else 0f,
            ),
        ).filterNot { it.isIdentity }

    fun finishingOps(): List<OpParams> =
        listOf(
            OpParams.PostCropVignette(
                this[DetailParam.VignetteAmount],
                this[DetailParam.VignetteMidpoint],
                this[DetailParam.VignetteRoundness],
                this[DetailParam.VignetteFeather],
                this[DetailParam.VignetteHighlights],
            ),
            OpParams.Grain(this[DetailParam.GrainAmount], this[DetailParam.GrainSize], this[DetailParam.GrainRoughness]),
        ).filterNot { it.isIdentity }

    companion object {
        /** Reads the values back from a layer's ops (other ops are ignored). */
        fun fromOps(ops: List<OpParams>): DetailAdjustments {
            val v = mutableMapOf<DetailParam, Float>()
            for (op in ops) {
                when (op) {
                    is OpParams.Sharpen -> {
                        v[DetailParam.SharpenAmount] = op.amount
                        v[DetailParam.SharpenRadius] = op.radius
                        v[DetailParam.SharpenDetail] = op.detail
                        v[DetailParam.SharpenMasking] = op.masking
                    }

                    is OpParams.NoiseReduction -> {
                        v[DetailParam.NoiseLuminance] = op.luminance
                        v[DetailParam.NoiseDetail] = op.detail
                        v[DetailParam.NoiseContrast] = op.contrast
                        v[DetailParam.NoiseColor] = op.color
                    }

                    is OpParams.Clarity -> {
                        v[DetailParam.Clarity] = op.amount
                    }

                    is OpParams.Texture -> {
                        v[DetailParam.Texture] = op.amount
                    }

                    is OpParams.Dehaze -> {
                        v[DetailParam.Dehaze] = op.amount
                    }

                    is OpParams.PostCropVignette -> {
                        v[DetailParam.VignetteAmount] = op.amount
                        v[DetailParam.VignetteMidpoint] = op.midpoint
                        v[DetailParam.VignetteRoundness] = op.roundness
                        v[DetailParam.VignetteFeather] = op.feather
                        v[DetailParam.VignetteHighlights] = op.highlights
                    }

                    is OpParams.Grain -> {
                        v[DetailParam.GrainAmount] = op.amount
                        v[DetailParam.GrainSize] = op.size
                        v[DetailParam.GrainRoughness] = op.roughness
                    }

                    else -> {
                        Unit
                    }
                }
            }
            return v.entries.fold(DetailAdjustments()) { d, (param, value) -> d.with(param, value) }
        }
    }
}
