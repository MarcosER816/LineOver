package dev.mark.lineover.feature.home

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mark.lineover.core.io.project.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val library = FakeProjectLibrary()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel() = HomeViewModel(library, cleanupScope = this, undoMillis = 1000)

    @Test
    fun listsFollowTheLibrary() =
        runTest(dispatcher) {
            library.add("Old", modified = 1)
            library.add("New", modified = 2)
            val vm = viewModel()
            runCurrent()

            assertTrue(vm.state.value.loaded)
            assertEquals(
                listOf("New", "Old"),
                vm.state.value.projects
                    .map { it.name },
            )
        }

    @Test
    fun importingOnePhotoOpensTheEditor() =
        runTest(dispatcher) {
            val vm = viewModel()
            library.nextImport = ImportResult.Imported(listOf(42L), failed = 0)

            vm.onIntent(HomeIntent.ImportPicked(listOf(Uri.parse("content://media/1"))))
            runCurrent()

            assertEquals(HomeEffect.OpenEditor(42L), vm.effects.first())
            assertEquals(false, vm.state.value.importing)
        }

    @Test
    fun importingSeveralPhotosStaysOnHomeAndReports() =
        runTest(dispatcher) {
            val vm = viewModel()
            library.nextImport = ImportResult.Imported(listOf(1L, 2L), failed = 1)

            vm.onIntent(HomeIntent.ImportPicked(List(3) { Uri.parse("content://media/$it") }))
            runCurrent()

            assertEquals(HomeEffect.Message("Imported 2 of 3; 1 could not be read"), vm.effects.first())
        }

    @Test
    fun cancelledPickerDoesNothing() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onIntent(HomeIntent.ImportPicked(emptyList()))
            vm.onIntent(HomeIntent.OpenDocument(null))
            runCurrent()

            assertEquals(false, vm.state.value.importing)
        }

    @Test
    fun projectFilesAreExplained() =
        runTest(dispatcher) {
            val vm = viewModel()
            library.nextImport = ImportResult.ProjectFileNotYetSupported

            vm.onIntent(HomeIntent.OpenDocument(Uri.parse("content://docs/trip.loproj")))
            runCurrent()

            assertEquals(HomeEffect.Message("Opening .loproj project files arrives in Stage 8"), vm.effects.first())
        }

    @Test
    fun deleteCanBeUndoneWithinTheWindow() =
        runTest(dispatcher) {
            val project = library.add("Beach", modified = 1)
            val vm = viewModel()
            runCurrent()

            vm.onIntent(HomeIntent.Delete(project.id))
            runCurrent()
            assertEquals(project, vm.state.value.pendingDelete)
            assertTrue(
                vm.state.value.projects
                    .isEmpty(),
            )

            vm.onIntent(HomeIntent.UndoDelete)
            advanceUntilIdle()

            assertNull(vm.state.value.pendingDelete)
            assertEquals(listOf(project), vm.state.value.projects)
            assertTrue(library.purged.isEmpty())
        }

    @Test
    fun deleteBecomesFinalAfterTheUndoWindow() =
        runTest(dispatcher) {
            val project = library.add("Beach", modified = 1)
            val vm = viewModel()
            runCurrent()

            vm.onIntent(HomeIntent.Delete(project.id))
            runCurrent()
            advanceTimeBy(1001)
            runCurrent()

            assertNull(vm.state.value.pendingDelete)
            assertEquals(listOf(project), library.purged)
        }

    @Test
    fun renameAndDuplicate() =
        runTest(dispatcher) {
            val project = library.add("Beach", modified = 1)
            val vm = viewModel()
            runCurrent()

            vm.onIntent(HomeIntent.StartRename(project))
            assertEquals(project, vm.state.value.renaming)
            vm.onIntent(HomeIntent.ConfirmRename(project.id, "Sunset"))
            vm.onIntent(HomeIntent.Duplicate(project.id))
            runCurrent()

            assertNull(vm.state.value.renaming)
            assertEquals(
                setOf("Sunset", "Sunset copy"),
                vm.state.value.projects
                    .map { it.name }
                    .toSet(),
            )
        }
}
