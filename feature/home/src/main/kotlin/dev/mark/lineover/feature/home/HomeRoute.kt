package dev.mark.lineover.feature.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mark.lineover.core.designsystem.component.LoToastState
import dev.mark.lineover.core.io.project.ProjectLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Most photos the picker returns at once (kept for batch editing, Stage 28). */
const val MAX_PICKED_PHOTOS = 50

/** Files offered by "Open file": RAW and TIFF are images; .loproj is a binary file. */
private val OPEN_FILE_TYPES = arrayOf("image/*", "application/octet-stream")

/**
 * Home wired to its ViewModel and the system pickers. The photo picker and the document
 * picker need no storage permission; LineOver never asks for one.
 */
@Composable
fun HomeRoute(
    library: ProjectLibrary,
    cleanupScope: CoroutineScope,
    onOpenEditor: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel = viewModel { HomeViewModel(library, cleanupScope) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toast = remember { LoToastState() }
    val pickPhotos =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_PHOTOS)) { uris ->
            viewModel.onIntent(HomeIntent.ImportPicked(uris))
        }
    val openFile =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            viewModel.onIntent(HomeIntent.OpenDocument(uri))
        }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is HomeEffect.OpenEditor -> onOpenEditor(effect.projectId)
                is HomeEffect.Message -> launch { toast.show(effect.text) }
            }
        }
    }
    HomeScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onImport = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onOpenFile = { openFile.launch(OPEN_FILE_TYPES) },
        onOpenSettings = onOpenSettings,
        toast = toast,
    )
}
