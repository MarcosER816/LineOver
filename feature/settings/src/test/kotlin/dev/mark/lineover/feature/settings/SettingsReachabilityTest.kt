package dev.mark.lineover.feature.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.io.prefs.CanvasSurround
import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.ui.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsReachabilityTest {
    @get:Rule
    val compose = createComposeRule()

    /** Mini tutorials (`settings/tutorials`) live in the Gestures row, one tap from Settings. */
    @Test
    fun tutorialsSlotIsTheGesturesRow() {
        val tool = requireNotNull(ToolRegistry["workflow.mini_tutorials"])
        assertEquals("settings/tutorials", tool.slot)
        compose.setContent {
            LineOverTheme {
                SettingsScreen(SettingsState(), onIntent = {}, onBack = {}, onOpenSection = {}, onOpenGallery = null, twoPane = false)
            }
        }
        compose.onNodeWithTag("section:${SettingsSection.GesturesHelp.id}").performScrollTo().assertExists()
    }

    /** Canvas surround (the Stage 3 row) switches between dark and mid grey and is saved. */
    @Test
    fun surroundIsSaved() {
        val prefs = InMemoryAppPreferences()
        compose.setContent {
            LineOverTheme { SettingsSectionScreen(SettingsSection.SurroundColor, "0.3.0", prefs, onBack = {}, onOpenGallery = null) }
        }
        compose.onNodeWithText("Mid grey").performClick()
        assertEquals(CanvasSurround.MidGrey, prefs.surround.value)
        compose.onNodeWithText("Dark").performClick()
        assertEquals(CanvasSurround.Dark, prefs.surround.value)
    }
}
