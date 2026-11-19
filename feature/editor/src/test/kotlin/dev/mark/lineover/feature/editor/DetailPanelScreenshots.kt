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
import dev.mark.lineover.core.model.DetailAdjustments
import dev.mark.lineover.core.model.DetailParam
import dev.mark.lineover.core.model.DetailTools
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal val SAMPLE_DETAIL =
    DetailAdjustments()
        .with(DetailParam.SharpenAmount, 60f)
        .with(DetailParam.SharpenRadius, 1.2f)
        .with(DetailParam.SharpenMasking, 30f)
        .with(DetailParam.NoiseLuminance, 40f)
        .with(DetailParam.NoiseColor, 25f)
        .with(DetailParam.Clarity, 25f)
        .with(DetailParam.Texture, -15f)
        .with(DetailParam.Dehaze, 35f)
        .with(DetailParam.VignetteAmount, -30f)
        .with(DetailParam.VignetteRoundness, 20f)
        .with(DetailParam.GrainAmount, 20f)

/** Every Stage 10 panel as a state sheet at compact and expanded widths, plus fontScale 2.0. */
abstract class DetailPanelScreenshots(
    private val widthClass: String,
    private val panelWidth: Int,
) {
    private val state = EditorState(detail = SAMPLE_DETAIL)

    @Composable
    private fun Label(text: String) {
        Text(text, style = LoType.Caption, color = LoColors.TextSecondary, modifier = Modifier.padding(LoSpacing.Xs))
    }

    private fun sheet(
        name: String,
        tools: List<String>,
        fontScale: Float = 1f,
    ) {
        captureRoboImage("editor/detail_panel/${widthClass}_$name.png") {
            LineOverTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                    Column(Modifier.width(panelWidth.dp).fillMaxWidth().background(LoColors.Surface1)) {
                        tools.forEach { tool ->
                            Label(tool)
                            DetailPanel(tool, state, {}, onClose = {})
                        }
                    }
                }
            }
        }
    }

    @Test
    fun detail() = sheet("detail", listOf(DetailTools.SHARPEN, DetailTools.NOISE))

    @Test
    fun effects() =
        sheet("effects", listOf(DetailTools.CLARITY, DetailTools.TEXTURE, DetailTools.DEHAZE, DetailTools.VIGNETTE, DetailTools.GRAIN))

    @Test
    fun sharpenAtFontScale2() = sheet("font_scale_2", listOf(DetailTools.SHARPEN), fontScale = 2f)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp-xhdpi")
class CompactDetailPanelScreenshotTest : DetailPanelScreenshots("compact", 411)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h3000dp-mdpi")
class ExpandedDetailPanelScreenshotTest : DetailPanelScreenshots("expanded", 320)
