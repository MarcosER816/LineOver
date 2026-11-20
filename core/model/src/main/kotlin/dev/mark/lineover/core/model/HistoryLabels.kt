package dev.mark.lineover.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/** What changed between two graphs: a History row's label ("Exposure +0.7") and its coalescing key. */
data class GraphChange(
    val label: String,
    /** The changed controls ("exposure.ev"): a drag of the same control coalesces into one step. */
    val key: String,
)

/** Names the difference between two graphs for the History list. */
object HistoryLabels {
    /** The change from [before] to [after] (an empty key when they are equal). */
    fun describe(
        before: EditGraph,
        after: EditGraph,
    ): GraphChange {
        if (before == after) return GraphChange("No change", "")
        // Layers above the photo (Stage 13): added, removed or moved ones are named by the
        // action that did it; changes of one layer are named after it.
        val layersMoved = LayerMath.flatten(before).map { it.first.id } != LayerMath.flatten(after).map { it.first.id }
        // A layer missing on one side has no ops (the background appears with the first edit).
        val ids =
            (before.layers.map { it.id } + after.layers.map { it.id })
                .distinct()
                .filter { id -> (before.layers + after.layers).first { it.id == id }.isBackground }
        val changes =
            if (layersMoved) {
                emptyList()
            } else {
                layerChanges(before, after)
            } +
                ids.flatMap { id ->
                    opChanges(
                        before.layers
                            .firstOrNull { it.id == id }
                            ?.ops
                            .orEmpty(),
                        after.layers
                            .firstOrNull { it.id == id }
                            ?.ops
                            .orEmpty(),
                    )
                }
        return when {
            layersMoved || (changes.isEmpty() && before.layers.map { it.id } != after.layers.map { it.id }) -> {
                GraphChange("Layers", "layers")
            }

            changes.isEmpty() && before.blendSpace != after.blendSpace -> {
                GraphChange("Blend space: ${if (after.blendSpace == BlendSpace.Linear) "linear" else "gamma"}", "blend_space")
            }

            changes.isEmpty() -> {
                GraphChange("Edit", "order")
            }

            changes.size == 1 -> {
                GraphChange(changes.single().label, changes.single().key)
            }

            else -> {
                val label =
                    if (changes.map { it.op }.distinct().size ==
                        1
                    ) {
                        name(changes.first().op)
                    } else {
                        "${changes.first().label} + ${changes.size - 1} more"
                    }
                GraphChange(label, changes.joinToString(",") { it.key })
            }
        }
    }

    /** Property and op changes of each layer above the photo (same layers on both sides). */
    private fun layerChanges(
        before: EditGraph,
        after: EditGraph,
    ): List<OpChange> {
        val old = LayerMath.flatten(before).associate { it.first.id to it.first }
        return LayerMath.flatten(after).map { it.first }.flatMap { b ->
            val a = old[b.id]
            if (a == null || a.copy(children = emptyList()) == b.copy(children = emptyList())) return@flatMap emptyList()
            val name = b.name.ifBlank { b.type.label }
            val key = "layer.${b.id}"
            val ops =
                if (a.ops !=
                    b.ops
                ) {
                    opChanges(a.ops, b.ops).map { c -> OpChange(key, "$key.${c.key}", "$name: ${c.label}") }
                } else {
                    emptyList()
                }
            (propertyChanges(a, b, name, key) + ops).ifEmpty { listOf(OpChange(key, key, name)) }
        }
    }

    private fun opChanges(
        before: List<OpParams>,
        after: List<OpParams>,
    ): List<OpChange> {
        val old = before.associate { op -> EditGraphJson.opObject(op).let { it.name() to it } }
        val new = after.associate { op -> EditGraphJson.opObject(op).let { it.name() to it } }
        return (old.keys + new.keys).distinct().flatMap { name ->
            val a = old[name]
            val b = new[name]
            when {
                a == b -> emptyList()
                name == GEOMETRY -> GeometryLabels.changes(a, b).map { OpChange(GEOMETRY, it.key, it.label) }
                else -> fieldChanges(name, a, b)
            }
        }
    }

    private fun fieldChanges(
        op: String,
        before: JsonObject?,
        after: JsonObject?,
    ): List<OpChange> {
        val fields = ((before?.keys ?: emptySet()) + (after?.keys ?: emptySet())) - "op"
        // A missing op counts as neutral: adding Highlights / Shadows with Highlights -40
        // changed Highlights only.
        val changed = fields.filter { field(before, it) != field(after, it) }
        if (changed.isEmpty()) {
            // An op without fields (Invert) switched on or off.
            return listOf(OpChange(op, op, if (after != null) name(op) else "${name(op)} off"))
        }
        if (changed.size > 1) {
            // Several controls of one op at once (an Auto, a reset, a wheel's hue and amount).
            val label = if (after == null) "${name(op)} reset" else name(op)
            return listOf(OpChange(op, changed.sorted().joinToString(",") { "$op.$it" }, label))
        }
        val field = changed.single()
        val value = (after?.get(field) as? JsonPrimitive)?.floatOrNull
        val title = fieldTitle(op, field)
        val label =
            when {
                after?.get(field) is JsonArray || before?.get(field) is JsonArray -> title
                after == null -> "$title ${format(op, field, neutral(field))}"
                value != null -> "$title ${format(op, field, value)}"
                else -> title
            }
        return listOf(OpChange(op, "$op.$field", label))
    }

    private fun field(
        op: JsonObject?,
        name: String,
    ): JsonElement? = if (op == null) JsonPrimitive(neutral(name)) else op[name]

    /** A scalar op removed from the graph went back to its neutral value. */
    private fun neutral(field: String): Float = if (field == "temperature") ColorAdjustments.DEFAULT_KELVIN else 0f

    private fun JsonObject.name(): String = (get("op") as? JsonPrimitive)?.content ?: "op"

    internal fun format(
        op: String,
        field: String,
        value: Float,
    ): String {
        val unsigned = field in UNSIGNED || op in UNSIGNED_OPS
        val text =
            when {
                field == "temperature" -> "${value.roundToInt()} K"
                abs(value) < FRACTION_BELOW && value != value.roundToInt().toFloat() -> "%.1f".format(java.util.Locale.ROOT, value)
                else -> value.roundToInt().toString()
            }
        return if (!unsigned && value > 0f) "+$text" else text
    }

    /** "Exposure", or the field's own name where the op has several ("Highlights", "Tint"). */
    private fun fieldTitle(
        op: String,
        field: String,
    ): String =
        when {
            op in FIELD_TITLED -> field.replaceFirstChar { it.uppercase() }
            NAMES[op] != null && SINGLE_FIELD.contains(op) -> name(op)
            else -> "${name(op)} · ${field.replaceFirstChar { it.uppercase() }}"
        }

    fun name(op: String): String = NAMES[op] ?: op.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private const val FRACTION_BELOW = 10f
    private const val GEOMETRY = "geometry"

    private val NAMES =
        mapOf(
            "identity" to "Identity",
            "exposure_test" to "Exposure test",
            "exposure" to "Exposure",
            "contrast" to "Contrast",
            "brightness" to "Brightness",
            "whites" to "Whites",
            "blacks" to "Blacks",
            "highlights_shadows" to "Highlights / Shadows",
            "white_balance" to "White balance",
            "vibrance" to "Vibrance",
            "saturation" to "Saturation",
            "hsl_mixer" to "HSL / Color",
            "invert" to "Invert",
            "posterize" to "Posterize",
            "threshold" to "Threshold",
            "curves" to "Curves",
            "parametric_curve" to "Parametric curve",
            "levels" to "Levels",
            "color_grading" to "Color grading",
            "color_balance" to "Color balance",
            "selective_color" to "Selective color",
            "channel_mixer" to "Channel mixer",
            "black_and_white" to "Black & white",
            "photo_filter" to "Photo filter",
            "gradient_map" to "Gradient map",
            "calibration" to "Calibration",
            "geometry" to "Geometry",
            "sharpen" to "Sharpen",
            "noise_reduction" to "Noise reduction",
            "clarity" to "Clarity",
            "texture" to "Texture",
            "dehaze" to "Dehaze",
            "post_crop_vignette" to "Vignette",
            "grain" to "Grain",
            "profile" to "Profile",
            "lut" to "3D LUT",
        )

    /** Ops with one meaningful value: the label is the op's name. */
    private val SINGLE_FIELD =
        setOf(
            "exposure_test",
            "exposure",
            "contrast",
            "brightness",
            "whites",
            "blacks",
            "vibrance",
            "saturation",
            "posterize",
            "threshold",
            "clarity",
            "texture",
            "dehaze",
        )

    /** Ops whose fields read well alone: "Highlights -40", "Temperature 5600 K". */
    private val FIELD_TITLED = setOf("highlights_shadows", "white_balance")

    private val UNSIGNED = setOf("temperature", "levels", "level", "density", "hue", "scale", "vignettingMidpoint")
    private val UNSIGNED_OPS = setOf("posterize", "threshold", "sharpen", "noise_reduction", "grain", "profile", "lut")
}

/** [value] when true, else null (a list of optional changes). */
private inline fun <T> Boolean.then(value: () -> T): T? = if (this) value() else null

private class OpChange(
    val op: String,
    val key: String,
    val label: String,
)

/** What changed of one layer's own settings (opacity, blend, ...), in the order the panel shows them. */
private fun propertyChanges(
    a: Layer,
    b: Layer,
    name: String,
    key: String,
): List<OpChange> =
    listOfNotNull(
        (a.opacity != b.opacity).then { OpChange(key, "$key.opacity", "$name: Opacity ${b.opacity.roundToInt()} %") },
        (a.fill != b.fill).then { OpChange(key, "$key.fill", "$name: Fill ${b.fill.roundToInt()} %") },
        (a.blend != b.blend).then { OpChange(key, "$key.blend", "$name: ${b.blend.label}") },
        (a.visible != b.visible).then { OpChange(key, "$key.visible", "${if (b.visible) "Show" else "Hide"} $name") },
        (a.locked != b.locked).then { OpChange(key, "$key.locked", "${if (b.locked) "Lock" else "Unlock"} $name") },
        (a.isolated != b.isolated).then {
            OpChange(key, "$key.isolated", "$name: ${if (b.isolated) "Isolated" else "Pass through"}")
        },
        (a.transform != b.transform).then { OpChange(key, "$key.transform", "$name: Place") },
        (a.name != b.name).then { OpChange(key, "$key.name", "Rename $name") },
    )
