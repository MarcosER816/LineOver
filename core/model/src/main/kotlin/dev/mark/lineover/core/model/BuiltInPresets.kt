package dev.mark.lineover.core.model

/**
 * LineOver's own presets (Stage 11): combinations of LineOver's tools made for this app,
 * not copies of any commercial look or film stock. Each sets only the ops it names.
 */
object BuiltInPresets {
    const val NATURAL = "Natural"
    const val COLOR = "Color"
    const val MONO = "Black & white"
    const val MOOD = "Mood"

    val GROUPS = listOf(NATURAL, COLOR, MONO, MOOD)

    private fun wheel(
        hue: Float,
        saturation: Float,
        luminance: Float = 0f,
    ) = listOf(hue, saturation, luminance)

    private val noWheel = wheel(0f, 0f)

    private fun grading(
        shadows: List<Float> = noWheel,
        midtones: List<Float> = noWheel,
        highlights: List<Float> = noWheel,
        global: List<Float> = noWheel,
        balance: Float = 0f,
    ) = OpParams.ColorGrading(50f, balance, shadows, midtones, highlights, global)

    private fun curve(
        highlights: Float,
        lights: Float,
        darks: Float,
        shadows: Float,
    ) = OpParams.ParametricCurve(highlights, lights, darks, shadows, 0.25f, 0.5f, 0.75f)

    private fun preset(
        id: String,
        name: String,
        group: String,
        vararg ops: OpParams,
    ) = Preset.of("builtin.$id", name, group, ops.toList(), builtIn = true)

    @Suppress("MagicNumber", "LongMethod") // the presets are their numbers
    val ALL: List<Preset> =
        listOf(
            preset("clean", "Clean", NATURAL, OpParams.Contrast(10f), OpParams.Vibrance(12f), OpParams.Clarity(8f)),
            preset(
                "soft_light",
                "Soft light",
                NATURAL,
                OpParams.HighlightsShadows(-30f, 25f),
                OpParams.Contrast(-10f),
                OpParams.Clarity(-10f),
            ),
            preset("crisp", "Crisp", NATURAL, OpParams.Contrast(15f), OpParams.Texture(25f), OpParams.Clarity(15f), OpParams.Dehaze(8f)),
            preset("warm_afternoon", "Warm afternoon", NATURAL, OpParams.WhiteBalance(5600f, 6f), OpParams.Vibrance(15f)),
            preset("cool_morning", "Cool morning", NATURAL, OpParams.WhiteBalance(7600f, -4f), OpParams.HighlightsShadows(-15f, 10f)),
            preset(
                "punchy",
                "Punchy",
                COLOR,
                OpParams.Contrast(30f),
                OpParams.Vibrance(30f),
                OpParams.Clarity(15f),
                OpParams.Profile("vivid", 70f),
            ),
            preset(
                "pastel",
                "Pastel",
                COLOR,
                OpParams.Contrast(-25f),
                OpParams.Saturation(-20f),
                OpParams.Blacks(30f),
                OpParams.Exposure(0.2f),
            ),
            preset(
                "deep_blue",
                "Deep blue",
                COLOR,
                OpParams.HslMixer(
                    List(8) { 0f },
                    listOf(0f, 0f, 0f, 0f, 20f, 30f, 0f, 0f),
                    listOf(0f, 0f, 0f, 0f, -10f, -25f, 0f, 0f),
                ),
                OpParams.Dehaze(10f),
            ),
            preset(
                "golden",
                "Golden",
                COLOR,
                OpParams.WhiteBalance(5200f, 10f),
                grading(highlights = wheel(60f, 25f)),
                OpParams.Vibrance(10f),
            ),
            preset(
                "teal_shadows",
                "Teal shadows",
                COLOR,
                grading(shadows = wheel(195f, 30f), highlights = wheel(45f, 15f)),
                OpParams.Contrast(10f),
            ),
            preset(
                "mono_soft",
                "Mono soft",
                MONO,
                OpParams.Profile("mono"),
                OpParams.Contrast(-10f),
                OpParams.HighlightsShadows(-20f, 20f),
            ),
            preset(
                "mono_contrast",
                "Mono contrast",
                MONO,
                OpParams.Profile("mono"),
                OpParams.Contrast(40f),
                OpParams.Clarity(20f),
                OpParams.Blacks(-20f),
            ),
            preset(
                "mono_matte",
                "Mono matte",
                MONO,
                OpParams.Profile("mono"),
                curve(-10f, 0f, 10f, 30f),
                OpParams.Grain(20f, 30f, 50f),
            ),
            preset("matte_fade", "Matte fade", MOOD, curve(-15f, 0f, 10f, 35f), OpParams.Saturation(-10f), OpParams.Profile("flat", 60f)),
            preset(
                "faded_print",
                "Faded print",
                MOOD,
                OpParams.Profile("flat"),
                grading(shadows = wheel(220f, 15f), highlights = wheel(50f, 15f)),
                OpParams.Grain(25f, 25f, 40f),
            ),
            preset(
                "night_city",
                "Night city",
                MOOD,
                OpParams.WhiteBalance(5800f, 8f),
                grading(shadows = wheel(230f, 25f), highlights = wheel(35f, 20f)),
                OpParams.Contrast(20f),
                OpParams.NoiseReduction(30f, 50f, 0f, 30f),
            ),
            preset(
                "moody",
                "Moody",
                MOOD,
                OpParams.Exposure(-0.3f),
                OpParams.HighlightsShadows(-30f, -10f),
                OpParams.Vibrance(-15f),
                OpParams.PostCropVignette(-30f),
            ),
            preset(
                "dreamy",
                "Dreamy",
                MOOD,
                OpParams.Clarity(-30f),
                OpParams.HighlightsShadows(-20f, 20f),
                OpParams.Dehaze(-15f),
                OpParams.Exposure(0.15f),
            ),
            preset(
                "bright_airy",
                "Bright & airy",
                MOOD,
                OpParams.Exposure(0.4f),
                OpParams.HighlightsShadows(-40f, 35f),
                OpParams.Contrast(-15f),
                OpParams.Vibrance(-10f),
            ),
            preset("frame", "Frame", MOOD, OpParams.PostCropVignette(-45f, 40f, 0f, 60f, 20f), OpParams.Clarity(10f)),
        )

    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }
}
