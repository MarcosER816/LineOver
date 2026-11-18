package dev.mark.lineover.core.engine

/**
 * 256-bin histograms of the edited preview as shown in sRGB: R, G, B and luminance L
 * (reference: `histogram_srgb` in tools/golden/reference.py). [bins] is channel-major,
 * `bins[channel * 256 + bin]`, and is never modified after construction.
 */
class Histogram(
    private val bins: IntArray,
) {
    init {
        require(bins.size == SIZE) { "a histogram has $SIZE bins, got ${bins.size}" }
    }

    operator fun get(
        channel: Int,
        bin: Int,
    ): Int = bins[channel * BINS + bin]

    /** The largest count of [channel], for scaling the curve. */
    fun max(channel: Int): Int {
        var best = 0
        for (i in 0 until BINS) best = maxOf(best, bins[channel * BINS + i])
        return best
    }

    /** Pixels counted in [channel]. */
    fun total(channel: Int): Long {
        var sum = 0L
        for (i in 0 until BINS) sum += bins[channel * BINS + i]
        return sum
    }

    companion object {
        const val BINS = 256
        const val CHANNELS = 4
        const val SIZE = BINS * CHANNELS
        const val RED = 0
        const val GREEN = 1
        const val BLUE = 2
        const val LUMA = 3
    }
}
