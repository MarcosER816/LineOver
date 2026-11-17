package dev.mark.lineover

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The debug build's debug menu opens the component gallery, and Back returns. */
@RunWith(AndroidJUnit4::class)
class DebugMenuTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun debugMenuOpensComponentGallery() {
        composeRule.onNodeWithContentDescription("Debug menu").performClick()
        composeRule.onNodeWithText("Component gallery").performClick()

        composeRule.onNodeWithText("Component gallery").assertIsDisplayed()
        composeRule.onNodeWithText("LoSlider").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("LineOver").assertIsDisplayed()
    }
}
