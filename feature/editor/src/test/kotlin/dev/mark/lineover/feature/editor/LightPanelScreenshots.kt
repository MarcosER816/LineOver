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
import dev.mark.lineover.core.model.LightParam
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Light panel as a state sheet (Stage 5): collapsed (selected slider), all sliders,
 * swipe mode on, and at fontScale 2.0, at compact and expanded widths.
 */
abstract class LightPanelScreenshots(
    private val widthClass: String,
    private val panelWidth: Int,
) {
    private val state =
        EditorState(
            panel = EditorPanel.Tool("light.highlights"),
            lightParam = LightParam.Highlights,
            light = SAMPLE_LIGHT,
        )

    @Composable
    private fun Label(text: String) {
        Text(text, style = LoType.Caption, color = LoColors.TextSecondary, modifier = Modifier.padding(LoSpacing.Xs))
    }

    @Test
    fun stateSheet() {
        captureRoboImage("editor/light_panel/$widthClass.png") {
            LineOverTheme {
                Column(Modifier.width(panelWidth.dp).background(LoColors.Surface1)) {
                    Label("collapsed: the selected slider")
                    LightPanel(state, {}, showAll = false, onShowAll = {}, onClose = {})
                    Label("all sliders (swipe up / wide layouts)")
                    LightPanel(state, {}, showAll = true, onShowAll = null, onClose = {})
                    Label("swipe mode on")
                    LightPanel(state.copy(swipeMode = true), {}, showAll = false, onShowAll = {}, onClose = {})
                    Label("fontScale 2.0")
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                        Column(Modifier.fillMaxWidth()) {
                            LightPanel(state, {}, showAll = false, onShowAll = {}, onClose = {})
                        }
                    }
                }
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1400dp-xhdpi")
class CompactLightPanelScreenshotTest : LightPanelScreenshots("compact", 411)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1400dp-mdpi")
class ExpandedLightPanelScreenshotTest : LightPanelScreenshots("expanded", 320)
