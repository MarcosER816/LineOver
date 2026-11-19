package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.io.lens.LensProfileState
import dev.mark.lineover.core.io.lens.LensProfileStore
import dev.mark.lineover.core.io.lens.LensfunFile
import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.model.OpParams
import dev.mark.lineover.core.model.PhotoExif
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/** Stage 9 lens profiles in the editor: download on request, match from the EXIF, pick, switch off. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LensProfileEditingTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val fixture: ByteArray by lazy {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        File(requireNotNull(dir), "testdata/lensfun/fixture.xml").readBytes()
    }

    private fun store(): LensProfileStore {
        val sha = MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) }
        return LensProfileStore(
            dir = File(temp.root, "lensfun"),
            files = listOf(LensfunFile("fixture.xml", fixture.size.toLong(), sha)),
            baseUrl = "https://example.invalid/",
            fetch = { ByteArrayInputStream(fixture) },
            io = dispatcher,
        )
    }

    private val exif = PhotoExif("SONY", "ILCE-7M3", "Sony", "FE 24-70mm F2.8 GM", focal = 35.0, focal35 = 35.0, aperture = 4.0)

    private fun TestScope.viewModel(store: LensProfileStore): EditorViewModel =
        EditorViewModel(
            1,
            FakeEditorLibrary(),
            InMemoryAppPreferences(),
            FakeCanvasFactory(FakeEditorCanvas(6000, 4000, exif)),
            renderDispatcher = dispatcher,
            saveScope = CoroutineScope(dispatcher),
            clock = { 42L },
            lensProfiles = store,
        ).also { runCurrent() }

    private fun EditorViewModel.act(action: GeometryAction) = onIntent(EditorIntent.Geometry(action))

    private val EditorViewModel.op: OpParams.Geometry?
        get() =
            state.value.graph.layers
                .firstOrNull()
                ?.ops
                ?.filterIsInstance<OpParams.Geometry>()
                ?.firstOrNull()

    @Test
    fun downloadThenTheMatchedProfileCorrectsThePhoto() =
        runTest(dispatcher) {
            val vm = viewModel(store())
            assertEquals(LensProfileState.Missing, vm.state.value.lensProfiles)
            vm.act(GeometryAction.UseProfile(true))
            runCurrent()
            assertNull("nothing before the download", vm.state.value.geometry.profile)

            vm.act(GeometryAction.DownloadProfiles)
            runCurrent()
            assertTrue(vm.state.value.lensProfiles is LensProfileState.Installed)
            vm.act(GeometryAction.LoadLensCandidates)
            runCurrent()
            assertEquals(
                "Sony FE 24-70mm f/2.8 GM",
                vm.state.value.lensCandidates
                    .first(),
            )

            vm.act(GeometryAction.UseProfile(true))
            runCurrent()
            val profile = requireNotNull(vm.state.value.geometry.profile)
            assertEquals("Sony FE 24-70mm f/2.8 GM", profile.lens)
            assertEquals("ILCE-7M3", profile.camera)
            val op = requireNotNull(vm.op)
            assertEquals(4, op.lensDistortion.size)
            assertEquals(3, op.vignetteProfile.size)
            assertEquals(
                "Lens profile Sony FE 24-70mm f/2.8 GM",
                vm.state.value.history.entries
                    .last()
                    .label,
            )

            // Half the profile's distortion, no chromatic aberration.
            vm.act(GeometryAction.Set(dev.mark.lineover.core.model.GeometryParam.ProfileDistortion, 50f))
            vm.act(GeometryAction.ProfileChromatic(false))
            assertEquals(profile.distortion.map { it * 0.5 }, vm.op!!.lensDistortion)
            assertTrue(vm.op!!.lensTcaRed.isEmpty())

            vm.act(GeometryAction.UseProfile(false))
            assertNull(vm.state.value.geometry.profile)
            assertTrue(vm.op?.lensDistortion.isNullOrEmpty())
        }

    @Test
    fun anotherLensCanBePicked() =
        runTest(dispatcher) {
            val store = store()
            store.download()
            val vm = viewModel(store)
            vm.act(GeometryAction.UseProfile(true, "Sony E 24mm f/1.8 ZA"))
            runCurrent()
            assertEquals(
                "Sony E 24mm f/1.8 ZA",
                vm.state.value.geometry.profile
                    ?.lens,
            )
        }
}
