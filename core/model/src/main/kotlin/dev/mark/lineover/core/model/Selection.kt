package dev.mark.lineover.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** How a new selection combines with the current one (Stage 15). */
@Serializable
enum class SelectionMode(
    val label: String,
) {
    @SerialName("new")
    New("New"),

    @SerialName("add")
    Add("Add"),

    @SerialName("subtract")
    Subtract("Subtract"),

    @SerialName("intersect")
    Intersect("Intersect"),
}

/**
 * The selection (Stage 15): a recipe of operations in document pixels that the engine evaluates
 * into a soft mask (R16F) at the canvas's resolution (selection.h). It is part of the edit, so
 * every change is a History step, and a mask made from it is saved as pixels (MaskPaint.Raster).
 */
@Serializable
data class Selection(
    val ops: List<SelectionOp> = emptyList(),
) {
    /** [op] after the current ops; a new shape in mode New starts the recipe again. */
    fun with(op: SelectionOp): Selection = copy(ops = if ((op as? SelectionOp.Shape)?.mode == SelectionMode.New) listOf(op) else ops + op)

    /** The last operation replaced (a shape that grows under the finger, a slider on the wand). */
    fun withLast(op: SelectionOp): Selection = copy(ops = ops.dropLast(1) + op)
}

/** One selection operation. Serialised with a "kind" (shared with the engine's parseSelection). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface SelectionOp {
    /** Operations that make a shape and combine it with the selection by [mode]. */
    sealed interface Shape : SelectionOp {
        val mode: SelectionMode

        fun withMode(mode: SelectionMode): Shape
    }

    @Serializable
    @SerialName("rect")
    data class Rect(
        override val mode: SelectionMode,
        val antiAlias: Boolean,
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
    ) : Shape {
        override fun withMode(mode: SelectionMode) = copy(mode = mode)

        override val label: String get() = "Rectangle selection"
    }

    @Serializable
    @SerialName("ellipse")
    data class Ellipse(
        override val mode: SelectionMode,
        val antiAlias: Boolean,
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
    ) : Shape {
        override fun withMode(mode: SelectionMode) = copy(mode = mode)

        override val label: String get() = "Ellipse selection"
    }

    /** Freehand: the finger's path, closed. */
    @Serializable
    @SerialName("lasso")
    data class Lasso(
        override val mode: SelectionMode,
        val antiAlias: Boolean,
        val points: List<Float>,
    ) : Shape {
        override fun withMode(mode: SelectionMode) = copy(mode = mode)

        override val label: String get() = "Lasso"
    }

    /** Straight sides between tapped points, closed. */
    @Serializable
    @SerialName("polygon")
    data class Polygon(
        override val mode: SelectionMode,
        val antiAlias: Boolean,
        val points: List<Float>,
    ) : Shape {
        override fun withMode(mode: SelectionMode) = copy(mode = mode)

        override val label: String get() = "Polygonal lasso"
    }

    /** Pixels within [tolerance] (OKLab delta E) of the one at (x, y); [contiguous]: connected to it. */
    @Serializable
    @SerialName("wand")
    data class Wand(
        override val mode: SelectionMode,
        val x: Float,
        val y: Float,
        val tolerance: Float,
        val contiguous: Boolean,
        val allLayers: Boolean,
    ) : Shape {
        override fun withMode(mode: SelectionMode) = copy(mode = mode)

        override val label: String get() = "Magic wand"
    }

    /** Colours near the [add] samples (x, y, ...) and away from the [subtract] ones, softly within [fuzziness]. */
    @Serializable
    @SerialName("color_range")
    data class ColorRange(
        override val mode: SelectionMode,
        val fuzziness: Float,
        val add: List<Float>,
        val subtract: List<Float>,
    ) : Shape {
        override fun withMode(mode: SelectionMode) = copy(mode = mode)

        override val label: String get() = "Color range"
    }

    /** Magnetic lasso (Stage 16): the live-wire path that snapped to edges, closed like a lasso. */
    @Serializable
    @SerialName("magnetic")
    data class Magnetic(
        override val mode: SelectionMode,
        val antiAlias: Boolean,
        val points: List<Float>,
    ) : Shape {
        override fun withMode(mode: SelectionMode) = copy(mode = mode)

        override val label: String get() = "Magnetic lasso"
    }

    /** Add / subtract brush (Stage 16): a round brush stroke of [radius] document px. */
    @Serializable
    @SerialName("brush")
    data class Brush(
        override val mode: SelectionMode,
        val radius: Float,
        val hardness: Float,
        val points: List<Float>,
    ) : Shape {
        override fun withMode(mode: SelectionMode) = copy(mode = mode)

        fun plus(
            x: Float,
            y: Float,
        ): Brush = copy(points = points + x + y)

        override val label: String get() = if (mode == SelectionMode.Subtract) "Subtract brush" else "Add brush"
    }

    /**
     * A model's mask (Stage 16: Select subject / sky, Object select), saved as pixels ([source],
     * [width] x [height], covering the document) like a selection made into a mask. The engine
     * brings it to the canvas resolution by joint bilateral upsampling with the image.
     */
    @Serializable
    @SerialName("raster")
    data class Raster(
        override val mode: SelectionMode,
        val source: String,
        val width: Float,
        val height: Float,
        val tool: String,
    ) : Shape {
        override fun withMode(mode: SelectionMode) = copy(mode = mode)

        override val label: String
            get() =
                when (tool) {
                    TOOL_SUBJECT -> "Select subject"
                    TOOL_SKY -> "Select sky"
                    else -> "Object select"
                }

        companion object {
            const val TOOL_SUBJECT = "subject"
            const val TOOL_SKY = "sky"
            const val TOOL_OBJECT = "object"
        }
    }

    /** Quick selection (Stage 16): a brush stroke that GrabCut grows to the object's edges. */
    @Serializable
    @SerialName("quick")
    data class Quick(
        override val mode: SelectionMode,
        val radius: Float,
        val points: List<Float>,
    ) : Shape {
        override fun withMode(mode: SelectionMode) = copy(mode = mode)

        fun plus(
            x: Float,
            y: Float,
        ): Quick = copy(points = points + x + y)

        override val label: String get() = "Quick selection"
    }

    /** Refine edge (Stage 16): guided-filter matting of [px] document px, then [contrast] (0..100). */
    @Serializable
    @SerialName("refine")
    data class Refine(
        val px: Float,
        val contrast: Float,
    ) : SelectionOp {
        override val label: String get() = "Refine edge"
    }

    @Serializable
    @SerialName("all")
    data object All : SelectionOp {
        override val label: String get() = "Select all"
    }

    @Serializable
    @SerialName("invert")
    data object Invert : SelectionOp {
        override val label: String get() = "Invert selection"
    }

    /** [px] > 0 grows, < 0 shrinks (an exact distance transform). */
    @Serializable
    @SerialName("grow")
    data class Grow(
        val px: Float,
    ) : SelectionOp {
        override val label: String get() = if (px >= 0f) "Grow selection ${px.toInt()} px" else "Shrink selection ${(-px).toInt()} px"
    }

    @Serializable
    @SerialName("feather")
    data class Feather(
        val px: Float,
    ) : SelectionOp {
        override val label: String get() = "Feather ${px.toInt()} px"
    }

    @Serializable
    @SerialName("smooth")
    data class Smooth(
        val px: Float,
    ) : SelectionOp {
        override val label: String get() = "Smooth selection ${px.toInt()} px"
    }

    @Serializable
    @SerialName("border")
    data class Border(
        val px: Float,
    ) : SelectionOp {
        override val label: String get() = "Border ${px.toInt()} px"
    }

    /** The History name of the operation. */
    val label: String

    companion object {
        /** The History name of [op]. */
        fun label(op: SelectionOp?): String = op?.label ?: "Selection"
    }
}
