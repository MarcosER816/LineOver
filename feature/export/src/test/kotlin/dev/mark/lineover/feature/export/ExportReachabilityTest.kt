package dev.mark.lineover.feature.export

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.ui.tools.ToolPlace
import dev.mark.lineover.core.ui.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every export tool has its slot in the export sheet, which is one tap (Export) from the editor. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ExportReachabilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyExportToolHasASlotInTheSheet() {
        compose.setContent { LineOverTheme { ExportSheet(ExportState("Harbour at dusk"), onClose = {}) } }
        val tools = ToolRegistry.inPlace(ToolPlace.ExportSheet)
        assertEquals(11, tools.size)
        tools.forEach { compose.onNodeWithTag("slot:${it.id}", useUnmergedTree = true).assertExists(it.id) }
    }
}
