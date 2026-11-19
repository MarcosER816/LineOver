package dev.mark.lineover.feature.settings

import androidx.lifecycle.viewModelScope
import dev.mark.lineover.core.io.lens.LensProfileState
import dev.mark.lineover.core.io.lens.LensProfileStore
import dev.mark.lineover.core.io.lens.LensfunManifest
import dev.mark.lineover.core.io.prefs.AppPreferences
import dev.mark.lineover.core.io.prefs.CanvasSurround
import dev.mark.lineover.core.io.prefs.TapGestures
import dev.mark.lineover.core.ui.mvi.MviViewModel
import kotlinx.coroutines.launch

/** A downloaded (or downloadable) AI model as shown in Settings → AI models. */
data class AiModelEntry(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val licence: String,
    val downloaded: Boolean,
)

data class SettingsState(
    val sections: List<SettingsSection> = SettingsSection.entries.filter { it != SettingsSection.Debug },
    /** Section shown in the detail pane on wide windows. */
    val selected: SettingsSection = SettingsSection.AiModels,
    /** Empty until the first AI tool ships (Stage 16, rule D3). */
    val models: List<AiModelEntry> = emptyList(),
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
}

class SettingsViewModel(
    versionName: String,
    showDebug: Boolean,
    private val preferences: AppPreferences,
    private val lensProfiles: LensProfileStore? = null,
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
    }

    override fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.Select -> setState { copy(selected = intent.section) }

            // No model can be downloaded before Stage 16, so there is nothing to delete yet.
            is SettingsIntent.DeleteModel -> setState { copy(models = models.filterNot { it.id == intent.id }) }

            is SettingsIntent.SetSurround -> preferences.setSurround(intent.surround)

            is SettingsIntent.SetTapGestures -> preferences.setTapGestures(intent.gestures)

            is SettingsIntent.DownloadLensProfiles -> lensProfiles?.let { viewModelScope.launch { it.download() } }

            is SettingsIntent.DeleteLensProfiles -> lensProfiles?.let { viewModelScope.launch { it.delete() } }
        }
    }
}
