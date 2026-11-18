package dev.mark.lineover.core.model

/** The channels of the point curve. */
enum class CurveChannel(
    val label: String,
) {
    Rgb("RGB"),
    Red("Red"),
    Green("Green"),
    Blue("Blue"),
    Luma("Luma"),
}

/** One wheel of Color Grading: hue (degrees), saturation 0..100, luminance -100..100. */
data class GradeWheel(
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val luminance: Float = 0f,
) {
    fun toList() = listOf(hue, saturation, luminance)
}

enum class GradeRange(
    val label: String,
) {
    Shadows("Shadows"),
    Midtones("Midtones"),
    Highlights("Highlights"),
    Global("Global"),
}

/** A Gradient Map stop: position 0..1 and an OKLab colour. */
data class GradientStop(
    val position: Float,
    val l: Float,
    val a: Float,
    val b: Float,
)

/**
 * Curves and grading (Stage 7). [calibration] runs first (camera-like primaries), [tone]
 * after the Light tools (Levels → Curves → Parametric), [grading] after the colour basics
 * (Color Balance → Selective Color → Channel Mixer → Color Grading → Photo Filter →
 * Black & White → Gradient Map). Controls at their defaults add no op.
 */
@Suppress("MagicNumber") // parameter counts and positions fixed by the engine's op formats (grade_ops.h)
data class GradeAdjustments(
    val curves: Map<CurveChannel, List<CurvePoint>> = emptyMap(),
    /** Free curve mode: points may go down (inversions); off = the curve can only rise. */
    val freeCurve: Boolean = false,
    val parametric: List<Float> = PARAMETRIC_DEFAULT,
    val levels: List<Float> = LEVELS_IDENTITY,
    val wheels: Map<GradeRange, GradeWheel> = emptyMap(),
    val blending: Float = 50f,
    val balance: Float = 0f,
    /** Color Balance: 3 ranges × (cyan-red, magenta-green, yellow-blue). */
    val colorBalance: List<Float> = List(9) { 0f },
    val preserveLuminosity: Boolean = true,
    val selective: List<Float> = List(36) { 0f },
    val selectiveRelative: Boolean = true,
    val mixer: List<Float> = MIXER_IDENTITY,
    val mixerConstant: List<Float> = List(3) { 0f },
    val mixerMonochrome: Boolean = false,
    val blackAndWhite: Boolean = false,
    val bwMix: List<Float> = List(6) { 0f },
    val bwTintHue: Float = 40f,
    val bwTintSaturation: Float = 0f,
    val filterHue: Float = 60f,
    val filterSaturation: Float = 80f,
    val filterDensity: Float = 0f,
    val filterPreserve: Boolean = true,
    val gradientMap: Boolean = false,
    val gradientStops: List<GradientStop> = GRADIENT_DEFAULT,
    val calibrationHue: List<Float> = List(3) { 0f },
    val calibrationSaturation: List<Float> = List(3) { 0f },
) {
    fun curve(channel: CurveChannel): List<CurvePoint> = curves[channel] ?: IDENTITY_POINTS

    fun wheel(range: GradeRange): GradeWheel = wheels[range] ?: GradeWheel()

    fun withWheel(
        range: GradeRange,
        wheel: GradeWheel,
    ) = copy(
        wheels =
            wheels +
                (range to wheel.copy(saturation = wheel.saturation.coerceIn(0f, 100f), luminance = wheel.luminance.coerceIn(-100f, 100f))),
    )

    // ---- Curve editing (the rules the curve editor enforces) ----

    /** Adds a point on the curve at [x]; null when too close to an existing point. */
    fun addCurvePoint(
        channel: CurveChannel,
        x: Float,
    ): Pair<GradeAdjustments, Int>? {
        val points = curve(channel)
        val cx = x.coerceIn(MIN_POINT_GAP, 1f - MIN_POINT_GAP)
        if (points.any { kotlin.math.abs(it.x - cx) < MIN_POINT_GAP } || points.size >= MAX_POINTS) return null
        val y = MonotoneCurve(points)(cx)
        val index = points.indexOfFirst { it.x > cx }
        val next = points.toMutableList().apply { add(index, CurvePoint(cx, y)) }
        return copy(curves = curves + (channel to next)) to index
    }

    /**
     * Moves point [index] to ([x], [y]). Ends keep their x; interior points stay between
     * their neighbours. Unless [freeCurve], y stays between the neighbours' y, so the
     * curve never inverts.
     */
    fun moveCurvePoint(
        channel: CurveChannel,
        index: Int,
        x: Float,
        y: Float,
    ): GradeAdjustments {
        val points = curve(channel).toMutableList()
        if (index !in points.indices) return this
        val last = points.lastIndex
        val nx =
            when (index) {
                0 -> points[0].x
                last -> points[last].x
                else -> x.coerceIn(points[index - 1].x + MIN_POINT_GAP, points[index + 1].x - MIN_POINT_GAP)
            }
        var ny = y.coerceIn(0f, 1f)
        if (!freeCurve) {
            if (index > 0) ny = maxOf(ny, points[index - 1].y)
            if (index < last) ny = minOf(ny, points[index + 1].y)
        }
        points[index] = CurvePoint(nx, ny)
        return copy(curves = curves + (channel to points))
    }

    /** Deletes an interior point (the two ends stay). */
    fun deleteCurvePoint(
        channel: CurveChannel,
        index: Int,
    ): GradeAdjustments {
        val points = curve(channel)
        if (index <= 0 || index >= points.lastIndex) return this
        return copy(curves = curves + (channel to points.filterIndexed { i, _ -> i != index }))
    }

    /** Switching free mode off makes every channel monotone again (each y ≥ the previous). */
    fun withFreeCurve(free: Boolean): GradeAdjustments {
        if (free) return copy(freeCurve = true)
        val fixed =
            curves.mapValues { (_, pts) ->
                var floor = 0f
                pts.map { p -> CurvePoint(p.x, maxOf(p.y, floor)).also { floor = it.y } }
            }
        return copy(freeCurve = false, curves = fixed)
    }

    // ---- Levels ----

    fun level(
        row: Int,
        field: Int,
    ): Float = levels[row * LEVEL_FIELDS + field]

    fun withLevel(
        row: Int,
        field: Int,
        value: Float,
    ) = copy(levels = levels.toMutableList().also { it[row * LEVEL_FIELDS + field] = value })

    /** Levels → Auto: per-channel black / white points from (low R, high R, low G, high G, low B, high B). */
    fun withAutoLevels(percentiles: FloatArray): GradeAdjustments {
        val v = LEVELS_IDENTITY.toMutableList()
        for (c in 0 until 3) {
            v[(c + 1) * LEVEL_FIELDS] = percentiles[2 * c].coerceIn(0f, AUTO_MAX_BLACK)
            v[(c + 1) * LEVEL_FIELDS + 2] = percentiles[2 * c + 1].coerceIn(AUTO_MIN_WHITE, 1f)
        }
        return copy(levels = v)
    }

    // ---- Ops ----

    fun calibrationOps(): List<OpParams> = listOf(OpParams.Calibration(calibrationHue, calibrationSaturation)).filterNot { it.isIdentity }

    fun toneOps(): List<OpParams> =
        listOf(
            OpParams.Levels(levels),
            OpParams.Curves(
                if (freeCurve) 1f else 0f,
                flat(CurveChannel.Rgb),
                flat(CurveChannel.Red),
                flat(CurveChannel.Green),
                flat(CurveChannel.Blue),
                flat(CurveChannel.Luma),
            ),
            OpParams.ParametricCurve(
                parametric[0],
                parametric[1],
                parametric[2],
                parametric[3],
                parametric[4],
                parametric[5],
                parametric[6],
            ),
        ).filterNot { it.isIdentity }

    fun gradingOps(): List<OpParams> =
        listOfNotNull(
            OpParams.ColorBalance(
                flag(preserveLuminosity),
                colorBalance.subList(0, 3),
                colorBalance.subList(3, 6),
                colorBalance.subList(6, 9),
            ),
            OpParams.SelectiveColor(flag(selectiveRelative), selective),
            OpParams.ChannelMixer(flag(mixerMonochrome), mixer, mixerConstant),
            OpParams.ColorGrading(
                blending,
                balance,
                wheel(GradeRange.Shadows).toList(),
                wheel(GradeRange.Midtones).toList(),
                wheel(GradeRange.Highlights).toList(),
                wheel(GradeRange.Global).toList(),
            ),
            OpParams.PhotoFilter(filterHue, filterSaturation, filterDensity, flag(filterPreserve)),
            OpParams.BlackAndWhite(bwTintHue, bwTintSaturation, bwMix).takeIf { blackAndWhite },
            OpParams.GradientMap(gradientStops.flatMap { listOf(it.position, it.l, it.a, it.b) }).takeIf { gradientMap },
        ).filterNot { it.isIdentity }

    /** An identity channel is written as [] (no points), the engine skips it. */
    private fun flat(channel: CurveChannel): List<Float> =
        MonotoneCurve.flatten(curve(channel)).takeUnless { MonotoneCurve.isIdentity(it) } ?: emptyList()

    private fun flag(b: Boolean) = if (b) 1f else 0f

    companion object {
        const val LEVEL_FIELDS = 5
        const val MIN_POINT_GAP = 0.01f
        const val MAX_POINTS = 16
        private const val AUTO_MAX_BLACK = 0.5f
        private const val AUTO_MIN_WHITE = 0.5f
        val IDENTITY_POINTS = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
        val LEVELS_IDENTITY: List<Float> = List(4) { listOf(0f, 1f, 1f, 0f, 1f) }.flatten()
        val MIXER_IDENTITY = listOf(100f, 0f, 0f, 0f, 100f, 0f, 0f, 0f, 100f)

        /** highlights, lights, darks, shadows, split 1, split 2, split 3. */
        val PARAMETRIC_DEFAULT = listOf(0f, 0f, 0f, 0f, 0.25f, 0.5f, 0.75f)
        val GRADIENT_DEFAULT = listOf(GradientStop(0f, 0.1f, 0f, 0f), GradientStop(1f, 0.95f, 0f, 0f))

        /** The 9 points of the parametric curve (as the engine builds them: centres move within their region). */
        fun parametricPoints(p: List<Float>): List<CurvePoint> {
            val edges = listOf(0f, p[4], p[5], p[6], 1f)
            val amounts = listOf(p[3], p[2], p[1], p[0]) // shadows, darks, lights, highlights
            val out = mutableListOf(CurvePoint(0f, 0f))
            for (i in 0 until 4) {
                val lo = edges[i]
                val hi = edges[i + 1]
                val c = 0.5f * (lo + hi)
                out += CurvePoint(c, c + amounts[i] / 100f * PARAMETRIC_REACH * (hi - lo))
                out += CurvePoint(hi, hi)
            }
            return out
        }

        private const val PARAMETRIC_REACH = 0.45f

        /** Reads the Stage 7 values back from a layer's ops (other ops are ignored). */
        @Suppress("CyclomaticComplexMethod", "LongMethod") // one branch per op
        fun fromOps(ops: List<OpParams>): GradeAdjustments {
            var g = GradeAdjustments()
            for (op in ops) {
                g =
                    when (op) {
                        is OpParams.Curves -> {
                            val map =
                                mapOf(
                                    CurveChannel.Rgb to op.rgb,
                                    CurveChannel.Red to op.red,
                                    CurveChannel.Green to op.green,
                                    CurveChannel.Blue to op.blue,
                                    CurveChannel.Luma to op.luma,
                                ).filterValues { it.size >= 4 }.mapValues { MonotoneCurve.points(it.value) }
                            g.copy(curves = map, freeCurve = op.free > 0.5f)
                        }

                        is OpParams.ParametricCurve -> {
                            g.copy(parametric = listOf(op.highlights, op.lights, op.darks, op.shadows, op.split1, op.split2, op.split3))
                        }

                        is OpParams.Levels -> {
                            g.copy(levels = op.values)
                        }

                        is OpParams.ColorGrading -> {
                            g.copy(
                                blending = op.blending,
                                balance = op.balance,
                                wheels =
                                    mapOf(
                                        GradeRange.Shadows to op.shadows,
                                        GradeRange.Midtones to op.midtones,
                                        GradeRange.Highlights to op.highlights,
                                        GradeRange.Global to op.global,
                                    ).mapValues { GradeWheel(it.value[0], it.value[1], it.value[2]) }.filterValues { it != GradeWheel() },
                            )
                        }

                        is OpParams.ColorBalance -> {
                            g.copy(colorBalance = op.shadows + op.midtones + op.highlights, preserveLuminosity = op.preserve > 0.5f)
                        }

                        is OpParams.SelectiveColor -> {
                            g.copy(selective = op.values, selectiveRelative = op.relative > 0.5f)
                        }

                        is OpParams.ChannelMixer -> {
                            g.copy(mixer = op.matrix, mixerConstant = op.constant, mixerMonochrome = op.monochrome > 0.5f)
                        }

                        is OpParams.BlackAndWhite -> {
                            g.copy(blackAndWhite = true, bwMix = op.mix, bwTintHue = op.tintHue, bwTintSaturation = op.tintSaturation)
                        }

                        is OpParams.PhotoFilter -> {
                            g.copy(
                                filterHue = op.hue,
                                filterSaturation = op.saturation,
                                filterDensity = op.density,
                                filterPreserve =
                                    op.preserve > 0.5f,
                            )
                        }

                        is OpParams.GradientMap -> {
                            g.copy(gradientMap = true, gradientStops = op.stops.chunked(4).map { GradientStop(it[0], it[1], it[2], it[3]) })
                        }

                        is OpParams.Calibration -> {
                            g.copy(calibrationHue = op.hue, calibrationSaturation = op.saturation)
                        }

                        else -> {
                            g
                        }
                    }
            }
            return g
        }
    }
}
