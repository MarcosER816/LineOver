package dev.mark.lineover.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.model.CurveChannel
import dev.mark.lineover.core.model.CurvePoint
import dev.mark.lineover.core.model.GradeAdjustments
import dev.mark.lineover.core.model.GradeRange
import dev.mark.lineover.core.model.GradeWheel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal val SAMPLE_GRADE =
    GradeAdjustments(
        curves =
            mapOf(
                CurveChannel.Rgb to listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.18f), CurvePoint(0.75f, 0.84f), CurvePoint(1f, 1f)),
            ),
        parametric = listOf(-30f, 10f, 20f, 40f, 0.25f, 0.5f, 0.75f),
        levels = listOf(0.04f, 1.15f, 0.95f, 0f, 1f) + GradeAdjustments.LEVELS_IDENTITY.drop(5),
        wheels = mapOf(GradeRange.Shadows to GradeWheel(194.8f, 60f, -10f), GradeRange.Highlights to GradeWheel(52.8f, 50f, 15f)),
        colorBalance = listOf(-20f, 0f, 15f, 0f, 0f, 0f, 10f, 0f, -15f),
        selective = List(36) { if (it == 0) -30f else 0f },
        blackAndWhite = true,
        bwMix = listOf(20f, 10f, -10f, 0f, -30f, 0f),
        filterDensity = 30f,
        gradientMap = true,
        calibrationHue = listOf(10f, 0f, -5f),
    )

/** Every Stage 7 panel as a state sheet at compact and expanded widths, plus fontScale 2.0. */
abstract class GradePanelScreenshots(
    private val widthClass: String,
    private val panelWidth: Int,
) {
    private val state = EditorState(grade = SAMPLE_GRADE)

    @Composable
    private fun Label(text: String) {
        Text(text, style = LoType.Caption, color = LoColors.TextSecondary, modifier = Modifier.padding(LoSpacing.Xs))
    }

    private fun sheet(
        name: String,
        tools: List<String>,
    ) {
        captureRoboImage("editor/grade_panel/${widthClass}_$name.png") {
            LineOverTheme {
                Column(Modifier.width(panelWidth.dp).background(LoColors.Surface1)) {
                    tools.forEach { tool ->
                        Label(tool)
                        GradePanel(tool, state, {}, onClose = {})
                    }
                }
            }
        }
    }

    @Test
    fun curvesAndLevels() = sheet("curves_levels", listOf("light.curves_point", "light.curves_parametric", "light.levels"))

    @Test
    fun grading() = sheet("grading", listOf("color.color_grading", "color.split_toning", "color.color_balance"))

    @Test
    fun mixers() = sheet("mixers", listOf("color.selective_color", "color.channel_mixer", "color.black_and_white"))

    @Test
    fun filters() = sheet("filters", listOf("color.photo_filter", "color.gradient_map", "color.calibration"))

    @Test
    fun curveAtFontScale2() {
        captureRoboImage("editor/grade_panel/${widthClass}_font_scale_2.png") {
            LineOverTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    Column(Modifier.width(panelWidth.dp).fillMaxWidth().background(LoColors.Surface1)) {
                        GradePanel("light.curves_point", state, {}, onClose = {})
                    }
                }
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp-xhdpi")
class CompactGradePanelScreenshotTest : GradePanelScreenshots("compact", 411)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h3000dp-mdpi")
class ExpandedGradePanelScreenshotTest : GradePanelScreenshots("expanded", 320)
