package dev.mark.lineover.core.model

/** One slider of the Light panel: its value range and default. */
enum class LightParam(
    val toolId: String,
    val label: String,
    val min: Float,
    val max: Float,
    val step: Float,
) {
    // Panel order (Stage 5 brief): Exposure, Contrast, Highlights, Shadows, Whites, Blacks, Brightness.
    Exposure("light.exposure", "Exposure", -5f, 5f, 0.01f),
    Contrast("light.contrast", "Contrast", -100f, 100f, 1f),
    Highlights("light.highlights", "Highlights", -100f, 100f, 1f),
    Shadows("light.shadows", "Shadows", -100f, 100f, 1f),
    Whites("light.whites", "Whites", -100f, 100f, 1f),
    Blacks("light.blacks", "Blacks", -100f, 100f, 1f),
    Brightness("light.brightness", "Brightness", -100f, 100f, 1f),
    ;

    val default: Float get() = 0f

    fun coerce(value: Float): Float = value.coerceIn(min, max)

    companion object {
        fun forTool(id: String): LightParam? = entries.firstOrNull { it.toolId == id }
    }
}

/**
 * The values of the Light sliders. [toOps] is what the engine runs, always in the fixed
 * order Highlights/Shadows → Exposure → Whites → Blacks → Contrast → Brightness (the
 * edge-aware base of Highlights/Shadows is computed from the original preview, so it
 * runs first); sliders at 0 add no op.
 */
data class LightAdjustments(
    val values: Map<LightParam, Float> = emptyMap(),
) {
    operator fun get(param: LightParam): Float = values[param] ?: param.default

    fun with(
        param: LightParam,
        value: Float,
    ): LightAdjustments = copy(values = values + (param to param.coerce(value)))

    val isIdentity: Boolean get() = LightParam.entries.all { this[it] == it.default }

    fun toOps(): List<OpParams> =
        listOf(
            OpParams.HighlightsShadows(this[LightParam.Highlights], this[LightParam.Shadows]),
            OpParams.Exposure(this[LightParam.Exposure]),
            OpParams.Whites(this[LightParam.Whites]),
            OpParams.Blacks(this[LightParam.Blacks]),
            OpParams.Contrast(this[LightParam.Contrast]),
            OpParams.Brightness(this[LightParam.Brightness]),
        ).filterNot { it.isIdentity }

    companion object {
        /** Reads the Light values back from a layer's ops (other ops are ignored). */
        fun fromOps(ops: List<OpParams>): LightAdjustments {
            var light = LightAdjustments()
            for (op in ops) {
                light =
                    when (op) {
                        is OpParams.Exposure -> {
                            light.with(LightParam.Exposure, op.ev)
                        }

                        is OpParams.Contrast -> {
                            light.with(LightParam.Contrast, op.amount)
                        }

                        is OpParams.Brightness -> {
                            light.with(LightParam.Brightness, op.amount)
                        }

                        is OpParams.Whites -> {
                            light.with(LightParam.Whites, op.amount)
                        }

                        is OpParams.Blacks -> {
                            light.with(LightParam.Blacks, op.amount)
                        }

                        is OpParams.HighlightsShadows -> {
                            light.with(LightParam.Highlights, op.highlights).with(LightParam.Shadows, op.shadows)
                        }

                        else -> {
                            light
                        }
                    }
            }
            return light
        }
    }
}
