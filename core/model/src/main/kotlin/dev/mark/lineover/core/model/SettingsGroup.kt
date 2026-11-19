package dev.mark.lineover.core.model

/**
 * The groups of the copy / paste checklist and of "Save current as preset" (Stage 11): every
 * background op belongs to exactly one group. Crop & optics is off by default (a crop rarely
 * fits another photo).
 */
enum class SettingsGroup(
    val label: String,
    val defaultOn: Boolean = true,
) {
    Light("Light"),
    WhiteBalance("White balance"),
    Color("Color"),
    Curves("Curves and levels"),
    Grading("Color grading and mixers"),
    Looks("Profile and LUT"),
    Effects("Effects"),
    Detail("Detail"),
    Geometry("Crop and optics", defaultOn = false),
    ;

    companion object {
        val DEFAULTS: Set<SettingsGroup> = entries.filter { it.defaultOn }.toSet()

        @Suppress("CyclomaticComplexMethod")
        fun of(op: OpParams): SettingsGroup? =
            when (op) {
                is OpParams.Exposure, is OpParams.Contrast, is OpParams.Brightness, is OpParams.Whites, is OpParams.Blacks,
                is OpParams.HighlightsShadows,
                -> Light

                is OpParams.WhiteBalance -> WhiteBalance

                is OpParams.Vibrance, is OpParams.Saturation, is OpParams.HslMixer, is OpParams.Invert, is OpParams.Posterize,
                is OpParams.Threshold,
                -> Color

                is OpParams.Curves, is OpParams.ParametricCurve, is OpParams.Levels -> Curves

                is OpParams.ColorGrading, is OpParams.ColorBalance, is OpParams.SelectiveColor, is OpParams.ChannelMixer,
                is OpParams.BlackAndWhite, is OpParams.PhotoFilter, is OpParams.GradientMap, is OpParams.Calibration,
                -> Grading

                is OpParams.Profile, is OpParams.Lut -> Looks

                is OpParams.Clarity, is OpParams.Texture, is OpParams.Dehaze, is OpParams.PostCropVignette, is OpParams.Grain,
                -> Effects

                is OpParams.Sharpen, is OpParams.NoiseReduction -> Detail

                is OpParams.Geometry -> Geometry

                is OpParams.Identity, is OpParams.ExposureTest -> null
            }

        /** The ops of [ops] in [groups]. */
        fun select(
            ops: List<OpParams>,
            groups: Set<SettingsGroup>,
        ): List<OpParams> = ops.filter { of(it) in groups }

        /**
         * [target] with the [groups] replaced by those of [source]: the target's ops of those
         * groups are dropped (a group the source leaves at default is reset), the source's added.
         */
        fun paste(
            target: List<OpParams>,
            source: List<OpParams>,
            groups: Set<SettingsGroup>,
        ): List<OpParams> = target.filterNot { of(it) in groups } + select(source, groups)
    }
}
