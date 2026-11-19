package dev.mark.lineover.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

/** History labels of the geometry op (Stage 9), for [HistoryLabels]. */
internal object GeometryLabels {
    private const val GEOMETRY = "geometry"

    /**
     * The geometry op is named by its panel values (`settings`), not by the composed matrix
     * and size: a drag of one control changes the same field every frame and coalesces.
     */
    fun changes(
        before: JsonObject?,
        after: JsonObject?,
    ): List<GraphChange> {
        val neutral = EditGraphJson.format.encodeToJsonElement(GeometryAdjustments.serializer(), GeometryAdjustments()) as JsonObject
        val old = before?.get("settings") as? JsonObject ?: neutral
        val new = after?.get("settings") as? JsonObject ?: neutral
        val changed = (old.keys + new.keys).filter { old[it] != new[it] }.sorted()
        if (changed.isEmpty()) return listOf(GraphChange(HistoryLabels.name(GEOMETRY), GEOMETRY))
        val key = changed.joinToString(",") { "$GEOMETRY.$it" }
        val label =
            when {
                changed.all { it.startsWith("crop") } -> "Crop"
                changed.all { it.startsWith("upright") } -> "Upright ${(new["upright"] as? JsonPrimitive)?.content.orEmpty()}".trim()
                changed.size > 1 -> HistoryLabels.name(GEOMETRY)
                else -> geometryLabel(changed.single(), new[changed.single()])
            }
        return listOf(GraphChange(label, key))
    }

    private fun geometryLabel(
        field: String,
        value: JsonElement?,
    ): String {
        val title = GEOMETRY_TITLES[field] ?: field.replaceFirstChar { it.uppercase() }
        val primitive = value as? JsonPrimitive ?: return title
        val number = primitive.floatOrNull
        return when {
            field in GEOMETRY_PLAIN -> title
            number != null -> "$title ${HistoryLabels.format(GEOMETRY, field, number)}"
            primitive.isString -> "$title ${primitive.content}"
            else -> title
        }
    }

    private val GEOMETRY_TITLES =
        mapOf(
            "straighten" to "Straighten",
            "quarterTurns" to "Rotate 90°",
            "flipHorizontal" to "Flip horizontal",
            "flipVertical" to "Flip vertical",
            "aspect" to "Aspect ratio",
            "customWidth" to "Custom ratio",
            "customHeight" to "Custom ratio",
            "constrain" to "Constrain crop",
            "vertical" to "Vertical",
            "horizontal" to "Horizontal",
            "rotate" to "Rotate",
            "aspectAmount" to "Aspect",
            "scale" to "Scale",
            "offsetX" to "X offset",
            "offsetY" to "Y offset",
            "distortion" to "Distortion",
            "distortionEdge" to "Edge distortion",
            "chromaticRed" to "Red / cyan fringe",
            "chromaticBlue" to "Blue / yellow fringe",
            "vignetting" to "Lens vignetting",
            "vignettingMidpoint" to "Vignetting midpoint",
            "profile" to "Lens profile",
            "profileDistortion" to "Profile distortion",
            "profileVignetting" to "Profile vignetting",
            "profileChromatic" to "Profile chromatic aberration",
            "perspectiveCorners" to "Perspective crop",
            "guides" to "Guided Upright",
        )

    /** Geometry fields named without their value (switches, lists, ratios). */
    private val GEOMETRY_PLAIN =
        setOf(
            "quarterTurns",
            "flipHorizontal",
            "flipVertical",
            "constrain",
            "customWidth",
            "customHeight",
            "perspectiveCorners",
            "guides",
            "profile",
            "profileChromatic",
        )
}
