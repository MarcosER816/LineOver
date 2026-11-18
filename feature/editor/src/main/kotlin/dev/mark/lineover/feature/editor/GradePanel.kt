package dev.mark.lineover.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoChip
import dev.mark.lineover.core.designsystem.component.LoColorWheel
import dev.mark.lineover.core.designsystem.component.LoCurveEditor
import dev.mark.lineover.core.designsystem.component.LoGradeWheel
import dev.mark.lineover.core.designsystem.component.LoHueSat
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.designsystem.component.LoSegmented
import dev.mark.lineover.core.designsystem.component.LoSlider
import dev.mark.lineover.core.designsystem.component.LoSliderFill
import dev.mark.lineover.core.designsystem.component.LoSliderTrack
import dev.mark.lineover.core.designsystem.component.LoSwitchRow
import dev.mark.lineover.core.engine.Histogram
import dev.mark.lineover.core.model.CurveChannel
import dev.mark.lineover.core.model.GradeAdjustments
import dev.mark.lineover.core.model.GradeRange
import dev.mark.lineover.core.model.GradeWheel
import dev.mark.lineover.core.model.GradientStop
import dev.mark.lineover.core.model.HueMap
import dev.mark.lineover.core.model.MonotoneCurve
import dev.mark.lineover.core.ui.tools.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The tools of Stage 7 (curves and grading). */
internal val GRADE_TOOLS =
    setOf(
        "light.curves_point",
        "light.curves_parametric",
        "light.levels",
        "color.color_grading",
        "color.split_toning",
        "color.color_balance",
        "color.selective_color",
        "color.channel_mixer",
        "color.black_and_white",
        "color.photo_filter",
        "color.gradient_map",
        "color.calibration",
    )

internal typealias Edit = ((GradeAdjustments) -> GradeAdjustments) -> Unit

/** The panel of one Stage 7 tool. */
@Composable
internal fun GradePanel(
    toolId: String,
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    onClose: () -> Unit,
    wide: Boolean = true,
) {
    val edit: Edit = { f -> onIntent(EditorIntent.EditGrade(f)) }
    val title = requireNotNull(ToolRegistry[toolId]).name
    // On phones the point curves panel is short (channels in the header row, short graph)
    // so that it fits the panel's height and the photo stays visible.
    val compactCurves = !wide && toolId == CURVES_POINT
    Column(Modifier.fillMaxWidth().testTag(EditorTags.GRADE_PANEL)) {
        if (!compactCurves) GradeHeader(toolId, title, onIntent, onClose)
        Column(Modifier.testTag(EditorTags.slot(toolId))) {
            GradeControls(toolId, state, edit, if (compactCurves) ({ CloseButton(title, onClose) }) else null)
        }
    }
}

@Composable
private fun GradeHeader(
    toolId: String,
    title: String,
    onIntent: (EditorIntent) -> Unit,
    onClose: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = LoSpacing.M), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = LoType.Title, color = LoColors.TextPrimary, modifier = Modifier.weight(1f))
        if (toolId == "light.levels") {
            LoChip("Auto", selected = false, onClick = { onIntent(EditorIntent.AutoLevels) }, leadingIcon = LoIcons.AutoFixHigh)
        }
        CloseButton(title, onClose)
    }
}

@Composable
private fun GradeControls(
    toolId: String,
    state: EditorState,
    edit: Edit,
    compactClose: (@Composable () -> Unit)?,
) {
    val grade = state.grade
    when (toolId) {
        CURVES_POINT -> CurvesControls(state, edit, compactClose)
        "light.curves_parametric" -> ParametricControls(grade, edit)
        "light.levels" -> LevelsControls(grade, edit)
        "color.color_grading" -> GradingControls(grade, edit)
        "color.split_toning" -> SplitToningControls(grade, edit)
        "color.color_balance" -> ColorBalanceControls(grade, edit)
        "color.selective_color" -> SelectiveColorControls(grade, edit)
        "color.channel_mixer" -> ChannelMixerControls(grade, edit)
        "color.black_and_white" -> BlackAndWhiteControls(grade, edit)
        "color.photo_filter" -> PhotoFilterControls(grade, edit)
        "color.gradient_map" -> GradientMapControls(grade, edit)
        "color.calibration" -> CalibrationControls(grade, edit)
    }
}

@Composable
internal fun Slider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    default: Float = 0f,
    step: Float = 1f,
    track: LoSliderTrack = LoSliderTrack.Plain,
    fill: LoSliderFill = LoSliderFill.FromDefault,
    enabled: Boolean = true,
) = LoSlider(value, onChange, label, valueRange = range, defaultValue = default, step = step, track = track, fill = fill, enabled = enabled)

@Composable
private fun Segments(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) = LoSegmented(options, selected, onSelect, Modifier.padding(horizontal = LoSpacing.M, vertical = LoSpacing.Xxs))

// ---- Curves ----

@Composable
private fun CloseButton(
    title: String,
    onClose: () -> Unit,
) = LoIconButton(LoIcons.Close, "Close $title", onClick = onClose)

/** [compactClose] non-null: the short phone layout, with that close button in the channel row. */
@Composable
private fun CurvesControls(
    state: EditorState,
    edit: Edit,
    compactClose: (@Composable () -> Unit)?,
) {
    val grade = state.grade
    val compact = compactClose != null
    var channel by rememberSaveable { mutableStateOf(CurveChannel.Rgb) }
    val labels = CurveChannel.entries.map { it.label }
    if (compactClose != null) {
        Row(Modifier.fillMaxWidth().padding(start = LoSpacing.M), verticalAlignment = Alignment.CenterVertically) {
            // Short labels so the row fits beside Close at any font size; TalkBack reads the full names.
            LoSegmented(
                CurveChannel.entries.map { it.label.take(if (it == CurveChannel.Rgb) RGB_LABEL else 1) },
                channel.ordinal,
                { channel = CurveChannel.entries[it] },
                Modifier.weight(1f),
                descriptions = labels,
            )
            compactClose()
        }
    } else {
        Segments(labels, channel.ordinal) { channel = CurveChannel.entries[it] }
    }
    val points = grade.curve(channel)
    val curve = remember(points) { MonotoneCurve(points) }
    val flow = remember(state.canvas) { state.canvas?.histogram ?: MutableStateFlow(null) }
    val histogram by flow.collectAsState()
    LoCurveEditor(
        points = points.map { Offset(it.x, it.y) },
        curve = { curve(it) },
        onAddPoint = { x ->
            grade.addCurvePoint(channel, x)?.let { (_, index) ->
                edit { g -> g.addCurvePoint(channel, x)?.first ?: g }
                index
            }
        },
        onMovePoint = { i, x, y -> edit { it.moveCurvePoint(channel, i, x, y) } },
        onDeletePoint = { i -> edit { it.deleteCurvePoint(channel, i) } },
        modifier = Modifier.padding(horizontal = LoSpacing.M).testTag(EditorTags.CURVE_EDITOR),
        curveColor = channel.color,
        histogram = histogram?.let(::lumaShape),
        label = "${channel.label} curve",
        aspectRatio = if (compact) COMPACT_CURVE_ASPECT else 1f,
    )
    if (compact) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = LoSpacing.M, vertical = LoSpacing.Xs),
            horizontalArrangement = Arrangement.spacedBy(LoSpacing.S),
        ) {
            LoChip("Free curve", selected = grade.freeCurve, onClick = { edit { it.withFreeCurve(!grade.freeCurve) } })
            LoChip("Reset ${channel.label}", selected = false, onClick = { edit { it.copy(curves = it.curves - channel) } })
        }
        return
    }
    LoSwitchRow(
        "Free curve",
        grade.freeCurve,
        { free -> edit { it.withFreeCurve(free) } },
        description = "Allow the curve to go down (inversions)",
    )
    LoChip("Reset ${channel.label}", selected = false, onClick = {
        edit { it.copy(curves = it.curves - channel) }
    }, modifier = Modifier.padding(start = LoSpacing.M))
}

private val CurveChannel.color: Color
    get() =
        when (this) {
            CurveChannel.Rgb -> LoColors.TextPrimary
            CurveChannel.Red -> LoColors.HistogramRed
            CurveChannel.Green -> LoColors.HistogramGreen
            CurveChannel.Blue -> LoColors.HistogramBlue
            CurveChannel.Luma -> LoColors.TextSecondary
        }

/** The luminance histogram as heights 0..1 (inner bins set the scale). */
private fun lumaShape(h: Histogram): FloatArray {
    var top = 1
    for (i in 1 until Histogram.BINS - 1) top = maxOf(top, h[Histogram.LUMA, i])
    return FloatArray(Histogram.BINS) { (h[Histogram.LUMA, it].toFloat() / top).coerceAtMost(1f) }
}

@Composable
private fun ParametricControls(
    grade: GradeAdjustments,
    edit: Edit,
) {
    val p = grade.parametric
    val points = GradeAdjustments.parametricPoints(p)
    val curve = remember(points) { MonotoneCurve(points) }
    LoCurveEditor(
        points = emptyList(),
        curve = { curve(it) },
        onAddPoint = { null },
        onMovePoint = { _, _, _ -> },
        onDeletePoint = {},
        modifier = Modifier.padding(horizontal = LoSpacing.M).height(PARAMETRIC_PREVIEW),
        enabled = false,
        label = "Parametric curve",
    )
    listOf("Highlights", "Lights", "Darks", "Shadows").forEachIndexed { i, label ->
        Slider(label, p[i], -100f..100f, { v -> edit { g -> g.copy(parametric = g.parametric.toMutableList().also { it[i] = v }) } })
    }
    listOf("Split: shadows / darks", "Split: darks / lights", "Split: lights / highlights").forEachIndexed { k, label ->
        val i = 4 + k
        val lo = if (k == 0) SPLIT_MIN else p[i - 1] + SPLIT_GAP
        val hi = if (k == 2) 1f - SPLIT_MIN else p[i + 1] - SPLIT_GAP
        Slider(label, p[i], SPLIT_MIN..1f - SPLIT_MIN, { v ->
            edit { g -> g.copy(parametric = g.parametric.toMutableList().also { it[i] = v.coerceIn(lo, hi) }) }
        }, default = GradeAdjustments.PARAMETRIC_DEFAULT[i], step = 0.01f, fill = LoSliderFill.FromStart)
    }
}

// ---- Levels ----

@Composable
private fun LevelsControls(
    grade: GradeAdjustments,
    edit: Edit,
) {
    var row by rememberSaveable { mutableIntStateOf(0) }
    Segments(listOf("RGB", "Red", "Green", "Blue"), row) { row = it }
    val labels = listOf("Input black", "Gamma", "Input white", "Output black", "Output white")
    val ranges = listOf(0f..0.98f, 0.1f..4f, 0.02f..1f, 0f..1f, 0f..1f)
    val defaults = listOf(0f, 1f, 1f, 0f, 1f)
    labels.forEachIndexed { field, label ->
        Slider(label, grade.level(row, field), ranges[field], { v ->
            edit { g ->
                val clamped =
                    when (field) {
                        0 -> v.coerceAtMost(g.level(row, 2) - LEVEL_GAP)
                        2 -> v.coerceAtLeast(g.level(row, 0) + LEVEL_GAP)
                        else -> v
                    }
                g.withLevel(row, field, clamped)
            }
        }, default = defaults[field], step = 0.01f, fill = LoSliderFill.FromStart)
    }
}

// ---- Color grading and split toning ----

private fun GradeWheel.hueSat() = LoHueSat(HueMap.oklchToHsv(hue), saturation / PERCENT)

@Composable
private fun GradingControls(
    grade: GradeAdjustments,
    edit: Edit,
) {
    var range by rememberSaveable { mutableStateOf(GradeRange.Shadows) }
    Segments(GradeRange.entries.map { it.label }, range.ordinal) { range = GradeRange.entries[it] }
    val wheel = grade.wheel(range)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        LoGradeWheel(
            value = wheel.hueSat(),
            luminance = wheel.luminance / PERCENT,
            onValueChange = { hs ->
                edit {
                    it.withWheel(
                        range,
                        it.wheel(range).copy(
                            hue = HueMap.hsvToOklch(hs.hue),
                            saturation =
                                hs.saturation * PERCENT,
                        ),
                    )
                }
            },
            onLuminanceChange = { l -> edit { it.withWheel(range, it.wheel(range).copy(luminance = l * PERCENT)) } },
            label = "${range.label} wheel",
            modifier = Modifier.height(WHEEL).testTag(EditorTags.GRADE_WHEEL),
        )
    }
    Slider("Blending", grade.blending, 0f..100f, { v -> edit { it.copy(blending = v) } }, default = 50f, fill = LoSliderFill.FromStart)
    Slider("Balance", grade.balance, -100f..100f, { v -> edit { it.copy(balance = v) } })
}

/** Split toning: the shadow and highlight wheels of Color Grading (hue and saturation) and Balance. */
@Composable
private fun SplitToningControls(
    grade: GradeAdjustments,
    edit: Edit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = LoSpacing.M), horizontalArrangement = Arrangement.spacedBy(LoSpacing.S)) {
        listOf(GradeRange.Shadows, GradeRange.Highlights).forEach { range ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                LoColorWheel(
                    grade.wheel(range).hueSat(),
                    { hs ->
                        edit {
                            it.withWheel(
                                range,
                                it.wheel(range).copy(
                                    hue = HueMap.hsvToOklch(hs.hue),
                                    saturation =
                                        hs.saturation * PERCENT,
                                ),
                            )
                        }
                    },
                    "${range.label} tint",
                    Modifier.height(SMALL_WHEEL),
                )
                Text(range.label, style = LoType.Caption, color = LoColors.TextSecondary)
            }
        }
    }
    Slider("Balance", grade.balance, -100f..100f, { v -> edit { it.copy(balance = v) } })
}

// ---- Color balance, selective color, channel mixer ----

@Composable
private fun ColorBalanceControls(
    grade: GradeAdjustments,
    edit: Edit,
) {
    var range by rememberSaveable { mutableIntStateOf(1) }
    Segments(listOf("Shadows", "Midtones", "Highlights"), range) { range = it }
    listOf("Cyan – Red", "Magenta – Green", "Yellow – Blue").forEachIndexed { c, label ->
        val i = range * 3 + c
        Slider(label, grade.colorBalance[i], -100f..100f, { v ->
            edit { g ->
                g.copy(
                    colorBalance =
                        g.colorBalance.toMutableList().also {
                            it[i] =
                                v
                        },
                )
            }
        })
    }
    LoSwitchRow("Preserve luminosity", grade.preserveLuminosity, { on -> edit { it.copy(preserveLuminosity = on) } })
}

private val FAMILIES = listOf("Reds", "Yellows", "Greens", "Cyans", "Blues", "Magentas", "Whites", "Neutrals", "Blacks")

@Composable
private fun SelectiveColorControls(
    grade: GradeAdjustments,
    edit: Edit,
) {
    var family by rememberSaveable { mutableIntStateOf(0) }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = LoSpacing.M),
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
    ) {
        FAMILIES.forEachIndexed { i, name -> LoChip(name, selected = i == family, onClick = { family = i }) }
    }
    Segments(listOf("Relative", "Absolute"), if (grade.selectiveRelative) 0 else 1) { i -> edit { it.copy(selectiveRelative = i == 0) } }
    listOf("Cyan", "Magenta", "Yellow", "Black").forEachIndexed { k, label ->
        val i = family * 4 + k
        Slider(label, grade.selective[i], -100f..100f, { v ->
            edit { g ->
                g.copy(selective = g.selective.toMutableList().also { it[i] = v })
            }
        })
    }
}

@Composable
private fun ChannelMixerControls(
    grade: GradeAdjustments,
    edit: Edit,
) {
    var output by rememberSaveable { mutableIntStateOf(0) }
    val row = if (grade.mixerMonochrome) 0 else output
    if (!grade.mixerMonochrome) Segments(listOf("Red out", "Green out", "Blue out"), output) { output = it }
    listOf("Red", "Green", "Blue").forEachIndexed { c, label ->
        val i = row * 3 + c
        Slider(
            label,
            grade.mixer[i],
            -200f..200f,
            { v -> edit { g -> g.copy(mixer = g.mixer.toMutableList().also { it[i] = v }) } },
            default = GradeAdjustments.MIXER_IDENTITY[i],
        )
    }
    Slider("Constant", grade.mixerConstant[row], -100f..100f, { v ->
        edit { g -> g.copy(mixerConstant = g.mixerConstant.toMutableList().also { it[row] = v }) }
    })
    val total = (0 until 3).sumOf { grade.mixer[row * 3 + it].toDouble() }
    Text(
        "Total ${total.toInt()} %",
        style = LoType.Caption,
        color = if (total > 100.5) LoColors.Warning else LoColors.TextSecondary,
        modifier = Modifier.padding(horizontal = LoSpacing.M),
    )
    LoSwitchRow("Monochrome", grade.mixerMonochrome, { on ->
        edit { g ->
            if (on &&
                !g.mixerMonochrome
            ) {
                g.copy(mixerMonochrome = true, mixer = MONO_DEFAULT + g.mixer.drop(3))
            } else {
                g.copy(mixerMonochrome = on)
            }
        }
    })
}

private val MONO_DEFAULT = listOf(30f, 59f, 11f)

// ---- Black & white, photo filter ----

@Composable
private fun BlackAndWhiteControls(
    grade: GradeAdjustments,
    edit: Edit,
) {
    LoSwitchRow("Black & White", grade.blackAndWhite, { on -> edit { it.copy(blackAndWhite = on) } })
    listOf("Reds", "Yellows", "Greens", "Cyans", "Blues", "Magentas").forEachIndexed { i, label ->
        Slider(
            label,
            grade.bwMix[i],
            -100f..100f,
            { v -> edit { g -> g.copy(bwMix = g.bwMix.toMutableList().also { it[i] = v }) } },
            enabled = grade.blackAndWhite,
        )
    }
    Slider(
        "Tint hue",
        HueMap.oklchToHsv(grade.bwTintHue),
        0f..359f,
        { v -> edit { it.copy(bwTintHue = HueMap.hsvToOklch(v)) } },
        default = HueMap.oklchToHsv(GradeAdjustments().bwTintHue),
        track = LoSliderTrack.Hue,
        fill = LoSliderFill.FromStart,
        enabled = grade.blackAndWhite,
    )
    Slider(
        "Tint",
        grade.bwTintSaturation,
        0f..100f,
        { v -> edit { it.copy(bwTintSaturation = v) } },
        fill = LoSliderFill.FromStart,
        enabled = grade.blackAndWhite,
    )
}

/** Photo filter presets: name, HSV hue, saturation. */
private val FILTERS =
    listOf(
        Triple("Warming 85", 30f, 80f),
        Triple("Warming LBA", 25f, 95f),
        Triple("Cooling 80", 215f, 75f),
        Triple("Cooling 82", 200f, 45f),
        Triple("Sepia", 35f, 60f),
        Triple("Green", 120f, 60f),
    )

@Composable
private fun PhotoFilterControls(
    grade: GradeAdjustments,
    edit: Edit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = LoSpacing.M),
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
    ) {
        FILTERS.forEach { (name, hue, sat) ->
            val oklch = HueMap.hsvToOklch(hue)
            LoChip(name, selected = kotlin.math.abs(grade.filterHue - oklch) < 0.5f && grade.filterSaturation == sat, onClick = {
                edit {
                    it.copy(
                        filterHue = oklch,
                        filterSaturation = sat,
                        filterDensity =
                            if (it.filterDensity ==
                                0f
                            ) {
                                DEFAULT_DENSITY
                            } else {
                                it.filterDensity
                            },
                    )
                }
            })
        }
    }
    Slider(
        "Hue",
        HueMap.oklchToHsv(grade.filterHue),
        0f..359f,
        { v -> edit { it.copy(filterHue = HueMap.hsvToOklch(v)) } },
        default = 30f,
        track = LoSliderTrack.Hue,
        fill = LoSliderFill.FromStart,
    )
    Slider("Saturation", grade.filterSaturation, 0f..100f, { v ->
        edit {
            it.copy(filterSaturation = v)
        }
    }, default = 80f, fill = LoSliderFill.FromStart)
    Slider("Density", grade.filterDensity, 0f..100f, { v -> edit { it.copy(filterDensity = v) } }, fill = LoSliderFill.FromStart)
    LoSwitchRow("Preserve luminosity", grade.filterPreserve, { on -> edit { it.copy(filterPreserve = on) } })
}

// ---- Calibration ----

@Composable
private fun CalibrationControls(
    grade: GradeAdjustments,
    edit: Edit,
) {
    listOf("Red primary", "Green primary", "Blue primary").forEachIndexed { i, name ->
        Text(
            name,
            style = LoType.Caption,
            color = LoColors.TextSecondary,
            modifier = Modifier.padding(start = LoSpacing.M, top = LoSpacing.Xs),
        )
        Slider("Hue", grade.calibrationHue[i], -100f..100f, { v ->
            edit { g -> g.copy(calibrationHue = g.calibrationHue.toMutableList().also { it[i] = v }) }
        })
        Slider("Saturation", grade.calibrationSaturation[i], -100f..100f, { v ->
            edit { g -> g.copy(calibrationSaturation = g.calibrationSaturation.toMutableList().also { it[i] = v }) }
        })
    }
}

private const val PERCENT = 100f
private const val SPLIT_MIN = 0.05f
private const val SPLIT_GAP = 0.05f
private const val LEVEL_GAP = 0.02f
private const val DEFAULT_DENSITY = 25f
private val PARAMETRIC_PREVIEW = 140.dp
private val WHEEL = 220.dp
private val SMALL_WHEEL = 140.dp

private const val CURVES_POINT = "light.curves_point"
private const val RGB_LABEL = 3

// Width / height of the phone curve graph: about 130 dp high at 411 dp wide, so the whole
// panel fits the compact panel's height (40 % of the canvas) above the tool strip.
private const val COMPACT_CURVE_ASPECT = 2.9f
