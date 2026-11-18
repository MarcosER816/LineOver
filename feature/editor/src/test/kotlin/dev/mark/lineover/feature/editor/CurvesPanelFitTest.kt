package dev.mark.lineover.feature.editor

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.model.ToolCategory
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * On phones the whole point-curves panel (channels, graph, Free curve, Reset) fits the
 * panel without scrolling, and the photo stays visible above it (Mark's feedback, Stage 7).
 */
abstract class CurvesPanelFit {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theWholeCurvesPanelFitsAndThePhotoStaysVisible() {
        compose.setContent {
            LineOverTheme {
                EditorScreen(
                    EditorState(
                        project = FakeEditorLibrary.PROJECT,
                        loaded = true,
                        category = ToolCategory.Light,
                        panel = EditorPanel.Tool("light.curves_point"),
                        grade = SAMPLE_GRADE,
                    ),
                    onIntent = {},
                    onBack = {},
                    onExport = {},
                )
            }
        }
        compose.waitForIdle()
        val panel = compose.onNodeWithTag(EditorTags.PANEL).getBoundsInRoot()
        // The canvas area: below the top bar, above the tool strip.
        val areaTop = compose.onNodeWithContentDescription("Back").getBoundsInRoot().bottom
        val areaBottom = compose.onNodeWithTag(EditorTags.STRIP, useUnmergedTree = true).getBoundsInRoot().top
        val graph = compose.onNodeWithTag(EditorTags.CURVE_EDITOR, useUnmergedTree = true).getBoundsInRoot()
        val reset = compose.onNodeWithText("Reset RGB").getBoundsInRoot()
        assertTrue("graph $graph outside the panel $panel", graph.top >= panel.top && graph.bottom <= panel.bottom)
        assertTrue("Reset $reset cut off by the panel $panel", reset.bottom <= panel.bottom)
        val photoVisible = (panel.top - areaTop) / (areaBottom - areaTop)
        assertTrue("only ${(photoVisible * 100).toInt()} % of the canvas is visible", photoVisible >= 0.55f)
    }
}

/** The POCO F7 Ultra: 411 x 914 dp, minus the status and navigation bars. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h840dp-xxhdpi")
class PocoCurvesPanelFitTest : CurvesPanelFit()

/** A small phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class SmallPhoneCurvesPanelFitTest : CurvesPanelFit()
