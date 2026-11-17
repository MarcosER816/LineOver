package dev.mark.lineover.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoEmptyState
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.designsystem.component.LoToast
import dev.mark.lineover.core.designsystem.component.LoToastHost
import dev.mark.lineover.core.designsystem.component.LoToastState
import dev.mark.lineover.core.io.gallery.DeviceGallery
import dev.mark.lineover.core.io.gallery.DevicePhoto
import dev.mark.lineover.core.io.project.ImportResult
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.core.ui.image.ProjectThumbnail
import dev.mark.lineover.core.ui.mvi.MviViewModel
import kotlinx.coroutines.launch

data class AlbumState(
    val name: String,
    val photos: List<DevicePhoto> = emptyList(),
    val loaded: Boolean = false,
    val importing: Boolean = false,
)

sealed interface AlbumIntent {
    data class Open(
        val photoId: Long,
    ) : AlbumIntent
}

sealed interface AlbumEffect {
    data class OpenEditor(
        val projectId: Long,
    ) : AlbumEffect

    data class Message(
        val text: String,
    ) : AlbumEffect
}

/**
 * One device album. Tapping a photo makes it a project: MediaStore grants no persistable
 * permission, so the original is copied into the project (it is never modified).
 */
class AlbumViewModel(
    albumId: Long,
    name: String,
    private val gallery: DeviceGallery,
    private val library: ProjectLibrary,
) : MviViewModel<AlbumState, AlbumIntent, AlbumEffect>(AlbumState(name)) {
    init {
        viewModelScope.launch { gallery.observePhotos(albumId).collect { setState { copy(photos = it, loaded = true) } } }
    }

    override fun onIntent(intent: AlbumIntent) {
        when (intent) {
            is AlbumIntent.Open -> {
                if (state.value.importing) return
                setState { copy(importing = true) }
                viewModelScope.launch {
                    val result = library.importPicked(listOf(gallery.uriOf(intent.photoId)))
                    setState { copy(importing = false) }
                    val id = (result as? ImportResult.Imported)?.ids?.singleOrNull()
                    sendEffect(if (id != null) AlbumEffect.OpenEditor(id) else AlbumEffect.Message("This photo could not be read"))
                }
            }
        }
    }
}

@Composable
fun AlbumRoute(
    albumId: Long,
    name: String,
    gallery: DeviceGallery,
    library: ProjectLibrary,
    onBack: () -> Unit,
    onOpenEditor: (Long) -> Unit,
) {
    val viewModel = viewModel(key = "album-$albumId") { AlbumViewModel(albumId, name, gallery, library) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toast = remember { LoToastState() }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is AlbumEffect.OpenEditor -> onOpenEditor(effect.projectId)
                is AlbumEffect.Message -> launch { toast.show(effect.text) }
            }
        }
    }
    MediaThumbnails(gallery) { AlbumScreen(state, viewModel::onIntent, onBack, toast = toast) }
}

/** The photos of one album, newest first; a tap opens the photo as a project. */
@Composable
fun AlbumScreen(
    state: AlbumState,
    onIntent: (AlbumIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    toast: LoToastState = remember { LoToastState() },
) {
    Box(modifier.fillMaxSize().background(LoColors.Bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                LoIconButton(LoIcons.ArrowBack, "Back", onClick = onBack)
                Text(state.name, style = LoType.Headline, color = LoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.loaded && state.photos.isEmpty()) {
                LoEmptyState(
                    LoIcons.Image,
                    "No photos here",
                    body = "This album is empty, or LineOver may only see the photos you selected.",
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(LoSpacing.Xxs),
                    horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xxs),
                    verticalArrangement = Arrangement.spacedBy(LoSpacing.Xxs),
                ) {
                    items(state.photos, key = { it.id }) { photo ->
                        ProjectThumbnail(
                            mediaThumbnailKey(photo.id),
                            Modifier
                                .aspectRatio(1f)
                                .clip(LoShapes.ExtraSmall)
                                .clickable(role = Role.Button, onClickLabel = "Open as project") { onIntent(AlbumIntent.Open(photo.id)) }
                                .semantics { contentDescription = photo.name },
                        )
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(LoSpacing.M)) {
            if (state.importing) LoToast("Opening…")
            LoToastHost(toast)
        }
    }
}
