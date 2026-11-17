package dev.mark.lineover

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mark.lineover.core.io.project.ImportResult
import dev.mark.lineover.core.io.project.ProjectLibraries
import dev.mark.lineover.feature.editor.EditorTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Every route on a device: Home → Editor → Export sheet → back, and Home → Settings →
 * section → back. The project is imported through the real library (the system photo
 * picker cannot be driven from a test).
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app = ApplicationProvider.getApplicationContext<LineOverApp>()

    private fun importTestPhoto(name: String): Long {
        val file = File(app.cacheDir, "$name.png")
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(90, 140, 220)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val result = runBlocking { app.container.projects.importPicked(listOf(Uri.fromFile(file))) }
        return (result as ImportResult.Imported).ids.single()
    }

    @Test
    fun homeEditorExportAndBack() {
        val name = "Nav test ${System.nanoTime()}"
        importTestPhoto(name)

        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(name).performClick()

        // The editor opens with its first-open coach marks; Skip dismisses them for good.
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithContentDescription("Layers").fetchSemanticsNodes().isNotEmpty() }
        if (composeRule.onAllNodesWithTag(EditorTags.COACH).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Skip").performClick()
        }
        // The coach-mark scrim swallows taps: make sure it is gone before tapping Export.
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(EditorTags.COACH).fetchSemanticsNodes().isEmpty() }
        // Export stays on screen at any width; Layers may sit in the scrolling actions (narrow windows).
        composeRule.onNodeWithContentDescription("Export").assertIsDisplayed()
        composeRule.onNodeWithText("Exposure").assertIsDisplayed()

        // Export is enabled once the project has loaded (asynchronously on a device).
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasContentDescription("Export") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Export").performClick()
        // The export sheet is a dialog: its window attaches asynchronously on a device.
        val caption = "Exports are saved to Pictures/LineOver. The original photo is never changed."
        try {
            composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(caption).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            // Diagnostics for the CI log: every compose root (the editor and any dialog) as seen now.
            composeRule.onAllNodes(isRoot()).printToLog("LineOverNav", maxDepth = Int.MAX_VALUE)
            throw e
        }
        composeRule.onNodeWithContentDescription("Close").performClick()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("LineOver").assertIsDisplayed()
    }

    @Test
    fun settingsSectionsAndDebugGallery() {
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()

        composeRule.onNodeWithText("About and licences").performScrollTo().performClick()
        composeRule.onNodeWithText("Open-source licences").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.onNodeWithText("Debug").performScrollTo().performClick()
        composeRule.onNodeWithText("Component gallery").assertIsDisplayed()
        composeRule.onNodeWithText("LoSlider").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("LineOver").assertIsDisplayed()
    }

    /** What "kill the app and reopen" relies on: a new database connection still sees the project. */
    @Test
    fun projectsSurviveANewDatabaseConnection() {
        val id = importTestPhoto("Persist ${System.nanoTime()}")

        val reopened = ProjectLibraries.create(app)

        val project = runBlocking { reopened.observe(id).first() }
        assertTrue(project != null && project.thumbPath != null)
    }
}
