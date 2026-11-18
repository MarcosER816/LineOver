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
import dev.mark.lineover.core.model.ColorAdjustments
import dev.mark.lineover.core.model.HslBand
import dev.mark.lineover.core.model.HslChannel
import dev.mark.lineover.feature.editor.canvas.PickMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal val SAMPLE_COLOR =
    ColorAdjustments()
        .withWhiteBalance(5200f, 14f)
        .copy(vibrance = 22f, saturation = -8f, posterize = 6, threshold = 0.42f)
        .withHsl(HslChannel.Saturation, HslBand.Orange, 35f)
        .withHsl(HslChannel.Saturation, HslBand.Blue, -25f)
        .withHsl(HslChannel.Saturation, HslBand.Aqua, 12f)

/**
 * The Color panels (Stage 6) as state sheets: every tool, the eyedropper and targeted
 * toggles on, the HSL channels, and fontScale 2.0, at compact and expanded widths.
 */
abstract class ColorPanelScreenshots(
    private val widthClass: String,
    private val panelWidth: Int,
) {
    private val state = EditorState(color = SAMPLE_COLOR, hslChannel = HslChannel.Saturation)

    @Composable
    private fun Label(text: String) {
        Text(text, style = LoType.Caption, color = LoColors.TextSecondary, modifier = Modifier.padding(LoSpacing.Xs))
    }

    @Composable
    private fun Panel(
        tool: String,
        s: EditorState = state,
    ) = ColorPanel(tool, s, {}, onClose = {})

    @Test
    fun whiteBalanceAndBasics() {
        captureRoboImage("editor/color_panel/${widthClass}_basics.png") {
            LineOverTheme {
                Column(Modifier.width(panelWidth.dp).background(LoColors.Surface1)) {
                    Label("white balance (blue -> amber, green -> magenta tracks)")
                    Panel(WHITE_BALANCE)
                    Label("eyedropper armed")
                    Panel(WHITE_BALANCE, state.copy(pickMode = PickMode.Eyedropper))
                    Label("vibrance · saturation")
                    Panel("color.vibrance")
                    Panel("color.saturation")
                    Label("invert · posterize · threshold")
                    Panel("color.invert", state.copy(color = SAMPLE_COLOR.copy(invert = true)))
                    Panel("color.posterize")
                    Panel("color.threshold")
                }
            }
        }
    }

    @Test
    fun hslMixer() {
        captureRoboImage("editor/color_panel/${widthClass}_hsl.png") {
            LineOverTheme {
                Column(Modifier.width(panelWidth.dp).background(LoColors.Surface1)) {
                    Label("saturation, targeted adjustment armed")
                    Panel(HSL_MIXER, state.copy(pickMode = PickMode.Targeted))
                    Label("fontScale 2.0, hue")
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                        Column(Modifier.fillMaxWidth()) { Panel(HSL_MIXER, state.copy(hslChannel = HslChannel.Hue)) }
                    }
                }
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2400dp-xhdpi")
class CompactColorPanelScreenshotTest : ColorPanelScreenshots("compact", 411)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h2400dp-mdpi")
class ExpandedColorPanelScreenshotTest : ColorPanelScreenshots("expanded", 320)
