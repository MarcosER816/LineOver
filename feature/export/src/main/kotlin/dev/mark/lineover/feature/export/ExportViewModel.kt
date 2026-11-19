package dev.mark.lineover.feature.export

import android.net.Uri
import androidx.lifecycle.viewModelScope
import dev.mark.lineover.core.io.export.ExportTarget
import dev.mark.lineover.core.io.prefs.AppPreferences
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.core.model.ExportEstimate
import dev.mark.lineover.core.model.ExportSettings
import dev.mark.lineover.core.model.Project
import dev.mark.lineover.core.ui.mvi.MviViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ExportState(
    val projectName: String = "",
    val settings: ExportSettings = ExportSettings(),
    val capabilities: ExportCapabilities = ExportCapabilities.ALL,
    /** The edited image's size (crop included), once known. */
    val imageSize: Pair<Int, Int>? = null,
    /** Estimated file size in bytes, once measured. */
    val estimate: Long? = null,
    val status: ExportStatus = ExportStatus.Idle,
    /** False: Pictures/LineOver; true: a place picked with the file picker. */
    val chooseLocation: Boolean = false,
    val hasLogo: Boolean = false,
    val message: String? = null,
) {
    val outputSize: Pair<Int, Int>? get() = imageSize?.let { (w, h) -> settings.outputSize(w, h) }
    val busy: Boolean get() = status is ExportStatus.Running
}

sealed interface ExportIntent {
    data class Change(
        val settings: ExportSettings,
    ) : ExportIntent

    data class ChooseLocation(
        val choose: Boolean,
    ) : ExportIntent

    /** Export with the sheet's settings to [target]. */
    data class Export(
        val target: ExportTarget,
    ) : ExportIntent

    /** Quick export: the default settings, to Pictures/LineOver. */
    data object QuickExport : ExportIntent

    data object Cancel : ExportIntent

    data object Dismiss : ExportIntent

    data class LogoPicked(
        val uri: Uri,
    ) : ExportIntent

    data class SaveProject(
        val target: Uri,
    ) : ExportIntent
}

/** What the sheet needs from Android (the route provides it; tests fake it). */
interface ExportPlatform {
    /** Known at once (cached, or the formats that need no device codec). */
    val capabilities: ExportCapabilities

    /** Verified on this device (HEIF / AVIF encoded and decoded once); may take a moment the first time. */
    suspend fun verifiedCapabilities(): ExportCapabilities

    fun start(request: ExportRequest)

    fun cancel()

    /** The edited image's size: the geometry's output, else the photo's (oriented). */
    suspend fun imageSize(project: Project): Pair<Int, Int>?

    /** Bytes per pixel of the project's thumbnail with the settings' encoders. */
    suspend fun sample(
        project: Project,
        settings: ExportSettings,
    ): ExportEstimate.Sample?

    /** Copies the picked logo into the app; false when it cannot be read. */
    suspend fun importLogo(uri: Uri): Boolean

    fun hasLogo(): Boolean
}

class ExportViewModel(
    private val projectId: Long,
    private val library: ProjectLibrary,
    private val preferences: AppPreferences,
    private val platform: ExportPlatform,
) : MviViewModel<ExportState, ExportIntent, Nothing>(
        ExportState(settings = preferences.exportSettings.value, capabilities = platform.capabilities, hasLogo = platform.hasLogo()),
    ) {
    private var project: Project? = null
    private var estimateJob: Job? = null

    init {
        viewModelScope.launch {
            library.observe(projectId).collect { p ->
                project = p
                setState { copy(projectName = p?.name.orEmpty()) }
            }
        }
        viewModelScope.launch {
            val p = library.observe(projectId).first() ?: return@launch
            val size = platform.imageSize(p)
            setState { copy(imageSize = size) }
            estimate()
        }
        viewModelScope.launch { ExportJobs.status.collect { setState { copy(status = it) } } }
        viewModelScope.launch {
            val verified = platform.verifiedCapabilities()
            setState { copy(capabilities = verified) }
            // A format this device cannot write (HEIF / AVIF) falls back to JPEG.
            if (!verified.supports(state.value.settings.format)) change(state.value.settings.copy(format = ExportSettings().format))
        }
    }

    override fun onIntent(intent: ExportIntent) {
        when (intent) {
            is ExportIntent.Change -> {
                change(intent.settings)
            }

            is ExportIntent.ChooseLocation -> {
                setState { copy(chooseLocation = intent.choose) }
            }

            is ExportIntent.Export -> {
                start(state.value.settings, intent.target)
            }

            ExportIntent.QuickExport -> {
                start(ExportSettings.QUICK, ExportTarget.Pictures)
            }

            ExportIntent.Cancel -> {
                platform.cancel()
            }

            ExportIntent.Dismiss -> {
                ExportJobs.acknowledge()
                setState { copy(message = null) }
            }

            is ExportIntent.LogoPicked -> {
                pickLogo(intent.uri)
            }

            is ExportIntent.SaveProject -> {
                saveProject(intent.target)
            }
        }
    }

    private fun change(settings: ExportSettings) {
        val valid = settings.validated()
        setState { copy(settings = valid) }
        preferences.setExportSettings(valid)
        estimate()
    }

    private fun start(
        settings: ExportSettings,
        target: ExportTarget,
    ) {
        if (state.value.busy) return
        platform.start(ExportRequest(projectId, settings.validated(), target))
    }

    private fun estimate() {
        estimateJob?.cancel()
        estimateJob =
            viewModelScope.launch {
                delay(ESTIMATE_DEBOUNCE_MS)
                val p = project ?: library.observe(projectId).first() ?: return@launch
                val s = state.value
                val (w, h) = s.outputSize ?: return@launch
                val sample = platform.sample(p, s.settings) ?: return@launch
                setState { copy(estimate = ExportEstimate.bytes(settings, w, h, sample)) }
            }
    }

    private fun pickLogo(uri: Uri) {
        viewModelScope.launch {
            val ok = platform.importLogo(uri)
            setState { copy(hasLogo = platform.hasLogo(), message = if (ok) null else "That image cannot be used as a logo") }
        }
    }

    private fun saveProject(target: Uri) {
        viewModelScope.launch {
            val ok = library.exportProjectFile(projectId, target)
            setState { copy(message = if (ok) "Project file saved" else "The project file could not be saved") }
        }
    }

    private companion object {
        const val ESTIMATE_DEBOUNCE_MS = 200L
    }
}
