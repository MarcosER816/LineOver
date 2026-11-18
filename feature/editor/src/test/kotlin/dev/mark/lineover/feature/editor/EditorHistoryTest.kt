package dev.mark.lineover.feature.editor

import androidx.lifecycle.ViewModelStore
import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.io.prefs.TapGestures
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.EditHistory
import dev.mark.lineover.core.model.LightParam
import dev.mark.lineover.core.model.OpParams
import dev.mark.lineover.core.model.ProjectDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stage 8 in the ViewModel: history steps, undo / redo, taps, autosave, snapshots and versions. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorHistoryTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val canvas = FakeEditorCanvas()
    private val library = FakeEditorLibrary()
    private val preferences = InMemoryAppPreferences()

    private fun TestScope.viewModel(): EditorViewModel =
        EditorViewModel(
            1,
            library,
            preferences,
            FakeCanvasFactory(canvas),
            renderDispatcher = dispatcher,
            saveScope = CoroutineScope(dispatcher),
            clock = { 42L },
        ).also { runCurrent() }

    /** A slider drag: many values, then the finger lifts. */
    private fun EditorViewModel.drag(
        param: LightParam,
        vararg values: Float,
    ) {
        values.forEach { onIntent(EditorIntent.SetLight(param, it)) }
        onIntent(EditorIntent.EndEdit)
    }

    private fun EditorViewModel.labels() =
        state.value.history.entries
            .map { it.label }

    @Test
    fun aSliderDragIsOneStepAndUndoRedoRestoreTheSliders() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.drag(LightParam.Exposure, 0.1f, 0.3f, 0.5f, 0.7f)
            vm.drag(LightParam.Contrast, 10f, 20f)
            vm.drag(LightParam.Exposure, 1f)
            assertEquals(listOf("Opened", "Exposure +0.7", "Contrast +20", "Exposure +1"), vm.labels())

            vm.onIntent(EditorIntent.Undo)
            vm.onIntent(EditorIntent.Undo)
            runCurrent()
            assertEquals(0.7f, vm.state.value.light[LightParam.Exposure])
            assertEquals(0f, vm.state.value.light[LightParam.Contrast])
            assertEquals(
                listOf(OpParams.Exposure(0.7f)),
                canvas.graphs
                    .last()
                    .layers
                    .first()
                    .ops,
            )
            vm.onIntent(EditorIntent.Redo)
            runCurrent()
            assertEquals(20f, vm.state.value.light[LightParam.Contrast])
            // A new edit after an undo drops the redo step.
            vm.drag(LightParam.Whites, 30f)
            assertFalse(vm.state.value.history.canRedo)
            assertEquals("Whites +30", vm.labels().last())
        }

    @Test
    fun twentyEditsFiveTwoFingerUndosAndAThreeFingerRedo() =
        runTest(dispatcher) {
            val vm = viewModel()
            repeat(20) { i -> vm.drag(if (i % 2 == 0) LightParam.Exposure else LightParam.Contrast, (i + 1) / 10f) }
            assertEquals(21, vm.state.value.history.entries.size)
            val after15 =
                vm.state.value.history.entries[15]
                    .graph
            repeat(5) { vm.onIntent(EditorIntent.MultiFingerTap(2)) }
            assertEquals(after15, vm.state.value.graph)
            vm.onIntent(EditorIntent.MultiFingerTap(3))
            assertEquals(
                vm.state.value.history.entries[16]
                    .graph,
                vm.state.value.graph,
            )
            assertEquals(EditorEffect.Message("Undo: Contrast +2"), vm.effects.first())
        }

    @Test
    fun tapMappingFollowsSettings() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.drag(LightParam.Exposure, 1f)
            preferences.setTapGestures(TapGestures.Swapped)
            runCurrent()
            vm.onIntent(EditorIntent.MultiFingerTap(2))
            assertEquals(1, vm.state.value.history.index)
            vm.onIntent(EditorIntent.MultiFingerTap(3))
            assertEquals(0, vm.state.value.history.index)
            preferences.setTapGestures(TapGestures.Off)
            runCurrent()
            vm.onIntent(EditorIntent.MultiFingerTap(3))
            vm.onIntent(EditorIntent.MultiFingerTap(2))
            assertEquals(0, vm.state.value.history.index)
        }

    @Test
    fun theEditAutosavesFiveSecondsAfterTheLastChangeAndOnStop() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.drag(LightParam.Exposure, 0.5f)
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(0, library.saves)
            vm.drag(LightParam.Contrast, 15f)
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("the timer restarts on every change", 0, library.saves)
            advanceTimeBy(1_100)
            runCurrent()
            assertEquals(1, library.saves)
            val saved = requireNotNull(library.saved)
            assertEquals(vm.state.value.graph, saved.graph)
            assertEquals(3, saved.active.history.entries.size)
            assertNotNull("a thumbnail for Home", library.savedThumbnails["current.jpg"])
            // onStop saves at once; nothing changed, nothing written.
            vm.onIntent(EditorIntent.SaveNow)
            runCurrent()
            assertEquals(1, library.saves)
            vm.drag(LightParam.Whites, 10f)
            vm.onIntent(EditorIntent.SaveNow)
            runCurrent()
            assertEquals(2, library.saves)
        }

    @Test
    fun closingSavesAndEndsTheEditingSession() =
        runTest(dispatcher) {
            val store = ViewModelStore()
            val vm = viewModel()
            store.put("editor", vm)
            assertTrue(library.editing)
            vm.drag(LightParam.Exposure, 0.3f)
            store.clear()
            runCurrent()
            assertEquals(1, library.saves)
            assertFalse(library.editing)
            assertTrue(canvas.closed)
        }

    @Test
    fun reopeningRestoresTheEditTheSlidersAndTheHistory() =
        runTest(dispatcher) {
            var history = EditHistory.start()
            val graph = EditGraph().withBackgroundOps(listOf(OpParams.Exposure(0.7f), OpParams.Contrast(20f)))
            history = history.record(graph, "Contrast +20", "contrast.amount")
            library.saved = ProjectDocument.of(EditGraph()).withHistory(history)
            val vm = viewModel()
            assertEquals(graph, vm.state.value.graph)
            assertEquals(0.7f, vm.state.value.light[LightParam.Exposure])
            assertEquals(graph, canvas.graphs.last())
            assertTrue(vm.state.value.history.canUndo)
        }

    @Test
    fun aRowPreviewsAndRestoreMovesTheStep() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.drag(LightParam.Exposure, 1f)
            vm.drag(LightParam.Contrast, 30f)
            vm.onIntent(EditorIntent.Preview(HistoryPreview.Step(1)))
            runCurrent()
            assertEquals(
                "the canvas shows step 1",
                vm.state.value.history.entries[1]
                    .graph,
                canvas.graphs.last(),
            )
            assertEquals("the edit is unchanged", 2, vm.state.value.history.index)
            vm.onIntent(EditorIntent.RestorePreview)
            runCurrent()
            assertEquals(1, vm.state.value.history.index)
            assertNull(vm.state.value.preview)
            assertTrue(vm.state.value.history.canRedo)
            val effect = vm.effects.first() as EditorEffect.Undoable
            assertEquals("Restored \"Exposure +1\"", effect.text)
            vm.onIntent(effect.undo)
            assertEquals(2, vm.state.value.history.index)
        }

    @Test
    fun snapshotsSaveAndApplyANamedState() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.drag(LightParam.Exposure, 1f)
            vm.onIntent(EditorIntent.OpenNameDialog(NameDialog.NewSnapshot(vm.state.value.nextSnapshotName())))
            vm.onIntent(EditorIntent.ConfirmName("Bright"))
            vm.drag(LightParam.Exposure, -1f)
            val snapshot =
                vm.state.value.document.snapshots
                    .single()
            assertEquals("Bright", snapshot.name)
            vm.onIntent(EditorIntent.Preview(HistoryPreview.SnapshotState(snapshot.id)))
            vm.onIntent(EditorIntent.RestorePreview)
            assertEquals(1f, vm.state.value.light[LightParam.Exposure])
            assertEquals("Snapshot: Bright", vm.labels().last())
            // Deleting it can be undone from the snackbar.
            vm.onIntent(EditorIntent.DeleteSnapshot(snapshot.id))
            assertTrue(
                vm.state.value.document.snapshots
                    .isEmpty(),
            )
            // The snackbars so far: "Applied snapshot", then "Deleted snapshot".
            val undo =
                vm.effects
                    .take(2)
                    .toList()
                    .last() as EditorEffect.Undoable
            assertEquals("Deleted snapshot \"Bright\"", undo.text)
            vm.onIntent(undo.undo)
            assertEquals(
                listOf("Bright"),
                vm.state.value.document.snapshots
                    .map { it.name },
            )
        }

    @Test
    fun versionsKeepTheirOwnEditAndHistory() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.drag(LightParam.Exposure, 1f)
            vm.onIntent(EditorIntent.OpenNameDialog(NameDialog.NewVersion("Version 2")))
            vm.onIntent(EditorIntent.ConfirmName("Moody"))
            vm.drag(LightParam.Contrast, 40f)
            val doc = vm.state.value.documentNow()
            assertEquals(listOf("Version 1", "Moody"), doc.versions.map { it.name })
            assertEquals("v2", doc.activeVersion)
            assertEquals(listOf("From Version 1", "Contrast +40"), vm.labels())
            vm.onIntent(EditorIntent.SwitchVersion("v1"))
            assertEquals(0f, vm.state.value.light[LightParam.Contrast])
            assertEquals(listOf("Opened", "Exposure +1"), vm.labels())
            vm.onIntent(EditorIntent.SwitchVersion("v2"))
            assertEquals(40f, vm.state.value.light[LightParam.Contrast])
            vm.onIntent(EditorIntent.DeleteVersion("v2"))
            assertEquals(
                listOf("Version 1"),
                vm.state.value.document.versions
                    .map { it.name },
            )
            assertEquals(1f, vm.state.value.light[LightParam.Exposure])
            vm.onIntent(EditorIntent.DeleteVersion("v1"))
            assertEquals("the last version stays", 1, vm.state.value.document.versions.size)
        }

    @Test
    fun aProjectThatCannotBeReadIsNeverOverwritten() =
        runTest(dispatcher) {
            library.loadFailure = "This project was saved by a newer LineOver (format 2)"
            val vm = viewModel()
            vm.drag(LightParam.Exposure, 1f)
            vm.onIntent(EditorIntent.SaveNow)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(0, library.saves)
            assertNotNull(vm.state.value.saveBlocked)
        }
}
