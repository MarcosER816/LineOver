package dev.mark.lineover.feature.editor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoChip
import dev.mark.lineover.core.designsystem.component.LoSegmented
import dev.mark.lineover.core.designsystem.component.LoSlider
import dev.mark.lineover.core.designsystem.component.LoSliderFill
import dev.mark.lineover.core.ml.ModelState
import dev.mark.lineover.core.ml.SmartTool
import dev.mark.lineover.core.ml.sizeText
import kotlin.math.roundToInt

/** Test tags of the Stage 16 tools. */
object SmartTags {
    const val SUBJECT = "select-subject"
    const val SKY = "select-sky"
    const val SHEET = "model-sheet"
    const val DOWNLOAD = "model-download"
    const val SHIMMER = "smart-shimmer"
    const val REFINE_APPLY = "refine-apply"
}

/** The one-tap buttons (options bar and the Subject / Sky panels). */
@Composable
internal fun SmartButtons(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    val busy = state.smart.busy
    LoChip(
        "Select Subject",
        selected = busy == SmartTool.Subject,
        onClick = { onIntent(EditorIntent.Select(SmartAction.Run(SmartTool.Subject))) },
        enabled = busy == null,
        leadingIcon = LoIcons.FramePerson,
        modifier = Modifier.testTag(SmartTags.SUBJECT),
    )
    LoChip(
        "Select Sky",
        selected = busy == SmartTool.Sky,
        onClick = { onIntent(EditorIntent.Select(SmartAction.Run(SmartTool.Sky))) },
        enabled = busy == null,
        leadingIcon = LoIcons.Landscape,
        modifier = Modifier.testTag(SmartTags.SKY),
    )
}

/** The options of a Stage 16 tool, above the selection ops of [SelectPanel]. */
@Composable
internal fun SmartToolOptions(
    toolId: String,
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    val smart = state.smart
    val set = { next: SmartSelectState -> onIntent(EditorIntent.Select(SmartAction.Configure(next))) }
    when (toolId) {
        SmartTools.SUBJECT, SmartTools.SKY -> {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) { SmartButtons(state, onIntent) }
            BrushOptions(smart, set)
        }

        SmartTools.OBJECT -> {
            LoSegmented(
                options = listOf("Add object", "Remove"),
                selectedIndex = if (smart.objectSubtract) 1 else 0,
                onSelect = { set(smart.copy(objectSubtract = it == 1)) },
            )
        }

        SmartTools.QUICK -> {
            LoSlider(
                value = smart.quickRadius,
                onValueChange = { set(smart.copy(quickRadius = it)) },
                label = "Brush size",
                valueRange = 2f..SmartSelectState.MAX_BRUSH,
                defaultValue = SmartSelectState.DEFAULT_QUICK,
                fill = LoSliderFill.FromStart,
                valueText = { "${it.roundToInt()} px" },
            )
        }

        SmartTools.REFINE -> {
            RefineOptions(smart, onIntent)
            BrushOptions(smart, set)
        }
    }
    smart.lastRun?.let { Text(it, style = LoType.Caption, color = LoColors.TextSecondary) }
}

@Composable
private fun BrushOptions(
    smart: SmartSelectState,
    set: (SmartSelectState) -> Unit,
) {
    LoSegmented(
        options = listOf("Add brush", "Subtract brush"),
        selectedIndex = if (smart.brushSubtract) 1 else 0,
        onSelect = { set(smart.copy(brushSubtract = it == 1)) },
    )
    LoSlider(
        value = smart.brushRadius,
        onValueChange = { set(smart.copy(brushRadius = it)) },
        label = "Brush size",
        valueRange = 2f..SmartSelectState.MAX_BRUSH,
        defaultValue = SmartSelectState.DEFAULT_BRUSH,
        fill = LoSliderFill.FromStart,
        valueText = { "${it.roundToInt()} px" },
    )
}

@Composable
private fun RefineOptions(
    smart: SmartSelectState,
    onIntent: (EditorIntent) -> Unit,
) {
    val refine = { r: Float, c: Float -> onIntent(EditorIntent.Select(SmartAction.SetRefine(r, c))) }
    LoSlider(
        value = smart.refineRadius,
        onValueChange = { refine(it, smart.refineContrast) },
        label = "Radius",
        valueRange = 1f..SmartSelectState.MAX_REFINE_RADIUS,
        defaultValue = SmartSelectState.DEFAULT_REFINE_RADIUS,
        fill = LoSliderFill.FromStart,
        valueText = { "${it.roundToInt()} px" },
        onValueChangeFinished = { onIntent(EditorIntent.EndEdit) },
    )
    LoSlider(
        value = smart.refineContrast,
        onValueChange = { refine(smart.refineRadius, it) },
        label = "Contrast",
        valueRange = 0f..SmartSelectState.MAX_CONTRAST,
        defaultValue = SmartSelectState.DEFAULT_REFINE_CONTRAST,
        fill = LoSliderFill.FromStart,
        valueText = { "${it.roundToInt()}" },
        onValueChangeFinished = { onIntent(EditorIntent.EndEdit) },
    )
    LoChip(
        "Refine the selection",
        selected = false,
        onClick = { onIntent(EditorIntent.Select(SmartAction.ApplyRefine)) },
        enabled = state(smart),
        leadingIcon = LoIcons.AutoFixHigh,
        modifier = Modifier.testTag(SmartTags.REFINE_APPLY),
    )
}

private fun state(smart: SmartSelectState): Boolean = smart.busy == null

/** What a Stage 16 tool's finger does, for the panel's hint. */
internal fun smartHint(toolId: String): String =
    when (toolId) {
        SmartTools.MAGNETIC -> "Trace along an edge: the line snaps to it. Lift to close the shape."
        SmartTools.QUICK -> "Paint over the object: the selection grows to its edges."
        SmartTools.OBJECT -> "Tap or scribble on an object to select it; tap again to refine."
        SmartTools.SUBJECT -> "One tap selects the main subject. Then paint to add or remove."
        SmartTools.SKY -> "One tap selects the sky. Then paint to add or remove."
        else -> "Refine soft edges such as hair; paint to add or remove."
    }

/**
 * The first-use sheet (rule D3): which model a tool needs, its size and licence, where it comes
 * from, and a Download button; then its progress, or why it can't download.
 */
@Composable
internal fun ModelSheetDialog(
    sheet: ModelSheet,
    onIntent: (EditorIntent) -> Unit,
) {
    val info = sheet.info
    val uri = LocalUriHandler.current
    val downloading = sheet.state is ModelState.Downloading || sheet.state == ModelState.Verifying
    val dismiss = { onIntent(EditorIntent.Select(SmartAction.DismissSheet)) }
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = LoColors.Surface2,
        modifier = Modifier.testTag(SmartTags.SHEET),
        title = { Text("${sheet.tool.label} needs a model", style = LoType.Headline, color = LoColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
                Text(info.name, style = LoType.Title, color = LoColors.TextPrimary)
                Text(
                    "Size: ${info.sizeBytes?.let(::sizeText) ?: "shown by Google Play services when it starts"}",
                    style = LoType.Body,
                    color = LoColors.TextSecondary,
                )
                Text("Licence: ${info.license}", style = LoType.Body, color = LoColors.TextSecondary)
                Text("From: ${info.source}", style = LoType.Body, color = LoColors.TextSecondary)
                Text(
                    "Downloaded once into the app, checked, and kept for next time; delete it in Settings → AI models. " +
                        "Your photos never leave the phone." + if (sheet.wifiOnly) " Downloads use Wi-Fi only." else "",
                    style = LoType.Caption,
                    color = LoColors.TextSecondary,
                )
                if (info.licenseUrl.isNotBlank()) {
                    TextButton(
                        onClick = { runCatching { uri.openUri(info.licenseUrl) } },
                    ) { Text("Read the licence", color = LoColors.Accent) }
                }
                val progress = (sheet.state as? ModelState.Downloading)?.fraction
                if (downloading) {
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = LoColors.Accent)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = LoColors.Accent)
                    }
                }
                sheet.statusText()?.let {
                    Text(it, style = LoType.Body, color = if (sheet.state is ModelState.Failed) LoColors.Warning else LoColors.TextPrimary)
                }
            }
        },
        confirmButton = {
            val label =
                if (sheet.needsWifi) {
                    "Download on mobile data"
                } else if (sheet.state is ModelState.Failed) {
                    "Try again"
                } else {
                    "Download"
                }
            TextButton(
                onClick = { onIntent(EditorIntent.Select(SmartAction.Download(allowMetered = sheet.needsWifi))) },
                enabled = !downloading,
                modifier = Modifier.testTag(SmartTags.DOWNLOAD),
            ) { Text(label, color = if (downloading) LoColors.TextDisabled else LoColors.Accent) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Not now", color = LoColors.TextSecondary) } },
    )
}

/** A light band sweeping over the canvas while a model runs (the brief's progress shimmer). */
@Composable
internal fun SmartShimmer(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Box(modifier.fillMaxSize().testTag(SmartTags.SHIMMER)) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    0f to Color.Transparent,
                    0.5f to Color.White.copy(alpha = SHIMMER_ALPHA),
                    1f to Color.Transparent,
                    start = Offset(phase * SHIMMER_SPAN, 0f),
                    end = Offset((phase + SHIMMER_WIDTH) * SHIMMER_SPAN, SHIMMER_SPAN),
                ),
            ),
        )
    }
}

private const val SHIMMER_MS = 1400
private const val SHIMMER_ALPHA = 0.22f
private const val SHIMMER_SPAN = 1600f
private const val SHIMMER_WIDTH = 0.6f
