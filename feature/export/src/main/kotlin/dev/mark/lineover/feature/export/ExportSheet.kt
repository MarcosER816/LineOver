package dev.mark.lineover.feature.export

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.designsystem.component.LoPlaceholderTool
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.core.ui.mvi.MviViewModel
import kotlinx.coroutines.launch

data class ExportState(
    val projectName: String = "",
)

class ExportViewModel(
    projectId: Long,
    library: ProjectLibrary,
) : MviViewModel<ExportState, Nothing, Nothing>(ExportState()) {
    init {
        viewModelScope.launch {
            library.observe(projectId).collect { setState { copy(projectName = it?.name.orEmpty()) } }
        }
    }

    override fun onIntent(intent: Nothing) = Unit
}

@Composable
fun ExportRoute(
    projectId: Long,
    library: ProjectLibrary,
    onClose: () -> Unit,
) {
    val viewModel = viewModel(key = "export-$projectId") { ExportViewModel(projectId, library) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ExportSheet(state, onClose)
}

/** The export sheet. Its sections are the reserved slots of Stage 12 (docs/TOOL_CATALOG.md). */
@Composable
fun ExportSheet(
    state: ExportState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .clip(LoShapes.PanelTop)
                .background(LoColors.Surface1)
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = LoSpacing.M),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Export", style = LoType.Headline, color = LoColors.TextPrimary)
                    if (state.projectName.isNotEmpty()) Text(state.projectName, style = LoType.Caption, color = LoColors.TextSecondary)
                }
                LoIconButton(LoIcons.Close, "Close", onClick = onClose)
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(LoSpacing.M),
                verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
            ) {
                LoPlaceholderTool("Formats: JPEG, PNG, WebP, HEIF, AVIF, TIFF, Ultra HDR", stage = EXPORT_STAGE, icon = LoIcons.Image)
                LoPlaceholderTool("Size, metadata, watermark, ICC profile", stage = EXPORT_STAGE, icon = LoIcons.Tune)
                Text(
                    "Exports will be saved to Pictures/LineOver. The original photo is never changed.",
                    style = LoType.Caption,
                    color = LoColors.TextSecondary,
                )
            }
        }
    }
}

private const val EXPORT_STAGE = 12
