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

// The Gradient Map panel (Stage 7): presets, the gradient bar and the stop editor.

// ---- Gradient map ----

/** Gradient presets as OKLab stops (position, L, a, b). */
private val GRADIENTS =
    listOf(
        "Black – white" to GradeAdjustments.GRADIENT_DEFAULT,
        "Teal – orange" to
            listOf(GradientStop(0f, 0.25f, -0.06f, -0.05f), GradientStop(0.5f, 0.6f, 0f, 0f), GradientStop(1f, 0.92f, 0.04f, 0.09f)),
        "Sepia" to listOf(GradientStop(0f, 0.18f, 0.02f, 0.03f), GradientStop(1f, 0.95f, 0.01f, 0.05f)),
        "Blue duotone" to listOf(GradientStop(0f, 0.2f, -0.02f, -0.12f), GradientStop(1f, 0.96f, 0f, 0.02f)),
    )

@Composable
internal fun GradientMapControls(
    grade: GradeAdjustments,
    edit: Edit,
) {
    LoSwitchRow("Gradient map", grade.gradientMap, { on -> edit { it.copy(gradientMap = on) } })
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = LoSpacing.M),
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
    ) {
        GRADIENTS.forEach { (name, stops) ->
            LoChip(name, selected = grade.gradientStops == stops, onClick = { edit { it.copy(gradientMap = true, gradientStops = stops) } })
        }
    }
    val stops = grade.gradientStops
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, stops.lastIndex)
    GradientBar(stops, index, Modifier.padding(horizontal = LoSpacing.M, vertical = LoSpacing.Xs).testTag(EditorTags.GRADIENT_BAR))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = LoSpacing.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
            stops.indices.forEach { i -> LoChip("Stop ${i + 1}", selected = i == index, onClick = { selected = i }) }
        }
        LoIconButton(LoIcons.Add, "Add stop", onClick = {
            if (stops.size < MAX_STOPS) {
                val a = stops[index]
                val b = stops.getOrNull(index + 1) ?: stops[index - 1]
                val mid = GradientStop((a.position + b.position) / 2f, (a.l + b.l) / 2f, (a.a + b.a) / 2f, (a.b + b.b) / 2f)
                edit { it.copy(gradientStops = (it.gradientStops + mid).sortedBy { s -> s.position }) }
            }
        }, enabled = stops.size < MAX_STOPS)
        LoIconButton(LoIcons.Remove, "Remove stop", onClick = {
            edit { it.copy(gradientStops = it.gradientStops.filterIndexed { i, _ -> i != index }) }
            selected = 0
        }, enabled = stops.size > 2)
    }
    val stop = stops[index]

    fun update(s: GradientStop) = edit { g -> g.copy(gradientStops = g.gradientStops.toMutableList().also { it[index] = s }) }
    val chroma = hypot(stop.a, stop.b)
    val hue = Math.toDegrees(atan2(stop.b, stop.a).toDouble()).toFloat().let { if (it < 0) it + 360f else it }

    fun withLch(
        h: Float,
        c: Float,
    ): GradientStop {
        val r = Math.toRadians(h.toDouble())
        return stop.copy(a = (c * cos(r)).toFloat(), b = (c * sin(r)).toFloat())
    }
    Slider("Position", stop.position, 0f..1f, { v -> update(stop.copy(position = v)) }, step = 0.01f, fill = LoSliderFill.FromStart)
    Slider("Lightness", stop.l, 0f..1f, { v -> update(stop.copy(l = v)) }, default = 0.5f, step = 0.01f, fill = LoSliderFill.FromStart)
    Slider(
        "Hue",
        HueMap.oklchToHsv(hue),
        0f..359f,
        { v -> update(withLch(HueMap.hsvToOklch(v), chroma)) },
        track = LoSliderTrack.Hue,
        fill = LoSliderFill.FromStart,
    )
    Slider("Colourfulness", chroma, 0f..MAX_CHROMA, { v -> update(withLch(hue, v)) }, step = 0.005f, fill = LoSliderFill.FromStart)
}

/** The gradient as it maps luminance (left = shadows), with the selected stop marked. */
@Composable
private fun GradientBar(
    stops: List<GradientStop>,
    selected: Int,
    modifier: Modifier = Modifier,
) {
    val sorted = stops.sortedBy { it.position }
    val colors = (0..GRADIENT_SAMPLES).map { i -> gradientColor(sorted, i / GRADIENT_SAMPLES.toFloat()) }
    Canvas(modifier.fillMaxWidth().height(GRADIENT_BAR).clip(LoShapes.Small)) {
        drawRect(Brush.horizontalGradient(colors))
        stops.forEachIndexed { i, s ->
            val x = s.position * size.width
            drawCircle(LoColors.Bg, if (i == selected) 9.dp.toPx() else 6.dp.toPx(), Offset(x, size.height / 2))
            drawCircle(gradientColor(sorted, s.position), if (i == selected) 7.dp.toPx() else 4.dp.toPx(), Offset(x, size.height / 2))
        }
    }
}

private fun gradientColor(
    stops: List<GradientStop>,
    t: Float,
): Color {
    val lab =
        when {
            t <= stops.first().position -> {
                stops.first()
            }

            t >= stops.last().position -> {
                stops.last()
            }

            else -> {
                val i = stops.indexOfLast { it.position <= t }.coerceAtMost(stops.size - 2)
                val a = stops[i]
                val b = stops[i + 1]
                val f = if (b.position > a.position) (t - a.position) / (b.position - a.position) else 0f
                GradientStop(t, a.l + (b.l - a.l) * f, a.a + (b.a - a.a) * f, a.b + (b.b - a.b) * f)
            }
        }
    val rgb = oklabToWorking(lab.l, lab.a, lab.b)
    return workingToSrgb(rgb[0], rgb[1], rgb[2])
}

/** OKLab -> linear Rec.2020 (the engine's working space), for drawing. */
@Suppress("MagicNumber") // published OKLab matrices
internal fun oklabToWorking(
    l: Float,
    a: Float,
    b: Float,
): FloatArray {
    val lp = l + 0.396337792f * a + 0.215803758f * b
    val mp = l - 0.105561342f * a - 0.063854175f * b
    val sp = l - 0.089484182f * a - 1.291485538f * b
    val lm = lp * lp * lp
    val mm = mp * mp * mp
    val sm = sp * sp * sp
    return floatArrayOf(
        2.140140411f * lm - 1.246355950f * mm + 0.106431726f * sm,
        -0.884832453f * lm + 2.163172721f * mm - 0.278361592f * sm,
        -0.048579058f * lm - 0.454490908f * mm + 1.502356295f * sm,
    )
}

private const val MAX_STOPS = 8
private const val MAX_CHROMA = 0.2f
private const val GRADIENT_SAMPLES = 32
private val GRADIENT_BAR = 28.dp
