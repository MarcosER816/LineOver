package dev.mark.lineover.feature.export

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import dev.mark.lineover.core.io.export.ExportDestinations
import dev.mark.lineover.core.io.export.ExportTarget
import dev.mark.lineover.core.io.prefs.AppPreferences
import dev.mark.lineover.core.io.prefs.InMemoryAppPreferences
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.core.model.ExportColorSpace
import dev.mark.lineover.core.model.ExportEstimate
import dev.mark.lineover.core.model.ExportFormat
import dev.mark.lineover.core.model.ExportSettings
import dev.mark.lineover.core.model.MetadataMode
import dev.mark.lineover.core.model.OutputSharpen
import dev.mark.lineover.core.model.ResizeMode
import dev.mark.lineover.core.model.WatermarkKind
import dev.mark.lineover.core.model.WatermarkPosition
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun ExportRoute(
    projectId: Long,
    library: ProjectLibrary,
    onClose: () -> Unit,
    preferences: AppPreferences = remember { InMemoryAppPreferences() },
) {
    val context = LocalContext.current
    val viewModel =
        viewModel(key = "export-$projectId") {
            ExportViewModel(projectId, library, preferences, AndroidExportPlatform(context, library))
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onIntent = viewModel::onIntent
    val saveAs =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(state.settings.format.mimeType)) { uri ->
            if (uri != null) onIntent(ExportIntent.Export(ExportTarget.Document(uri)))
        }
    val saveProject =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) onIntent(ExportIntent.SaveProject(uri))
        }
    val pickLogo =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onIntent(ExportIntent.LogoPicked(uri))
        }
    // Progress shows in a notification: ask once (the export runs either way).
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun askNotifications() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    ExportSheet(
        state = state,
        onClose = onClose,
        onIntent = { intent ->
            when (intent) {
                is ExportIntent.Export -> {
                    askNotifications()
                    if (state.chooseLocation) saveAs.launch(state.settings.fileName(state.projectName)) else onIntent(intent)
                }

                ExportIntent.QuickExport -> {
                    askNotifications()
                    onIntent(intent)
                }

                else -> {
                    onIntent(intent)
                }
            }
        },
        onPickLogo = { pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onSaveProject = { saveProject.launch("${state.projectName.substringBeforeLast('.')}.loproj") },
    )
}

/**
 * The export sheet (Stage 12 fills the slots frozen in Stage 3): format and quality, size and
 * sharpening, colour space (with its ICC profile), metadata, watermark, where to save, the
 * estimated file size, Quick export, and the running export's progress.
 */
@Composable
fun ExportSheet(
    state: ExportState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onIntent: (ExportIntent) -> Unit = {},
    onPickLogo: () -> Unit = {},
    onSaveProject: () -> Unit = {},
) {
    val s = state.settings

    fun change(next: ExportSettings) = onIntent(ExportIntent.Change(next))
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
                TextButton(
                    onClick = { onIntent(ExportIntent.QuickExport) },
                    enabled = !state.busy,
                    modifier = Modifier.heightIn(min = LoDimens.TouchTargetMin).testTag(ExportTags.QUICK),
                ) {
                    Text("Quick export", style = LoType.Body, color = if (state.busy) LoColors.TextDisabled else LoColors.Accent)
                }
                LoIconButton(LoIcons.Close, "Close", onClick = onClose)
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = LoSpacing.M),
                verticalArrangement = Arrangement.spacedBy(LoSpacing.M),
            ) {
                FormatSection(state, ::change)
                SizeSection(state, ::change)
                ColorSection(s, ::change)
                MetadataSection(s, ::change)
                WatermarkSection(state, ::change, onPickLogo)
                Section("Save to") {
                    LoSegmented(
                        listOf("Pictures/LineOver", "Choose…"),
                        selectedIndex = if (state.chooseLocation) 1 else 0,
                        onSelect = { onIntent(ExportIntent.ChooseLocation(it == 1)) },
                    )
                }
                val where =
                    if (state.chooseLocation) {
                        "You pick the file's place when you export."
                    } else {
                        "Exports go to ${ExportDestinations.RELATIVE_PATH}."
                    }
                Text(
                    "The original photo is never changed. $where",
                    style = LoType.Caption,
                    color = LoColors.TextSecondary,
                    modifier = Modifier.padding(bottom = LoSpacing.Xs),
                )
            }
            Footer(state, onIntent, onSaveProject)
        }
    }
}

@Composable
private fun FormatSection(
    state: ExportState,
    change: (ExportSettings) -> Unit,
) {
    val s = state.settings
    Section("Format") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
            for (format in ExportFormat.entries.filter { state.capabilities.supports(it) }) {
                LoChip(format.label, selected = s.format == format, onClick = {
                    change(s.copy(format = format))
                }, modifier = Modifier.testTag("slot:${format.id}"))
            }
        }
        if (s.format == ExportFormat.Webp) LoSwitchRow("Lossless", s.webpLossless, { change(s.copy(webpLossless = it)) })
        if (s.format.hasQuality && !(s.format == ExportFormat.Webp && s.webpLossless)) {
            LoSlider(
                value = s.quality.toFloat(),
                onValueChange = { change(s.copy(quality = it.roundToInt())) },
                label = "Quality",
                valueRange = 1f..100f,
                defaultValue = ExportSettings.DEFAULT_QUALITY.toFloat(),
                fill = LoSliderFill.FromStart,
            )
        }
        if (s.format.bits.size > 1) {
            LoSegmented(
                listOf("8 bit", "16 bit"),
                selectedIndex =
                    if (s.bits ==
                        16
                    ) {
                        1
                    } else {
                        0
                    },
                onSelect = { change(s.copy(bits = if (it == 1) 16 else 8)) },
            )
        }
        val note =
            when (s.format) {
                ExportFormat.UltraHdr -> "JPEG with a gain map: HDR highlights glow on HDR screens, other screens show the SDR picture."
                ExportFormat.Heif, ExportFormat.Avif -> "Encoded by this phone's ${s.format.label} encoder, in sRGB."
                ExportFormat.Tiff -> "Uncompressed."
                else -> null
            }
        if (note != null) Text(note, style = LoType.Caption, color = LoColors.TextSecondary)
    }
}

@Composable
private fun SizeSection(
    state: ExportState,
    change: (ExportSettings) -> Unit,
) {
    val s = state.settings
    Section("Size", Modifier.testTag("slot:export.resize")) {
        val modes =
            listOf(
                ResizeMode.Original to "Original",
                ResizeMode.LongEdge to "Long edge",
                ResizeMode.Megapixels to "Megapixels",
                ResizeMode.Percent to "Percent",
            )
        LoSegmented(
            modes.map { it.second },
            selectedIndex = modes.indexOfFirst { it.first == s.resizeMode },
            onSelect = { i ->
                val mode = modes[i].first
                change(s.copy(resizeMode = mode, resizeValue = defaultValue(mode, state.imageSize)))
            },
        )
        val image = state.imageSize
        if (s.resizeMode != ResizeMode.Original) {
            val (range, step, unit) =
                when (s.resizeMode) {
                    ResizeMode.LongEdge -> {
                        Triple(
                            MIN_EDGE..(image?.let { max(it.first, it.second).toFloat() } ?: MAX_EDGE),
                            EDGE_STEP,
                            "px",
                        )
                    }

                    ResizeMode.Megapixels -> {
                        Triple(
                            1f..(image?.let { (it.first.toFloat() * it.second / MEGA).coerceAtLeast(1f) } ?: MAX_MP),
                            0.5f,
                            "MP",
                        )
                    }

                    else -> {
                        Triple(5f..100f, 1f, "%")
                    }
                }
            LoSlider(
                value = s.resizeValue.coerceIn(range.start, range.endInclusive.coerceAtLeast(range.start)),
                onValueChange = { change(s.copy(resizeValue = it)) },
                label = "Size",
                valueRange = range.start..range.endInclusive.coerceAtLeast(range.start + step),
                defaultValue = range.endInclusive,
                step = step,
                fill = LoSliderFill.FromStart,
                valueText = { v -> if (unit == "MP") "%.1f MP".format(java.util.Locale.ROOT, v) else "${v.roundToInt()} $unit" },
            )
        }
        val out = state.outputSize
        val summary =
            listOfNotNull(
                out?.let { "${it.first} × ${it.second} px" },
                state.estimate?.let { "≈ ${ExportEstimate.label(it)}" },
                s.format.label,
            ).joinToString(" · ")
        Text(summary, style = LoType.Caption, color = LoColors.TextSecondary, modifier = Modifier.testTag(ExportTags.ESTIMATE))
        Text("Sharpen for screen", style = LoType.Body, color = LoColors.TextPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
            for (level in OutputSharpen.entries) LoChip(level.label, s.sharpen == level, { change(s.copy(sharpen = level)) })
        }
    }
}

private fun defaultValue(
    mode: ResizeMode,
    image: Pair<Int, Int>?,
): Float =
    when (mode) {
        ResizeMode.Original -> {
            ExportSettings.DEFAULT_LONG_EDGE
        }

        ResizeMode.LongEdge -> {
            image?.let { minOf(ExportSettings.DEFAULT_LONG_EDGE, max(it.first, it.second).toFloat()) }
                ?: ExportSettings.DEFAULT_LONG_EDGE
        }

        ResizeMode.Megapixels -> {
            image?.let { minOf(DEFAULT_MP, it.first.toFloat() * it.second / MEGA) } ?: DEFAULT_MP
        }

        ResizeMode.Percent -> {
            DEFAULT_PERCENT
        }
    }

@Composable
private fun ColorSection(
    s: ExportSettings,
    change: (ExportSettings) -> Unit,
) {
    Section("Colour space", Modifier.testTag("slot:export.icc_profile")) {
        val spaces = ExportColorSpace.entries
        LoSegmented(
            spaces.map { it.label },
            selectedIndex = spaces.indexOf(s.colorSpace),
            onSelect = { i ->
                val space = spaces[i]
                if (space in s.format.spaces &&
                    (space != ExportColorSpace.Rec2020 || s.bits == ExportSettings.WIDE_BITS)
                ) {
                    change(s.copy(colorSpace = space))
                }
            },
            descriptions = spaces.map { if (it in s.format.spaces) it.label else "${it.label}: not with ${s.format.label}" },
        )
        Text(
            when {
                s.colorSpace == ExportColorSpace.Rec2020 -> "Rec. 2020 ICC profile embedded (16-bit files only)."
                s.format == ExportFormat.Heif || s.format == ExportFormat.Avif -> "Tagged sRGB by the encoder."
                else -> "${s.colorSpace.label} ICC profile embedded. Colours outside it are mapped in, not clipped."
            },
            style = LoType.Caption,
            color = LoColors.TextSecondary,
        )
    }
}

@Composable
private fun MetadataSection(
    s: ExportSettings,
    change: (ExportSettings) -> Unit,
) {
    Section("Metadata", Modifier.testTag("slot:export.metadata")) {
        val modes = MetadataMode.entries
        LoSegmented(modes.map { it.label }, selectedIndex = modes.indexOf(s.metadata), onSelect = { change(s.copy(metadata = modes[it])) })
        LoSwitchRow(
            "Remove location",
            checked = s.removeLocation || s.metadata != MetadataMode.All,
            onCheckedChange = { change(s.copy(removeLocation = it)) },
            description = "GPS position of the photo",
            enabled = s.metadata == MetadataMode.All,
        )
        if (s.metadata != MetadataMode.None) {
            LoTextField(s.copyright, { change(s.copy(copyright = it)) }, "Copyright (when the photo has none)")
        }
    }
}

@Composable
private fun WatermarkSection(
    state: ExportState,
    change: (ExportSettings) -> Unit,
    onPickLogo: () -> Unit,
) {
    val s = state.settings
    val w = s.watermark

    fun mark(next: dev.mark.lineover.core.model.WatermarkSettings) = change(s.copy(watermark = next))
    Section("Watermark", Modifier.testTag("slot:export.watermark")) {
        LoSwitchRow("Add a watermark", checked = w.enabled, onCheckedChange = {
            mark(w.copy(enabled = it))
        }, description = "Text or logo, position and opacity")
        if (!w.enabled) return@Section
        LoSegmented(listOf("Text", "Logo"), selectedIndex = w.kind.ordinal, onSelect = { mark(w.copy(kind = WatermarkKind.entries[it])) })
        if (w.kind == WatermarkKind.Text) {
            LoTextField(w.text, { mark(w.copy(text = it)) }, "Text")
        } else {
            OutlinedButton(onClick = onPickLogo, modifier = Modifier.heightIn(min = LoDimens.TouchTargetMin)) {
                Text(if (state.hasLogo) "Change logo" else "Choose logo", style = LoType.Body, color = LoColors.TextPrimary)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
            for (p in WatermarkPosition.entries) LoChip(p.label, w.position == p, { mark(w.copy(position = p)) })
        }
        LoSlider(
            w.opacity,
            { mark(w.copy(opacity = it)) },
            "Opacity",
            valueRange = 0f..100f,
            defaultValue = 70f,
            fill = LoSliderFill.FromStart,
        )
        LoSlider(
            w.scale,
            { mark(w.copy(scale = it)) },
            "Size",
            valueRange = ExportSettings.MIN_MARK_SCALE..ExportSettings.MAX_MARK_SCALE,
            defaultValue = 18f,
            fill = LoSliderFill.FromStart,
            valueText = { "${it.roundToInt()} %" },
        )
    }
}

@Composable
private fun Footer(
    state: ExportState,
    onIntent: (ExportIntent) -> Unit,
    onSaveProject: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(LoSpacing.M), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        state.message?.let { Text(it, style = LoType.Caption, color = LoColors.TextSecondary) }
        when (val status = state.status) {
            is ExportStatus.Running -> {
                Text(status.step.label, style = LoType.Body, color = LoColors.TextPrimary)
                LinearProgressIndicator(
                    progress = { status.progress },
                    modifier = Modifier.fillMaxWidth().testTag(ExportTags.PROGRESS),
                    color = LoColors.Accent,
                    trackColor = LoColors.Surface3,
                )
                OutlinedButton(onClick = { onIntent(ExportIntent.Cancel) }, modifier = Modifier.heightIn(min = LoDimens.TouchTargetMin)) {
                    Text("Cancel", style = LoType.Body, color = LoColors.TextPrimary)
                }
            }

            is ExportStatus.Done -> {
                Text(
                    "Saved ${status.fileName} · ${status.width} × ${status.height} · ${ExportEstimate.label(status.bytes)}" +
                        if (status.inPictures) " · Pictures/LineOver" else "",
                    style = LoType.Body,
                    color = LoColors.TextPrimary,
                    modifier = Modifier.testTag(ExportTags.DONE),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
                    OutlinedButton(onClick = {
                        launch(
                            context,
                            Intent(
                                Intent.ACTION_VIEW,
                            ).setDataAndType(status.uri, status.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    }) { Text("Open", style = LoType.Body, color = LoColors.TextPrimary) }
                    OutlinedButton(onClick = {
                        launch(context, Intent.createChooser(ExportService.shareIntent(status.uri, status.mimeType), "Share"))
                    }) { Text("Share", style = LoType.Body, color = LoColors.TextPrimary) }
                    TextButton(onClick = { onIntent(ExportIntent.Dismiss) }) { Text("Done", style = LoType.Body, color = LoColors.Accent) }
                }
            }

            is ExportStatus.Failed -> {
                Text("Export failed: ${status.message}", style = LoType.Body, color = LoColors.Danger)
                TextButton(onClick = { onIntent(ExportIntent.Dismiss) }) { Text("OK", style = LoType.Body, color = LoColors.Accent) }
            }

            ExportStatus.Idle, ExportStatus.Cancelled -> {
                Row(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onSaveProject, modifier = Modifier.weight(1f).heightIn(min = LoDimens.TouchTargetMin)) {
                        Text("Save project", style = LoType.Body)
                    }
                    Button(
                        onClick = { onIntent(ExportIntent.Export(ExportTarget.Pictures)) },
                        modifier = Modifier.weight(1f).heightIn(min = LoDimens.TouchTargetMin).testTag(ExportTags.EXPORT),
                        colors = ButtonDefaults.buttonColors(containerColor = LoColors.Accent, contentColor = LoColors.OnAccent),
                    ) {
                        Text("Export", style = LoType.Body)
                    }
                }
            }
        }
    }
}

private fun launch(
    context: android.content.Context,
    intent: Intent,
) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No app for it: nothing to open.
    }
}

@Composable
private fun LoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = LoType.Body,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedTextColor = LoColors.TextPrimary,
                unfocusedTextColor = LoColors.TextPrimary,
                focusedBorderColor = LoColors.Accent,
                unfocusedBorderColor = LoColors.Outline,
                focusedLabelColor = LoColors.Accent,
                unfocusedLabelColor = LoColors.TextSecondary,
                cursorColor = LoColors.Accent,
            ),
    )
}

@Composable
private fun Section(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        Text(title, style = LoType.Title, color = LoColors.TextPrimary)
        content()
    }
}

object ExportTags {
    const val QUICK = "export:quick"
    const val EXPORT = "export:export"
    const val PROGRESS = "export:progress"
    const val DONE = "export:done"
    const val ESTIMATE = "export:estimate"
}

private const val SHEET_MAX_FRACTION = 0.9f
private const val MIN_EDGE = 256f
private const val MAX_EDGE = 16384f
private const val EDGE_STEP = 1f
private const val MAX_MP = 200f
private const val MEGA = 1_000_000f
private const val DEFAULT_MP = 12f
private const val DEFAULT_PERCENT = 50f
