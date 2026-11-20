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

/** The selection grid's image (Stage 16): [width] x [height] sRGB pixels, RGBA bytes row by row. */
class SelectionImage(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
)

/**
 * A model's mask on the selection grid (Stage 17) and the grid's colour guide: 3 planes of
 * sqrt(rgb), [guide] is width x 3 height. Masks keep both so exports follow the photo's edges.
 */
class UpsampledMask(
    val mask: SelectionRaster,
    val guide: SelectionRaster,
)
