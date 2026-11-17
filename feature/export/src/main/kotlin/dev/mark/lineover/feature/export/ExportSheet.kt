package dev.mark.lineover.feature.export

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoDimens
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoChip
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.designsystem.component.LoSegmented
import dev.mark.lineover.core.designsystem.component.LoSlider
import dev.mark.lineover.core.designsystem.component.LoSliderFill
import dev.mark.lineover.core.designsystem.component.LoSwitchRow
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

/**
 * The export sheet: format, quality, size, colour space, metadata, watermark and
 * "Save project". The layout is final (frozen after Stage 3); the controls are shown
 * disabled with the stage that brings them (export: Stage 12, project files: Stage 8).
 */
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
                .fillMaxHeight(SHEET_MAX_FRACTION)
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
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = LoSpacing.M),
                verticalArrangement = Arrangement.spacedBy(LoSpacing.M),
            ) {
                Section("Format") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
                        FORMATS.forEachIndexed { index, (id, label) ->
                            LoChip(label, selected = index == 0, onClick = {}, enabled = false, modifier = Modifier.testTag("slot:$id"))
                        }
                    }
                    LoSlider(
                        value = DEFAULT_QUALITY,
                        onValueChange = {},
                        label = "Quality",
                        valueRange = 1f..100f,
                        defaultValue = DEFAULT_QUALITY,
                        fill = LoSliderFill.FromStart,
                        enabled = false,
                    )
                }
                Section("Size", Modifier.testTag("slot:export.resize")) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
                        listOf("Original", "4096 px", "2048 px", "Custom").forEachIndexed { index, label ->
                            LoChip(label, selected = index == 0, onClick = {}, enabled = false)
                        }
                    }
                }
                Section("Colour space", Modifier.testTag("slot:export.icc_profile")) {
                    LoSegmented(listOf("sRGB", "Display P3", "Rec. 2020"), selectedIndex = 0, onSelect = {}, enabled = false)
                }
                Section("Metadata", Modifier.testTag("slot:export.metadata")) {
                    LoSwitchRow("Keep camera data (EXIF)", checked = true, onCheckedChange = {}, enabled = false)
                    LoSwitchRow("Remove location", checked = true, onCheckedChange = {}, enabled = false)
                }
                Section("Watermark", Modifier.testTag("slot:export.watermark")) {
                    LoSwitchRow(
                        "Add a watermark",
                        checked = false,
                        onCheckedChange = {},
                        description = "Text or logo, position and opacity",
                        enabled = false,
                    )
                }
                Text(
                    "Exports are saved to Pictures/LineOver. The original photo is never changed.",
                    style = LoType.Caption,
                    color = LoColors.TextSecondary,
                    modifier = Modifier.padding(bottom = LoSpacing.Xs),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(LoSpacing.M),
                horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f).heightIn(min = LoDimens.TouchTargetMin)) {
                    Text("Save project · Stage 8", style = LoType.Body)
                }
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f).heightIn(min = LoDimens.TouchTargetMin),
                    colors = ButtonDefaults.buttonColors(containerColor = LoColors.Accent, contentColor = LoColors.OnAccent),
                ) {
                    Text("Export · Stage $EXPORT_STAGE", style = LoType.Body)
                }
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = LoType.Title, color = LoColors.TextPrimary, modifier = Modifier.weight(1f))
            Text("Stage $EXPORT_STAGE", style = LoType.Micro, color = LoColors.TextSecondary)
        }
        content()
    }
}

private val FORMATS =
    listOf(
        "export.jpeg" to "JPEG",
        "export.png" to "PNG",
        "export.webp" to "WebP",
        "export.heif" to "HEIF",
        "export.avif" to "AVIF",
        "export.tiff" to "TIFF",
        "export.ultra_hdr" to "Ultra HDR",
    )

private const val EXPORT_STAGE = 12
private const val DEFAULT_QUALITY = 90f
private const val SHEET_MAX_FRACTION = 0.9f
