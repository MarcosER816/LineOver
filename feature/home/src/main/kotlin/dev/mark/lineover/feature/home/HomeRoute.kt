package dev.mark.lineover.feature.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mark.lineover.core.designsystem.component.LoToastState
import dev.mark.lineover.core.io.gallery.DeviceAlbum
import dev.mark.lineover.core.io.gallery.DeviceGallery
import dev.mark.lineover.core.io.project.ProjectLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Most photos the picker returns at once (kept for batch editing, Stage 28). */
const val MAX_PICKED_PHOTOS = 50

/** Files offered by "Open file": RAW and TIFF are images; .loproj is a binary file. */
private val OPEN_FILE_TYPES = arrayOf("image/*", "application/octet-stream")

/**
 * Home wired to its ViewModel and the system pickers. Import (photo picker) and Open file
 * (document picker) need no permission. Only the Albums tab asks for photo access, when
 * the user taps "Allow access".
 */
@Composable
fun HomeRoute(
    library: ProjectLibrary,
    gallery: DeviceGallery,
    cleanupScope: CoroutineScope,
    onOpenEditor: (Long) -> Unit,
    onOpenAlbum: (DeviceAlbum) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel = viewModel { HomeViewModel(library, gallery, cleanupScope) }
    val context = LocalContext.current
    // Re-read the permission on every resume: it can change in the system settings.
    LifecycleResumeEffect(viewModel) {
        viewModel.onIntent(HomeIntent.MediaAccessChanged(context.mediaAccess()))
        onPauseOrDispose {}
    }
    val requestMedia =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.onIntent(HomeIntent.MediaAccessChanged(context.mediaAccess()))
        }
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
    MediaThumbnails(gallery) {
        HomeScreen(
            state = state,
            onIntent = viewModel::onIntent,
            onImport = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onOpenFile = { openFile.launch(OPEN_FILE_TYPES) },
            onOpenSettings = onOpenSettings,
            onRequestMediaAccess = { requestMedia.launch(MEDIA_PERMISSIONS) },
            onOpenAlbum = onOpenAlbum,
            toast = toast,
        )
    }
}
