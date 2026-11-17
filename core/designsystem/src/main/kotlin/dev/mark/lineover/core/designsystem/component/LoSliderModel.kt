package dev.mark.lineover.core.designsystem.component

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sign

/**
 * The value math behind [LoSlider], free of Compose so it can be unit-tested.
 *
 * @param min lowest value
 * @param max highest value
 * @param step quantisation step; 0 means continuous (taps then move 1 % of the range)
 * @param default value restored by a double tap; the haptic detent sits here
 * @param detentFraction half-width of the sticky zone around [default], as a fraction of the range
 */
class LoSliderModel(
    val min: Float,
    val max: Float,
    val step: Float,
    val default: Float,
    val detentFraction: Float = DEFAULT_DETENT_FRACTION,
) {
    init {
        require(max > min) { "max must be greater than min" }
        require(step >= 0f) { "step must not be negative" }
        require(default in min..max) { "default must lie within min..max" }
    }

    val span: Float get() = max - min

    /** Step used by taps: [step], or 1 % of the range for a continuous slider. */
    val tapStep: Float get() = if (step > 0f) step else span / CONTINUOUS_TAP_DIVISIONS

    /** Clamps to the range and rounds to the nearest step, counted from [min]. */
    fun coerce(value: Float): Float {
        val clamped = value.coerceIn(min, max)
        if (step <= 0f) return clamped
        // Work in double precision on the decimal values of min and step: the float 0.01f
        // is really 0.0099999998, and 623 of those would give 1.2299999 instead of 1.23.
        val base = min.toString().toDouble()
        val unit = step.toString().toDouble()
        val gridPoint = (base + ((clamped - base) / unit).roundToLong() * unit).toFloat().coerceIn(min, max)
        // max stays reachable even when the range is not a whole number of steps.
        return if (max - clamped < abs(clamped - gridPoint)) max else gridPoint
    }

    /** Position of [value] along the track, 0..1. */
    fun fraction(value: Float): Float = (coerce(value) - min) / span

    /** Value at track position [fraction] (0..1). */
    fun valueAt(fraction: Float): Float = coerce(min + fraction.coerceIn(0f, 1f) * span)

    /** One tap step towards [direction] (-1 = left, +1 = right). */
    fun stepBy(
        value: Float,
        direction: Int,
    ): Float = coerce(value + direction.sign * tapStep)

    /** The value a double tap resets to. */
    fun reset(): Float = default

    /**
     * Drag speed for a finger [verticalOffsetPx] below the slider's centre: full speed,
     * or a quarter in fine mode, entered by moving the finger at least
     * [FINE_MODE_DISTANCE_ROWS] row heights below the slider.
     */
    fun speedFactor(
        verticalOffsetPx: Float,
        rowHeightPx: Float,
    ): Float = if (verticalOffsetPx >= FINE_MODE_DISTANCE_ROWS * rowHeightPx) FINE_MODE_SPEED else 1f

    /** Starts a relative drag at [startValue]. */
    fun startDrag(startValue: Float): Drag = Drag(startValue)

    /**
     * One drag gesture. The thumb moves by the finger's horizontal movement (relative
     * drag, it never jumps to the finger), so fine mode can slow it down. Sub-step
     * movements accumulate instead of being lost to rounding.
     */
    inner class Drag internal constructor(
        startValue: Float,
    ) {
        private var raw: Float = coerce(startValue)

        /** Current value of the drag. */
        var value: Float = raw
            private set

        /**
         * Applies a horizontal movement of [dxPx] over a track [trackWidthPx] wide.
         * @return true when the value reached or crossed [default] (time for a haptic tick)
         */
        fun dragBy(
            dxPx: Float,
            trackWidthPx: Float,
            verticalOffsetPx: Float,
            rowHeightPx: Float,
        ): Boolean {
            if (trackWidthPx <= 0f) return false
            val before = value
            raw = (raw + dxPx / trackWidthPx * span * speedFactor(verticalOffsetPx, rowHeightPx)).coerceIn(min, max)
            val next = if (abs(raw - default) <= detentFraction * span) default else coerce(raw)
            value = next
            val reached = next == default && before != default
            val crossed = (before - default).sign * (next - default).sign < 0f
            return reached || crossed
        }
    }

    companion object {
        const val DEFAULT_DETENT_FRACTION = 0.01f
        const val FINE_MODE_DISTANCE_ROWS = 2f
        const val FINE_MODE_SPEED = 0.25f
        private const val CONTINUOUS_TAP_DIVISIONS = 100f
    }
}
