package dev.mark.lineover.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
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
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.core.model.Project
import dev.mark.lineover.core.ui.image.ProjectThumbnail
import dev.mark.lineover.core.ui.mvi.MviViewModel
import kotlinx.coroutines.launch

data class EditorState(
    val project: Project? = null,
    val loaded: Boolean = false,
)

/** The editor of one project. Until Stage 3 it only shows which project is open. */
class EditorViewModel(
    projectId: Long,
    library: ProjectLibrary,
) : MviViewModel<EditorState, Nothing, Nothing>(EditorState()) {
    init {
        viewModelScope.launch { library.markOpened(projectId) }
        viewModelScope.launch {
            library.observe(projectId).collect { project -> setState { copy(project = project, loaded = true) } }
        }
    }

    override fun onIntent(intent: Nothing) = Unit
}

@Composable
fun EditorRoute(
    projectId: Long,
    library: ProjectLibrary,
    onBack: () -> Unit,
    onExport: () -> Unit,
) {
    val viewModel = viewModel(key = "editor-$projectId") { EditorViewModel(projectId, library) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    EditorScreen(state, onBack, onExport)
}

/** Editor placeholder: the shell with every tool slot arrives in Stage 3, the engine in Stage 4. */
@Composable
fun EditorScreen(
    state: EditorState,
    onBack: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(LoColors.CanvasSurroundDark).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).background(LoColors.Surface1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoIconButton(LoIcons.ArrowBack, "Back", onClick = onBack)
                Text(
                    state.project?.name.orEmpty(),
                    style = LoType.Title,
                    color = LoColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Column(Modifier.weight(1f).fillMaxWidth().padding(LoSpacing.M), horizontalAlignment = Alignment.CenterHorizontally) {
                val project = state.project
                if (state.loaded && project == null) {
                    LoEmptyState(LoIcons.Image, "Project not found", body = "It may have been deleted.")
                } else if (project != null) {
                    ProjectThumbnail(
                        project.thumbPath,
                        Modifier
                            .widthIn(max = 280.dp)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(LoShapes.Medium),
                    )
                    LoEmptyState(
                        icon = LoIcons.Tune,
                        title = "The editor arrives in Stage 3",
                        body = "This project is saved. Editing tools are added stage by stage; your original photo is never changed.",
                    )
                }
            }
        }
        if (state.project != null) {
            ExtendedFloatingActionButton(
                onClick = onExport,
                modifier = Modifier.align(Alignment.BottomEnd).padding(LoSpacing.M),
                containerColor = LoColors.Surface3,
                contentColor = LoColors.TextPrimary,
                shape = LoShapes.Large,
                icon = { Icon(painterResource(LoIcons.IosShare), contentDescription = null) },
                text = { Text("Export", style = LoType.Title) },
            )
        }
    }
}
