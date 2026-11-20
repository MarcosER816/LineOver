package dev.mark.lineover.feature.settings

import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.ml.ModelImage
import dev.mark.lineover.core.ml.ModelInfo
import dev.mark.lineover.core.ml.ModelMask
import dev.mark.lineover.core.ml.ModelRun
import dev.mark.lineover.core.ml.ModelState
import dev.mark.lineover.core.ml.SegmentPrompt
import dev.mark.lineover.core.ml.SmartModels
import dev.mark.lineover.core.ml.SmartTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Settings → AI models (Stage 16): every model listed with its tool, size and licence; delete; Wi-Fi only. */
@OptIn(ExperimentalCoroutinesApi::class)
class AiModelsSettingsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Models : SmartModels {
        override val states =
            MutableStateFlow(
                mapOf<SmartTool, ModelState>(
                    SmartTool.Subject to ModelState.Missing,
                    SmartTool.Sky to ModelState.Ready,
                    SmartTool.Object to ModelState.Downloading(15_000_000L, 30_000_000L),
                ),
            )
        override val lastRun = MutableStateFlow<ModelRun?>(null)
        val deleted = mutableListOf<SmartTool>()

        override fun info(tool: SmartTool) =
            ModelInfo(
                tool,
                "${tool.name} model",
                if (tool ==
                    SmartTool.Subject
                ) {
                    null
                } else {
                    2_348_952L
                },
                "Apache License 2.0",
                "",
                "example.org",
            )

        override suspend fun prepare(
            tool: SmartTool,
            allowMetered: Boolean,
        ) = ModelState.Ready

        override suspend fun segment(
            tool: SmartTool,
            image: ModelImage,
            prompts: List<SegmentPrompt>,
        ): ModelMask? = null

        override suspend fun delete(tool: SmartTool) {
            deleted += tool
            states.value = states.value + (tool to ModelState.Missing)
        }
    }

    @Test
    fun listsDeletesAndSwitchesWifiOnly() =
        runTest(dispatcher) {
            val models = Models()
            val prefs = InMemoryAppPreferences()
            val vm = SettingsViewModel("1.0", showDebug = false, preferences = prefs, smartModels = models)
            advanceUntilIdle()
            val rows = vm.state.value.models
            assertEquals(listOf("Subject", "Sky", "Object", "Depth"), rows.map { it.id })
            assertEquals(
                listOf("Select subject, Subject / Background mask", "Select sky, Sky / People mask", "Object select", "Depth range mask"),
                rows.map { it.usedBy },
            )
            assertEquals(listOf(false, true, false, false), rows.map { it.downloaded })
            assertEquals(0L, rows[0].sizeBytes) // Google Play services knows its size
            assertEquals("Downloading 50 %", rows[2].status)
            // Delete: the store forgets it, the row says so, and the tool will ask again.
            vm.onIntent(SettingsIntent.DeleteModel("Sky"))
            advanceUntilIdle()
            assertEquals(listOf(SmartTool.Sky), models.deleted)
            assertFalse(
                vm.state.value.models[1]
                    .downloaded,
            )
            // Wi-Fi only is on by default and is a preference.
            assertTrue(vm.state.value.modelsWifiOnly)
            vm.onIntent(SettingsIntent.SetModelsWifiOnly(false))
            advanceUntilIdle()
            assertFalse(prefs.modelsWifiOnly.value)
            assertFalse(vm.state.value.modelsWifiOnly)
        }
}
