package dev.mark.lineover.feature.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.mark.lineover.core.designsystem.LineOverTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A 320 dp window (the CI emulator; a phone with a large display size or in split screen)
 * is narrower than the eight top-bar buttons (384 dp). Back, More and Export must stay
 * fully on screen; the other actions scroll.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
class NarrowEditorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun exportMoreAndBackStayOnScreenAndEveryActionIsReachable() {
        var exported = false
        compose.setContent {
            LineOverTheme {
                EditorScreen(
                    EditorState(project = FakeEditorLibrary.PROJECT, loaded = true),
                    onIntent = {},
                    onBack = {},
                    onExport = { exported = true },
                )
            }
        }
        val screenRight = compose.onRoot().getBoundsInRoot().right
        listOf("Back", "More", "Export").forEach { name ->
            val bounds = compose.onNodeWithContentDescription(name).assertIsDisplayed().getBoundsInRoot()
            assertTrue("$name is cut off: $bounds", bounds.right <= screenRight && bounds.right - bounds.left >= 48.dp)
        }
        compose.onNodeWithContentDescription("Export").performClick()
        assertTrue(exported)
        // The scrolling actions are all reachable.
        listOf("Undo", "Redo", "Compare", "History", "Layers").forEach {
            compose.onNodeWithContentDescription(it).performScrollTo().assertIsDisplayed()
        }
        compose.onRoot().captureRoboImage("editor/narrow/top_bar_320dp.png")
    }
}
