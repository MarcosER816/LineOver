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
    }

    @Serializable
    @SerialName("all")
    data object All : SelectionOp

    @Serializable
    @SerialName("invert")
    data object Invert : SelectionOp

    /** [px] > 0 grows, < 0 shrinks (an exact distance transform). */
    @Serializable
    @SerialName("grow")
    data class Grow(
        val px: Float,
    ) : SelectionOp

    @Serializable
    @SerialName("feather")
    data class Feather(
        val px: Float,
    ) : SelectionOp

    @Serializable
    @SerialName("smooth")
    data class Smooth(
        val px: Float,
    ) : SelectionOp

    @Serializable
    @SerialName("border")
    data class Border(
        val px: Float,
    ) : SelectionOp

    companion object {
        /** The History name of [op]. */
        fun label(op: SelectionOp?): String =
            when (op) {
                is Rect -> "Rectangle selection"
                is Ellipse -> "Ellipse selection"
                is Lasso -> "Lasso"
                is Polygon -> "Polygonal lasso"
                is Wand -> "Magic wand"
                is ColorRange -> "Color range"
                All -> "Select all"
                Invert -> "Invert selection"
                is Grow -> if (op.px >= 0f) "Grow selection ${op.px.toInt()} px" else "Shrink selection ${(-op.px).toInt()} px"
                is Feather -> "Feather ${op.px.toInt()} px"
                is Smooth -> "Smooth selection ${op.px.toInt()} px"
                is Border -> "Border ${op.px.toInt()} px"
                null -> "Selection"
            }
    }
}
