package dev.mark.lineover.feature.home

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.ui.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tools whose slot is on Home: merging, batch and sync settings (More, one tap), RAW and
 * .loproj files (Open file, one tap). Their slots are reserved now and filled in Stages
 * 8, 25, 27 and 28.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class HomeReachabilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun homeSlotsAreOneTapAway() {
        compose.setContent {
            LineOverTheme { HomeScreen(HomeState(loaded = true), onIntent = {}, onImport = {}, onOpenFile = {}, onOpenSettings = {}) }
        }

        // RAW development and .loproj project files come in through Open file.
        compose.onNodeWithContentDescription("Open file").assertExists()

        compose.onNodeWithContentDescription("More").performClick()
        val homeTools =
            ToolRegistry.all.filter {
                it.slot.startsWith(
                    "home/",
                ) && it.slot != "home/projects" && it.slot != "home/import/raw"
            }
        assertEquals(HOME_MORE_TOOLS.toSet(), homeTools.map { it.id }.toSet())
        homeTools.forEach { compose.onNodeWithTag("slot:${it.id}", useUnmergedTree = true).assertExists(it.id) }
    }
}
