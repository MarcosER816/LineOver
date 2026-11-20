package dev.mark.lineover.feature.settings

import androidx.lifecycle.viewModelScope
import dev.mark.lineover.core.io.lens.LensProfileState
import dev.mark.lineover.core.io.lens.LensProfileStore
import dev.mark.lineover.core.io.lens.LensfunManifest
import dev.mark.lineover.core.io.prefs.AppPreferences
import dev.mark.lineover.core.io.prefs.CanvasSurround
import dev.mark.lineover.core.io.prefs.TapGestures
import dev.mark.lineover.core.ml.ModelState
import dev.mark.lineover.core.ml.SmartModels
import dev.mark.lineover.core.ml.SmartTool
import dev.mark.lineover.core.ui.mvi.MviViewModel
import kotlinx.coroutines.launch

/** A downloaded (or downloadable) AI model as shown in Settings → AI models. */
data class AiModelEntry(
    val id: String,
    val name: String,
    /** 0 when Google Play services downloads it (it knows the size). */
    val sizeBytes: Long,
    val licence: String,
    val downloaded: Boolean,
    /** The tool that uses it (Stage 16). */
    val usedBy: String = "",
    /** Downloading / failed, when not simply downloaded or not. */
    val status: String? = null,
)

data class SettingsState(
    val sections: List<SettingsSection> = SettingsSection.entries.filter { it != SettingsSection.Debug },
    /** Section shown in the detail pane on wide windows. */
    val selected: SettingsSection = SettingsSection.AiModels,
    /** Every AI model (Stage 16, rule D3): downloaded on first use, deletable here. */
    val models: List<AiModelEntry> = emptyList(),
    /** Models download on Wi-Fi only (default on). */
    val modelsWifiOnly: Boolean = true,
    val versionName: String = "",
    val surround: CanvasSurround = CanvasSurround.Dark,
    val tapGestures: TapGestures = TapGestures.Standard,
    /** The Lensfun lens profile database (Stage 9): downloaded on request, deletable. */
    val lensProfiles: LensProfileState = LensProfileState.Missing,
    val lensProfilesBytes: Long = LensfunManifest.TOTAL_BYTES,
)

sealed interface SettingsIntent {
    data class Select(
        val section: SettingsSection,
    ) : SettingsIntent

    data class DeleteModel(
        val id: String,
    ) : SettingsIntent

    data class SetSurround(
        val surround: CanvasSurround,
    ) : SettingsIntent

    data class SetTapGestures(
        val gestures: TapGestures,
    ) : SettingsIntent

    data object DownloadLensProfiles : SettingsIntent

    data object DeleteLensProfiles : SettingsIntent

    data class SetModelsWifiOnly(
        val wifiOnly: Boolean,
    ) : SettingsIntent
}

private const val PERCENT = 100

class SettingsViewModel(
    versionName: String,
    showDebug: Boolean,
    private val preferences: AppPreferences,
    private val lensProfiles: LensProfileStore? = null,
    private val smartModels: SmartModels? = null,
) : MviViewModel<SettingsState, SettingsIntent, Nothing>(
        SettingsState(
            sections = SettingsSection.entries.filter { showDebug || it != SettingsSection.Debug },
            versionName = versionName,
            surround = preferences.surround.value,
            tapGestures = preferences.tapGestures.value,
        ),
    ) {
    init {
        viewModelScope.launch { preferences.surround.collect { setState { copy(surround = it) } } }
        viewModelScope.launch { preferences.tapGestures.collect { setState { copy(tapGestures = it) } } }
        lensProfiles?.let { store -> viewModelScope.launch { store.state.collect { setState { copy(lensProfiles = it) } } } }
        viewModelScope.launch { preferences.modelsWifiOnly.collect { setState { copy(modelsWifiOnly = it) } } }
        smartModels?.let { models -> viewModelScope.launch { models.states.collect { setState { copy(models = entries(models, it)) } } } }
    }

    private fun entries(
        models: SmartModels,
        states: Map<SmartTool, ModelState>,
    ): List<AiModelEntry> =
        SmartTool.entries.map { tool ->
            val info = models.info(tool)
            val state = states[tool] ?: ModelState.Missing
            AiModelEntry(
                id = tool.name,
                name = info.name,
                sizeBytes = info.sizeBytes ?: 0L,
                licence = info.license,
                downloaded = state == ModelState.Ready,
                usedBy = tool.label,
                status =
                    when (state) {
                        is ModelState.Downloading -> "Downloading ${(state.fraction * PERCENT).toInt()} %"
                        ModelState.Verifying -> "Checking"
                        is ModelState.Failed -> "Not downloaded (${state.error.name.lowercase()})"
                        else -> null
                    },
            )
        }

    override fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.Select -> {
                setState { copy(selected = intent.section) }
            }

            // The next use of the tool shows its download sheet again (rule D3).
            is SettingsIntent.DeleteModel -> {
                val tool = SmartTool.entries.firstOrNull { it.name == intent.id }
                if (tool != null) smartModels?.let { viewModelScope.launch { it.delete(tool) } }
            }

            is SettingsIntent.SetModelsWifiOnly -> {
                preferences.setModelsWifiOnly(intent.wifiOnly)
            }

            is SettingsIntent.SetSurround -> {
                preferences.setSurround(intent.surround)
            }

            is SettingsIntent.SetTapGestures -> {
                preferences.setTapGestures(intent.gestures)
            }

            is SettingsIntent.DownloadLensProfiles -> {
                lensProfiles?.let { viewModelScope.launch { it.download() } }
            }

            is SettingsIntent.DeleteLensProfiles -> {
                lensProfiles?.let { viewModelScope.launch { it.delete() } }
            }
        }
    }
}
