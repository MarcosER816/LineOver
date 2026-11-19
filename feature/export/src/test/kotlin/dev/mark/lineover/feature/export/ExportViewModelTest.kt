package dev.mark.lineover.feature.export

import android.net.Uri
import dev.mark.lineover.core.io.export.ExportTarget
import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.model.ExportEstimate
import dev.mark.lineover.core.model.ExportFormat
import dev.mark.lineover.core.model.ExportSettings
import dev.mark.lineover.core.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private class FakePlatform(
        override val capabilities: ExportCapabilities = ExportCapabilities.ALL,
    ) : ExportPlatform {
        override suspend fun verifiedCapabilities() = capabilities

        val started = mutableListOf<ExportRequest>()
        var cancelled = 0

        override fun start(request: ExportRequest) {
            started += request
        }

        override fun cancel() {
            cancelled++
        }

        override suspend fun imageSize(project: Project) = 4000 to 3000

        override suspend fun sample(
            project: Project,
            settings: ExportSettings,
        ) = ExportEstimate.Sample(jpeg = 0.6f, png = 2f, webp = 0.4f, webpLossless = 1.6f)

        override suspend fun importLogo(uri: Uri) = true

        override fun hasLogo() = false
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ExportJobs.update(ExportStatus.Idle)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun changedSettingsAreRememberedAndTheEstimateFollows() =
        runTest(dispatcher) {
            val prefs = InMemoryAppPreferences()
            val vm = ExportViewModel(1, FakeExportLibrary(), prefs, FakePlatform())
            advanceUntilIdle()
            assertEquals(4000 to 3000, vm.state.value.imageSize)
            val jpeg = requireNotNull(vm.state.value.estimate)
            vm.onIntent(ExportIntent.Change(ExportSettings(format = ExportFormat.Tiff, bits = 16)))
            advanceUntilIdle()
            assertEquals(ExportFormat.Tiff, prefs.exportSettings.value.format)
            assertEquals(16, prefs.exportSettings.value.bits)
            assertEquals(4000L * 3000 * 6 + 8_192, vm.state.value.estimate)
            assertTrue(vm.state.value.estimate!! > jpeg)
            // The next sheet starts from them.
            assertEquals(
                ExportFormat.Tiff,
                ExportViewModel(1, FakeExportLibrary(), prefs, FakePlatform())
                    .state.value.settings.format,
            )
        }

    @Test
    fun quickExportUsesTheDefaultsWhateverTheSheetSays() =
        runTest(dispatcher) {
            val platform = FakePlatform()
            val prefs = InMemoryAppPreferences(exportSettings = ExportSettings(format = ExportFormat.Png, bits = 16))
            val vm = ExportViewModel(1, FakeExportLibrary(), prefs, platform)
            vm.onIntent(ExportIntent.QuickExport)
            val request = platform.started.single()
            assertEquals(ExportSettings.QUICK, request.settings)
            assertEquals(ExportTarget.Pictures, request.target)
            assertEquals(1L, request.projectId)
            // The sheet's own settings are untouched.
            assertEquals(ExportFormat.Png, vm.state.value.settings.format)
        }

    @Test
    fun exportSendsTheSheetsSettingsAndOneAtATime() =
        runTest(dispatcher) {
            val platform = FakePlatform()
            val vm = ExportViewModel(1, FakeExportLibrary(), InMemoryAppPreferences(), platform)
            vm.onIntent(ExportIntent.Change(ExportSettings(format = ExportFormat.Webp, quality = 75)))
            vm.onIntent(ExportIntent.Export(ExportTarget.Pictures))
            assertEquals(
                ExportFormat.Webp,
                platform.started
                    .single()
                    .settings.format,
            )
            ExportJobs.update(ExportStatus.Running("Harbour", ExportStep.Rendering, 0.3f))
            advanceUntilIdle()
            assertTrue(vm.state.value.busy)
            vm.onIntent(ExportIntent.Export(ExportTarget.Pictures))
            assertEquals(1, platform.started.size)
            vm.onIntent(ExportIntent.Cancel)
            assertEquals(1, platform.cancelled)
        }

    @Test
    fun aFormatWithoutAnEncoderFallsBackAndIsHidden() =
        runTest(dispatcher) {
            val noAvif = ExportCapabilities(ExportFormat.entries.toSet() - ExportFormat.Avif - ExportFormat.Heif)
            val prefs = InMemoryAppPreferences(exportSettings = ExportSettings(format = ExportFormat.Avif))
            val vm = ExportViewModel(1, FakeExportLibrary(), prefs, FakePlatform(noAvif))
            advanceUntilIdle() // the device's formats are verified in the background
            assertEquals(ExportFormat.Jpeg, vm.state.value.settings.format)
            assertNotNull(vm.state.value.capabilities)
            assertTrue(
                !vm.state.value.capabilities
                    .supports(ExportFormat.Avif),
            )
        }
}
