package dev.mark.lineover.feature.settings

import androidx.lifecycle.viewModelScope
import dev.mark.lineover.core.io.prefs.AppPreferences
import dev.mark.lineover.core.io.prefs.CanvasSurround
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
}

class SettingsViewModel(
    versionName: String,
    showDebug: Boolean,
    private val preferences: AppPreferences,
) : MviViewModel<SettingsState, SettingsIntent, Nothing>(
        SettingsState(
            sections = SettingsSection.entries.filter { showDebug || it != SettingsSection.Debug },
            versionName = versionName,
            surround = preferences.surround.value,
        ),
    ) {
    init {
        viewModelScope.launch { preferences.surround.collect { setState { copy(surround = it) } } }
    }

    override fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.Select -> setState { copy(selected = intent.section) }

            // No model can be downloaded before Stage 16, so there is nothing to delete yet.
            is SettingsIntent.DeleteModel -> setState { copy(models = models.filterNot { it.id == intent.id }) }

            is SettingsIntent.SetSurround -> preferences.setSurround(intent.surround)
        }
    }
}
