package dev.mark.lineover.feature.home

import android.net.Uri
import androidx.lifecycle.viewModelScope
import dev.mark.lineover.core.io.gallery.DeviceAlbum
import dev.mark.lineover.core.io.gallery.DeviceGallery
import dev.mark.lineover.core.io.project.ImportResult
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.core.model.Project
import dev.mark.lineover.core.ui.mvi.MviViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

enum class HomeTab { Recent, Projects, Albums }

/** How much of the device's photo library LineOver may read (Albums tab). */
enum class MediaAccess {
    /** Not granted: the Albums tab explains and offers "Allow access". */
    None,

    /** Android 14+ "select photos": only the photos the user chose. */
    Partial,

    Full,
}

data class HomeState(
    val tab: HomeTab = HomeTab.Recent,
    val recent: List<Project> = emptyList(),
    val projects: List<Project> = emptyList(),
    val loaded: Boolean = false,
    val importing: Boolean = false,
    /** Project whose rename dialog is open. */
    val renaming: Project? = null,
    /** Deleted project whose undo toast is showing. */
    val pendingDelete: Project? = null,
    val mediaAccess: MediaAccess = MediaAccess.None,
    val albums: List<DeviceAlbum> = emptyList(),
) {
    val visible: List<Project> get() = if (tab == HomeTab.Recent) recent else projects
}

sealed interface HomeIntent {
    data class SelectTab(
        val tab: HomeTab,
    ) : HomeIntent

    /** Photos returned by the photo picker (empty when the user cancelled). */
    data class ImportPicked(
        val uris: List<Uri>,
    ) : HomeIntent

    /** A file returned by ACTION_OPEN_DOCUMENT (null when cancelled). */
    data class OpenDocument(
        val uri: Uri?,
    ) : HomeIntent

    data class Open(
        val id: Long,
    ) : HomeIntent

    data class StartRename(
        val project: Project,
    ) : HomeIntent

    data class ConfirmRename(
        val id: Long,
        val name: String,
    ) : HomeIntent

    data object CancelRename : HomeIntent

    data class Duplicate(
        val id: Long,
    ) : HomeIntent

    data class Delete(
        val id: Long,
    ) : HomeIntent

    data object UndoDelete : HomeIntent

    /** The media permission state, re-read whenever Home resumes or a request returns. */
    data class MediaAccessChanged(
        val access: MediaAccess,
    ) : HomeIntent
}

sealed interface HomeEffect {
    data class OpenEditor(
        val projectId: Long,
    ) : HomeEffect

    data class Message(
        val text: String,
    ) : HomeEffect
}

/**
 * Home screen. A deleted project stays restorable for [undoMillis]; its files are only
 * removed afterwards, on [cleanupScope] so a pending clean-up survives this ViewModel.
 */
class HomeViewModel(
    private val library: ProjectLibrary,
    private val gallery: DeviceGallery,
    private val cleanupScope: CoroutineScope,
    private val undoMillis: Long = UNDO_MILLIS,
) : MviViewModel<HomeState, HomeIntent, HomeEffect>(HomeState()) {
    private var undoJob: Job? = null
    private var albumsJob: Job? = null

    init {
        viewModelScope.launch {
            combine(library.observeRecent(), library.observeProjects()) { recent, all -> recent to all }
                .collect { (recent, all) -> setState { copy(recent = recent, projects = all, loaded = true) } }
        }
    }

    override fun onIntent(intent: HomeIntent) {
        when (intent) {
            is HomeIntent.SelectTab -> {
                setState { copy(tab = intent.tab) }
            }

            is HomeIntent.ImportPicked -> {
                importPicked(intent.uris)
            }

            is HomeIntent.OpenDocument -> {
                intent.uri?.let(::openDocument)
            }

            is HomeIntent.Open -> {
                sendEffect(HomeEffect.OpenEditor(intent.id))
            }

            is HomeIntent.StartRename -> {
                setState { copy(renaming = intent.project) }
            }

            is HomeIntent.CancelRename -> {
                setState { copy(renaming = null) }
            }

            is HomeIntent.ConfirmRename -> {
                setState { copy(renaming = null) }
                viewModelScope.launch { library.rename(intent.id, intent.name) }
            }

            is HomeIntent.Duplicate -> {
                viewModelScope.launch { library.duplicate(intent.id) }
            }

            is HomeIntent.Delete -> {
                delete(intent.id)
            }

            is HomeIntent.UndoDelete -> {
                undoDelete()
            }

            is HomeIntent.MediaAccessChanged -> {
                mediaAccessChanged(intent.access)
            }
        }
    }

    private fun mediaAccessChanged(access: MediaAccess) {
        if (access == state.value.mediaAccess && albumsJob != null) return
        setState { copy(mediaAccess = access) }
        albumsJob?.cancel()
        albumsJob = null
        if (access == MediaAccess.None) {
            setState { copy(albums = emptyList()) }
            return
        }
        albumsJob = viewModelScope.launch { gallery.observeAlbums().collect { setState { copy(albums = it) } } }
    }

    private fun importPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        setState { copy(importing = true) }
        viewModelScope.launch {
            val result = library.importPicked(uris)
            setState { copy(importing = false) }
            report(result, picked = uris.size)
        }
    }

    private fun openDocument(uri: Uri) {
        setState { copy(importing = true) }
        viewModelScope.launch {
            val result = library.importDocument(uri)
            setState { copy(importing = false) }
            report(result, picked = 1)
        }
    }

    private fun report(
        result: ImportResult,
        picked: Int,
    ) {
        when (result) {
            ImportResult.ProjectFileNotYetSupported -> {
                sendEffect(HomeEffect.Message("Opening .loproj project files arrives in Stage 8"))
            }

            is ImportResult.Imported -> {
                when {
                    result.ids.size == 1 && picked == 1 -> {
                        sendEffect(HomeEffect.OpenEditor(result.ids.single()))
                    }

                    result.failed > 0 -> {
                        sendEffect(
                            HomeEffect.Message("Imported ${result.ids.size} of $picked; ${result.failed} could not be read"),
                        )
                    }

                    else -> {
                        sendEffect(HomeEffect.Message("Imported ${result.ids.size} photos"))
                    }
                }
                if (result.ids.isNotEmpty()) setState { copy(tab = HomeTab.Recent) }
            }
        }
    }

    private fun delete(id: Long) {
        finishPendingDelete()
        viewModelScope.launch {
            val deleted = library.delete(id) ?: return@launch
            setState { copy(pendingDelete = deleted) }
            undoJob =
                viewModelScope.launch {
                    delay(undoMillis)
                    finishPendingDelete()
                }
        }
    }

    private fun undoDelete() {
        val project = state.value.pendingDelete ?: return
        undoJob?.cancel()
        setState { copy(pendingDelete = null) }
        viewModelScope.launch { library.restore(project) }
    }

    /** Makes the pending deletion final: removes the project's files. */
    private fun finishPendingDelete() {
        val project = state.value.pendingDelete ?: return
        undoJob?.cancel()
        setState { copy(pendingDelete = null) }
        cleanupScope.launch { library.purge(project) }
    }

    override fun onCleared() {
        finishPendingDelete()
    }

    companion object {
        const val UNDO_MILLIS = 5000L
    }
}
