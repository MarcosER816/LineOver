package dev.mark.lineover.core.engine

/**
 * A selection as pixels (Stage 15): [width] x [height] values (0..1, R16F precision, row by
 * row) covering a [docWidth] x [docHeight] document.
 */
class SelectionRaster(
    val width: Int,
    val height: Int,
    val docWidth: Int,
    val docHeight: Int,
    val values: FloatArray,
)
