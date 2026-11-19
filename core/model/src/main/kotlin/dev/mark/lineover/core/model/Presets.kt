package dev.mark.lineover.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

/**
 * A preset (Stage 11): a partial edit graph — the background ops it sets, nothing else.
 * Applying it replaces those ops (by kind) and keeps the rest of the photo's edit.
 */
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val group: String,
    val graph: EditGraph,
    val builtIn: Boolean = false,
) {
    val ops: List<OpParams>
        get() =
            graph.layers
                .firstOrNull()
                ?.ops
                .orEmpty()

    companion object {
        fun of(
            id: String,
            name: String,
            group: String,
            ops: List<OpParams>,
            builtIn: Boolean = false,
        ) = Preset(id, name, group, EditGraph().withBackgroundOps(ops), builtIn)
    }
}

/**
 * Applying a preset with an Amount (0..200 %): every value the preset sets moves from what
 * the photo has (or the neutral value when it has none) towards the preset's, by Amount;
 * 100 % is the preset, 200 % goes as far again. Values that cannot be blended (a LUT's id, a
 * black & white mix, split points) are the preset's once Amount is above 0.
 */
object PresetMath {
    const val MAX_AMOUNT = 2f
    private const val MIN_CURVE_FLOATS = 4 // two points

    fun apply(
        base: List<OpParams>,
        preset: List<OpParams>,
        amount: Float,
    ): List<OpParams> {
        val a = amount.coerceIn(0f, MAX_AMOUNT)
        if (a == 0f) return base
        val blended =
            preset.map { p ->
                val b = base.firstOrNull { it::class == p::class }
                blend(b, p, a)
            }
        return base.filterNot { b -> preset.any { it::class == b::class } } + blended
    }

    /** One op: [base] (null: neutral) towards [preset] by [a]. */
    fun blend(
        base: OpParams?,
        preset: OpParams,
        a: Float,
    ): OpParams {
        if (preset is OpParams.Curves) return blendCurves(base as? OpParams.Curves, preset, a)
        val p = EditGraphJson.opObject(preset)
        // Ops without blendable fields (a black & white mix, levels…) are the preset's.
        val fields = NEUTRAL[(p["op"] as? JsonPrimitive)?.content] ?: return preset
        val b = base?.let { EditGraphJson.opObject(it) }
        val out =
            p.mapValues { (key, value) ->
                val neutral = fields[key] ?: return@mapValues value
                val from = b?.get(key) ?: neutral.of(value)
                lerp(from, value, a, keepFirst = neutral is Neutral.Wheel)
            }
        return EditGraphJson.opFromObject(JsonObject(out))
    }

    private fun lerp(
        from: JsonElement,
        to: JsonElement,
        a: Float,
        keepFirst: Boolean,
    ): JsonElement =
        when {
            from is JsonPrimitive && to is JsonPrimitive -> {
                val f = from.floatOrNull
                val t = to.floatOrNull
                if (f == null || t == null) to else JsonPrimitive(f + a * (t - f))
            }

            from is JsonArray && to is JsonArray && from.size == to.size -> {
                JsonArray(
                    to.indices.map { i ->
                        // A grading wheel's hue is the preset's (hues do not blend linearly).
                        if (keepFirst && i == 0) to[i] else lerp(from[i], to[i], a, keepFirst = false)
                    },
                )
            }

            else -> {
                to
            }
        }

    /** Curves: each preset point's y moves from the base curve's value at that x. */
    private fun blendCurves(
        base: OpParams.Curves?,
        preset: OpParams.Curves,
        a: Float,
    ): OpParams.Curves {
        fun channel(
            p: List<Float>,
            b: List<Float>?,
        ): List<Float> {
            if (p.isEmpty()) return b.orEmpty()
            val curve = b?.takeIf { it.size >= MIN_CURVE_FLOATS }?.let { MonotoneCurve(MonotoneCurve.points(it)) }
            return MonotoneCurve.points(p).flatMap { pt ->
                val from = curve?.invoke(pt.x) ?: pt.x
                listOf(pt.x, (from + a * (pt.y - from)).coerceIn(0f, 1f))
            }
        }
        return preset.copy(
            rgb = channel(preset.rgb, base?.rgb),
            red = channel(preset.red, base?.red),
            green = channel(preset.green, base?.green),
            blue = channel(preset.blue, base?.blue),
            luma = channel(preset.luma, base?.luma),
        )
    }

    /** What a blendable field is when the photo has no such op. */
    private sealed interface Neutral {
        fun of(preset: JsonElement): JsonElement

        data class Value(
            val v: Float,
        ) : Neutral {
            override fun of(preset: JsonElement) = JsonPrimitive(v)
        }

        /** Zeros of the preset array's length. */
        data object Zeros : Neutral {
            override fun of(preset: JsonElement) = JsonArray(List((preset as? JsonArray)?.size ?: 0) { JsonPrimitive(0f) })
        }

        /** A grading wheel (hue, saturation, luminance): the preset's hue, nothing else. */
        data object Wheel : Neutral {
            override fun of(preset: JsonElement): JsonElement {
                val hue = (preset as? JsonArray)?.firstOrNull() ?: JsonPrimitive(0f)
                return JsonArray(listOf(hue, JsonPrimitive(0f), JsonPrimitive(0f)))
            }
        }
    }

    private val ZERO = Neutral.Value(0f)

    private val NEUTRAL: Map<String, Map<String, Neutral>> =
        mapOf(
            "exposure" to mapOf("ev" to ZERO),
            "contrast" to mapOf("amount" to ZERO),
            "brightness" to mapOf("amount" to ZERO),
            "whites" to mapOf("amount" to ZERO),
            "blacks" to mapOf("amount" to ZERO),
            "highlights_shadows" to mapOf("highlights" to ZERO, "shadows" to ZERO),
            "white_balance" to mapOf("temperature" to Neutral.Value(ColorAdjustments.DEFAULT_KELVIN), "tint" to ZERO),
            "vibrance" to mapOf("amount" to ZERO),
            "saturation" to mapOf("amount" to ZERO),
            "hsl_mixer" to mapOf("hue" to Neutral.Zeros, "saturation" to Neutral.Zeros, "luminance" to Neutral.Zeros),
            "parametric_curve" to mapOf("highlights" to ZERO, "lights" to ZERO, "darks" to ZERO, "shadows" to ZERO),
            "color_grading" to
                mapOf(
                    "balance" to ZERO,
                    "shadows" to Neutral.Wheel,
                    "midtones" to Neutral.Wheel,
                    "highlights" to Neutral.Wheel,
                    "global" to Neutral.Wheel,
                ),
            "color_balance" to mapOf("shadows" to Neutral.Zeros, "midtones" to Neutral.Zeros, "highlights" to Neutral.Zeros),
            "photo_filter" to mapOf("density" to ZERO),
            "calibration" to mapOf("hue" to Neutral.Zeros, "saturation" to Neutral.Zeros),
            "clarity" to mapOf("amount" to ZERO),
            "texture" to mapOf("amount" to ZERO),
            "dehaze" to mapOf("amount" to ZERO),
            "post_crop_vignette" to mapOf("amount" to ZERO),
            "grain" to mapOf("amount" to ZERO),
            "sharpen" to mapOf("amount" to ZERO),
            "noise_reduction" to mapOf("luminance" to ZERO, "contrast" to ZERO, "color" to ZERO),
            "profile" to mapOf("amount" to ZERO),
            "lut" to mapOf("amount" to ZERO),
        )
}
