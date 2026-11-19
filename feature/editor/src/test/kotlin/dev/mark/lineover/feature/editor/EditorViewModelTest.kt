package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.ToolCategory
import dev.mark.lineover.feature.editor.canvas.CanvasOpenException
import dev.mark.lineover.feature.editor.canvas.EditorCanvasFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        prefs: InMemoryAppPreferences = InMemoryAppPreferences(),
        canvases: EditorCanvasFactory = FakeCanvasFactory(),
    ) = EditorViewModel(1, FakeEditorLibrary(), prefs, canvases, renderDispatcher = Dispatchers.Main)

    @Test
    fun opensWithTheLastUsedCategoryAndMarksTheProjectOpened() {
        val library = FakeEditorLibrary()
        val vm = EditorViewModel(1, library, InMemoryAppPreferences(lastCategory = "retouch"), FakeCanvasFactory())
        assertEquals(ToolCategory.Retouch, vm.state.value.category)
        assertEquals(1, library.opened)
        assertEquals(
            "Harbour at dusk",
            vm.state.value.project
                ?.name,
        )
    }

    @Test
    fun firstOpenDefaultsToLightAndRemembersTheCategory() {
        val prefs = InMemoryAppPreferences()
        val vm = viewModel(prefs)
        assertEquals(ToolCategory.Light, vm.state.value.category)
        vm.onIntent(EditorIntent.SelectCategory(ToolCategory.Filters))
        assertEquals("filters", prefs.lastCategory.value)
    }

    @Test
    fun aMissingPreviewIsReportedNotHidden() {
        val vm = viewModel(canvases = FakeCanvasFactory(canvas = null))
        assertTrue(vm.state.value.previewUnavailable)
        assertNull(vm.state.value.canvas)
    }

    @Test
    fun anEngineThatCannotStartIsReportedNotACrash() {
        val vm =
            viewModel(
                canvases = EditorCanvasFactory { throw CanvasOpenException("shader compile failed: x") },
            )
        assertTrue(vm.state.value.previewUnavailable)
        assertEquals("shader compile failed: x", vm.state.value.engineError)
        assertNull(vm.state.value.canvas)
    }

    @Test
    fun theEngineOpensAndReceivesTheGraph() {
        val canvas = FakeEditorCanvas()
        val vm = viewModel(canvases = FakeCanvasFactory(canvas))
        assertEquals(canvas, vm.state.value.canvas)
        assertEquals(
            8160,
            vm.state.value.image
                ?.width,
        )
        assertFalse(vm.state.value.previewUnavailable)
        assertEquals(listOf(EditGraph()), canvas.graphs)
    }

    @Test
    fun theSpinnerShowsOnlyAfter300Ms() =
        runTest {
            val canvas = FakeEditorCanvas()
            val vm = viewModel(canvases = FakeCanvasFactory(canvas))
            canvas.loading.value = true
            assertFalse(vm.state.value.showSpinner)
            advanceTimeBy(EditorViewModel.SPINNER_DELAY_MILLIS + 1)
            assertTrue(vm.state.value.showSpinner)
            canvas.loading.value = false
            assertFalse(vm.state.value.showSpinner)
        }

    @Test
    fun aQuickLoadNeverShowsTheSpinner() =
        runTest {
            val canvas = FakeEditorCanvas()
            val vm = viewModel(canvases = FakeCanvasFactory(canvas))
            canvas.loading.value = true
            advanceTimeBy(EditorViewModel.SPINNER_DELAY_MILLIS - 50)
            canvas.loading.value = false
            advanceTimeBy(1_000)
            assertFalse(vm.state.value.showSpinner)
        }

    @Test
    fun selectingAToolOpensItsPanelAndAgainClosesIt() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.SelectTool("light.contrast"))
        assertEquals(EditorPanel.Tool("light.contrast"), vm.state.value.panel)
        vm.onIntent(EditorIntent.SelectTool("light.contrast"))
        assertNull(vm.state.value.panel)
    }

    @Test
    fun aToolFromSearchSwitchesToItsCategory() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.OpenSearch)
        vm.onIntent(EditorIntent.Query("erase object"))
        val first =
            vm.state.value.searchResults
                .first()
        vm.onIntent(EditorIntent.SelectTool(first.id))
        assertEquals(ToolCategory.Retouch, vm.state.value.category)
        assertEquals(EditorPanel.Tool("retouch.ai_remove"), vm.state.value.panel)
        assertFalse(vm.state.value.searchOpen)
    }

    @Test
    fun changingCategoryClosesTheOldToolButKeepsSheets() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.SelectTool("light.exposure"))
        vm.onIntent(EditorIntent.SelectCategory(ToolCategory.Color))
        assertNull(vm.state.value.panel)
        vm.onIntent(EditorIntent.OpenPanel(EditorPanel.Layers))
        vm.onIntent(EditorIntent.SelectCategory(ToolCategory.Crop))
        assertEquals(EditorPanel.Layers, vm.state.value.panel)
    }

    @Test
    fun longPressPinsAndUnpinsFavoritesAndSaysSo() =
        runTest {
            val prefs = InMemoryAppPreferences()
            val vm = viewModel(prefs)
            vm.onIntent(EditorIntent.ToggleFavorite("retouch.spot_healing"))
            assertEquals(listOf("retouch.spot_healing"), prefs.favorites.value)
            assertEquals(EditorEffect.Message("Added Spot healing to Favorites"), vm.effects.first())
            vm.onIntent(EditorIntent.SelectCategory(ToolCategory.Favorites))
            assertEquals(
                listOf("favorites.favorites", "retouch.spot_healing"),
                vm.state.value.strip
                    .map { it.id },
            )
            vm.onIntent(EditorIntent.ToggleFavorite("retouch.spot_healing"))
            assertTrue(prefs.favorites.value.isEmpty())
        }

    @Test
    fun openingAFavoriteKeepsTheFavoritesStrip() {
        val vm = viewModel(InMemoryAppPreferences(favorites = listOf("color.vibrance")))
        vm.onIntent(EditorIntent.SelectCategory(ToolCategory.Favorites))
        vm.onIntent(EditorIntent.SelectTool("color.vibrance"))
        assertEquals(ToolCategory.Favorites, vm.state.value.category)
        assertEquals(EditorPanel.Tool("color.vibrance"), vm.state.value.panel)
    }

    @Test
    fun coachMarksShowOnFirstOpenAndAreDismissedForever() {
        val prefs = InMemoryAppPreferences(coachMarksSeen = false)
        val vm = viewModel(prefs)
        assertEquals(0, vm.state.value.coachStep)
        vm.onIntent(EditorIntent.NextCoachMark)
        vm.onIntent(EditorIntent.NextCoachMark)
        assertEquals(2, vm.state.value.coachStep)
        vm.onIntent(EditorIntent.NextCoachMark)
        assertNull(vm.state.value.coachStep)
        assertTrue(prefs.coachMarksSeen.value)
        assertNull(viewModel(prefs).state.value.coachStep)
    }

    @Test
    fun skipAlsoDismissesForeverAndHelpShowsThemAgain() {
        val prefs = InMemoryAppPreferences(coachMarksSeen = false)
        val vm = viewModel(prefs)
        vm.onIntent(EditorIntent.DismissCoachMarks)
        assertTrue(prefs.coachMarksSeen.value)
        vm.onIntent(EditorIntent.ShowHelp)
        assertEquals(0, vm.state.value.coachStep)
    }

    @Test
    fun overlaysToggleFromTheMoreMenu() {
        val vm = viewModel()
        vm.onIntent(EditorIntent.OpenMenu(EditorMenu.More))
        vm.onIntent(EditorIntent.ToggleOverlay("light.histogram"))
        assertEquals(setOf("light.histogram"), vm.state.value.overlays)
        assertNull(vm.state.value.menu)
        vm.onIntent(EditorIntent.ToggleOverlay("light.histogram"))
        assertTrue(
            vm.state.value.overlays
                .isEmpty(),
        )
    }
}
