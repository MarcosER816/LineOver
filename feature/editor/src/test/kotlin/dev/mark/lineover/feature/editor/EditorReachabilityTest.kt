package dev.mark.lineover.feature.editor

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.ui.tools.ToolPlace
import dev.mark.lineover.core.ui.tools.ToolRegistry
import dev.mark.lineover.core.ui.tools.ToolSpec
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Stage 3 Definition of Done: every tool of the catalog has a slot reachable in at most
 * two taps. For each tool this test starts a fresh editor, performs the taps a finger
 * would (scrolling a strip is not a tap), counts them and checks the tool's slot is shown.
 *
 * Slots outside the editor are checked where they live: the export sheet
 * (`ExportReachabilityTest`, one tap on Export), Home → More / Open file
 * (`HomeReachabilityTest`) and Settings (`SettingsReachabilityTest`).
 */
abstract class EditorReachability {
    @get:Rule
    val compose = createComposeRule()

    private var taps = 0

    private sealed interface Step {
        data class Tap(
            val find: EditorReachability.() -> SemanticsNodeInteraction,
        ) : Step

        /** Scrolls [container] until [tag] is composed, then taps it. */
        data class ScrollAndTap(
            val container: String,
            val tag: String,
        ) : Step
    }

    private fun tag(tag: String): Step = Step.Tap { compose.onNodeWithTag(tag, useUnmergedTree = true) }

    private fun description(text: String): Step = Step.Tap { compose.onNodeWithContentDescription(text) }

    private fun text(text: String): Step = Step.Tap { compose.onNodeWithText(text) }

    @Suppress("CyclomaticComplexMethod") // A table: one path per kind of slot.
    private fun pathInEditor(
        tool: ToolSpec,
        wide: Boolean,
    ): List<Step>? {
        val category = tool.category
        return when {
            tool.place == ToolPlace.Strip && category != null -> {
                listOf(
                    Step.ScrollAndTap(if (wide) EditorTags.RAIL else EditorTags.CATEGORY_BAR, EditorTags.category(category.id)),
                    Step.ScrollAndTap(EditorTags.STRIP, EditorTags.tool(tool.id)),
                )
            }

            tool.place == ToolPlace.CanvasOverlay || tool.id == "workflow.guides_grid_rulers" -> {
                listOf(description("More"), tag("more:${tool.id}"))
            }

            tool.id == "history.undo_redo" -> {
                listOf(description("Undo"))
            }

            tool.id == "history.before_after" -> {
                listOf(description("Compare"))
            }

            tool.id == "history.history_list" -> {
                listOf(description("History"))
            }

            tool.id == "history.snapshots" -> {
                listOf(description("History"), text("Snapshots"))
            }

            tool.id == "history.versions" -> {
                listOf(description("History"), text("Versions"))
            }

            tool.id == "workflow.tool_search" -> {
                listOf(description("More"), tag("more:workflow.tool_search"))
            }

            // Batch's own slot is Home → More; the editor's More menu is a second way in.
            tool.id == "workflow.batch" -> {
                listOf(description("More"), tag("more:workflow.batch"))
            }

            tool.place == ToolPlace.LayersPanel && tool.slot.startsWith("panel/layers/add/") -> {
                listOf(description("Layers"), text("Add layer"))
            }

            tool.place == ToolPlace.LayersPanel -> {
                listOf(description("Layers"))
            }

            else -> {
                null
            }
        }
    }

    private fun run(step: Step) {
        when (step) {
            is Step.Tap -> {
                step.find(this).performClick()
            }

            is Step.ScrollAndTap -> {
                compose.onNodeWithTag(step.container).performScrollToNode(hasTestTag(step.tag))
                compose.onNodeWithTag(step.tag).performClick()
            }
        }
        taps++
        compose.waitForIdle()
    }

    protected fun everyEditorSlotIsReachableInTwoTaps(wide: Boolean) {
        var generation by mutableIntStateOf(0)
        compose.setContent {
            LineOverTheme {
                key(generation) {
                    val vm = remember { EditorViewModel(1, FakeEditorLibrary(), InMemoryAppPreferences(), FakeCanvasFactory()) }
                    val state by vm.state.collectAsState()
                    EditorScreen(state, vm::onIntent, onBack = {}, onExport = {}, autoFocusSearch = false)
                }
            }
        }
        val outside = mutableListOf<ToolSpec>()
        for (tool in ToolRegistry.all) {
            val path = pathInEditor(tool, wide)
            if (path == null) {
                outside += tool
                continue
            }
            generation++
            compose.waitForIdle()
            taps = 0
            path.forEach(::run)
            assertTrue("${tool.id} took $taps taps", taps <= 2)
            compose.onNodeWithTag(EditorTags.slot(tool.id), useUnmergedTree = true).assertExists("slot of ${tool.id} after $taps taps")
        }
        // Everything not in the editor lives in the export sheet, on Home or in Settings.
        val elsewhere =
            outside.filterNot {
                it.place in setOf(ToolPlace.ExportSheet, ToolPlace.Home, ToolPlace.Import) ||
                    it.slot.startsWith("home/") ||
                    it.slot.startsWith("settings/")
            }
        assertTrue("tools with no tested slot: $elsewhere", elsewhere.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class CompactEditorReachabilityTest : EditorReachability() {
    @Test
    fun everySlotInTwoTaps() = everyEditorSlotIsReachableInTwoTaps(wide = false)

    @Test
    fun exportIsOneTapAway() {
        var exported = false
        compose.setContent {
            LineOverTheme {
                val vm = remember { EditorViewModel(1, FakeEditorLibrary(), InMemoryAppPreferences(), FakeCanvasFactory()) }
                val state by vm.state.collectAsState()
                EditorScreen(state, vm::onIntent, onBack = {}, onExport = { exported = true })
            }
        }
        compose.onNodeWithContentDescription("Export").performClick()
        assertTrue(exported)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
class ExpandedEditorReachabilityTest : EditorReachability() {
    @Test
    fun everySlotInTwoTaps() = everyEditorSlotIsReachableInTwoTaps(wide = true)

    @Test
    fun theRailAndTheSideColumnAreUsed() {
        compose.setContent {
            LineOverTheme {
                val vm = remember { EditorViewModel(1, FakeEditorLibrary(), InMemoryAppPreferences(), FakeCanvasFactory()) }
                val state by vm.state.collectAsState()
                EditorScreen(state, vm::onIntent, onBack = {}, onExport = {})
            }
        }
        compose.onNodeWithTag(EditorTags.RAIL).assertIsDisplayed()
        compose.onNodeWithTag(EditorTags.CATEGORY_BAR).assertDoesNotExist()
        // With no panel open the right column lists the category's tools.
        compose.onNodeWithTag("list:light.exposure", useUnmergedTree = true).assertExists()
    }
}
