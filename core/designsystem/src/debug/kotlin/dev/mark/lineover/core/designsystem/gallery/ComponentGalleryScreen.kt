package dev.mark.lineover.core.designsystem.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoCategory
import dev.mark.lineover.core.designsystem.component.LoCategoryBar
import dev.mark.lineover.core.designsystem.component.LoChip
import dev.mark.lineover.core.designsystem.component.LoColorWheel
import dev.mark.lineover.core.designsystem.component.LoCurveEditor
import dev.mark.lineover.core.designsystem.component.LoCurveEditorPlaceholder
import dev.mark.lineover.core.designsystem.component.LoEmptyState
import dev.mark.lineover.core.designsystem.component.LoGradeWheel
import dev.mark.lineover.core.designsystem.component.LoHueSat
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.designsystem.component.LoPanel
import dev.mark.lineover.core.designsystem.component.LoPlaceholderTool
import dev.mark.lineover.core.designsystem.component.LoSegmented
import dev.mark.lineover.core.designsystem.component.LoSlider
import dev.mark.lineover.core.designsystem.component.LoSliderFill
import dev.mark.lineover.core.designsystem.component.LoSliderTrack
import dev.mark.lineover.core.designsystem.component.LoSwitchRow
import dev.mark.lineover.core.designsystem.component.LoToastHost
import dev.mark.lineover.core.designsystem.component.LoToastResult
import dev.mark.lineover.core.designsystem.component.LoToastState
import dev.mark.lineover.core.designsystem.component.LoToolButton
import dev.mark.lineover.core.designsystem.component.rememberLoPanelState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The 18 editor categories in their fixed order (docs/TOOL_CATALOG.md), for the gallery. */
val GalleryCategories =
    listOf(
        LoCategory("favorites", "Favorites", LoIcons.Star),
        LoCategory("presets", "Presets", LoIcons.Style),
        LoCategory("auto", "Auto", LoIcons.AutoAwesome),
        LoCategory("light", "Light", LoIcons.LightMode),
        LoCategory("color", "Color", LoIcons.Palette),
        LoCategory("effects", "Effects", LoIcons.BlurOn),
        LoCategory("detail", "Detail", LoIcons.Grain),
        LoCategory("optics", "Optics", LoIcons.Lens),
        LoCategory("crop", "Crop", LoIcons.Crop),
        LoCategory("masks", "Masks", LoIcons.Gradient),
        LoCategory("select", "Select", LoIcons.SelectAll),
        LoCategory("retouch", "Retouch", LoIcons.Healing),
        LoCategory("paint", "Paint", LoIcons.Brush),
        LoCategory("filters", "Filters", LoIcons.Filter),
        LoCategory("liquify", "Liquify", LoIcons.WaterDrop),
        LoCategory("text", "Text", LoIcons.TextFields),
        LoCategory("shapes", "Shapes", LoIcons.Shapes),
        LoCategory("ai", "AI", LoIcons.AutoFixHigh),
    )

/**
 * Debug-only gallery of every Lo* component, interactive, for checking them on the phone.
 * Lives in the debug source set, so it never ships in a release build.
 */
@Composable
fun ComponentGalleryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val toast = remember { LoToastState() }
    var showPanel by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(LoColors.Bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                LoIconButton(LoIcons.ArrowBack, "Back", onClick = onBack)
                Text("Component gallery", style = LoType.Headline, color = LoColors.TextPrimary)
            }
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(minSize = 340.dp),
                contentPadding = PaddingValues(LoSpacing.M),
                horizontalArrangement = Arrangement.spacedBy(LoSpacing.M),
                verticalItemSpacing = LoSpacing.M,
                modifier = Modifier.weight(1f),
            ) {
                item { SlidersSection() }
                item { ToolButtonsSection() }
                item { CategoryBarSection() }
                item { ButtonsAndChipsSection() }
                item { SwitchesSection(showPanel) { showPanel = it } }
                item { ColorWheelSection() }
                item { Section("LoCurveEditorPlaceholder") { LoCurveEditorPlaceholder() } }
                item {
                    Section("LoCurveEditor") {
                        LoCurveEditor(
                            listOf(Offset(0f, 0f), Offset(0.3f, 0.2f), Offset(0.7f, 0.8f), Offset(1f, 1f)),
                            { it },
                            { null },
                            { _, _, _ -> },
                            {},
                        )
                    }
                }
                item { Section("LoGradeWheel") { LoGradeWheel(LoHueSat(200f, 0.5f), -0.3f, {}, {}, "Shadows") } }
                item { ToastSection(toast) }
                item { EmptyAndPlaceholderSection() }
            }
        }
        if (showPanel) {
            LoPanel(rememberLoPanelState(), Modifier.align(Alignment.BottomCenter), title = "Light") {
                var exposure by remember { mutableFloatStateOf(0f) }
                var highlights by remember { mutableFloatStateOf(0f) }
                LoSlider(exposure, { exposure = it }, "Exposure", valueRange = -5f..5f, step = 0.01f)
                LoSlider(highlights, { highlights = it }, "Highlights")
                LoSlider(0f, {}, "Shadows")
            }
        }
        LoToastHost(toast, Modifier.align(Alignment.BottomCenter).padding(LoSpacing.M))
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(LoColors.Surface1, LoShapes.Medium).padding(vertical = LoSpacing.S),
        verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
    ) {
        Text(title, style = LoType.Title, color = LoColors.TextPrimary, modifier = Modifier.padding(horizontal = LoSpacing.M))
        content()
    }
}

@Composable
private fun SlidersSection() {
    var exposure by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
    var temperature by remember { mutableFloatStateOf(0f) }
    var tint by remember { mutableFloatStateOf(0f) }
    var hue by remember { mutableFloatStateOf(0f) }
    var grain by remember { mutableFloatStateOf(0f) }
    var secondFinger by remember { mutableStateOf(false) }
    Section("LoSlider") {
        Text(
            "Second finger: ${if (secondFinger) "down" else "up"}",
            style = LoType.Caption,
            color = if (secondFinger) LoColors.Accent else LoColors.TextSecondary,
            modifier = Modifier.padding(horizontal = LoSpacing.M),
        )
        val report: (Boolean) -> Unit = { secondFinger = it }
        LoSlider(exposure, { exposure = it }, "Exposure", valueRange = -5f..5f, step = 0.01f, onSecondaryPointerChange = report)
        LoSlider(contrast, { contrast = it }, "Contrast", onSecondaryPointerChange = report)
        LoSlider(temperature, { temperature = it }, "Temperature", track = LoSliderTrack.Temperature, onSecondaryPointerChange = report)
        LoSlider(tint, { tint = it }, "Tint", track = LoSliderTrack.Tint, onSecondaryPointerChange = report)
        LoSlider(hue, { hue = it }, "Hue", valueRange = -180f..180f, track = LoSliderTrack.Hue, onSecondaryPointerChange = report)
        LoSlider(grain, { grain = it }, "Grain", valueRange = 0f..100f, fill = LoSliderFill.FromStart)
        LoSlider(25f, {}, "Disabled", enabled = false)
    }
}

@Composable
private fun ToolButtonsSection() {
    var selected by remember { mutableIntStateOf(0) }
    val tools =
        listOf(LoIcons.LightMode to "Exposure", LoIcons.Tune to "Contrast", LoIcons.Palette to "Color", LoIcons.Gradient to "Curves")
    Section("LoToolButton") {
        Row(Modifier.padding(horizontal = LoSpacing.Xs)) {
            tools.forEachIndexed { index, (icon, label) ->
                LoToolButton(icon, label, selected = index == selected, onClick = { selected = index }, enabled = index != tools.lastIndex)
            }
        }
    }
}

@Composable
private fun CategoryBarSection() {
    var selected by rememberSaveable { mutableStateOf("light") }
    Section("LoCategoryBar") {
        LoCategoryBar(GalleryCategories, selected, { selected = it })
    }
}

@Composable
private fun ButtonsAndChipsSection() {
    var compare by remember { mutableStateOf(false) }
    var chip by remember { mutableIntStateOf(1) }
    var segment by remember { mutableIntStateOf(0) }
    Section("LoIconButton · LoChip · LoSegmented") {
        Row(Modifier.padding(horizontal = LoSpacing.Xs)) {
            LoIconButton(LoIcons.Undo, "Undo", onClick = {})
            LoIconButton(LoIcons.Redo, "Redo", onClick = {}, enabled = false)
            LoIconButton(LoIcons.Visibility, "Before and after", onClick = { compare = !compare }, selected = compare)
            LoIconButton(LoIcons.MoreVert, "More", onClick = {})
        }
        FlowRow(Modifier.padding(horizontal = LoSpacing.M), horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
            listOf("Original", "1:1", "4:5", "16:9").forEachIndexed { index, label ->
                LoChip(label, selected = index == chip, onClick = { chip = index })
            }
            LoChip("Free", selected = false, onClick = {}, enabled = false)
        }
        LoSegmented(listOf("RGB", "R", "G", "B"), segment, { segment = it }, Modifier.padding(horizontal = LoSpacing.M))
    }
}

@Composable
private fun SwitchesSection(
    showPanel: Boolean,
    onShowPanel: (Boolean) -> Unit,
) {
    var clipping by remember { mutableStateOf(true) }
    Section("LoSwitchRow · LoPanel") {
        LoSwitchRow("Show panel", showPanel, onShowPanel, description = "LoPanel with 160 / 280 dp detents")
        LoSwitchRow("Clipping warnings", clipping, { clipping = it })
        LoSwitchRow("Disabled", checked = false, onCheckedChange = {}, enabled = false)
    }
}

@Composable
private fun ColorWheelSection() {
    var value by remember { mutableStateOf(LoHueSat(0f, 0f)) }
    Section("LoColorWheel") {
        LoColorWheel(value, { value = it }, "Midtones tint", Modifier.padding(horizontal = LoSpacing.M))
        Text(
            "Hue ${value.hue.roundToInt()}°, saturation ${(value.saturation * 100).roundToInt()} % (double tap resets)",
            style = LoType.Caption,
            color = LoColors.TextSecondary,
            modifier = Modifier.padding(horizontal = LoSpacing.M),
        )
    }
}

@Composable
private fun ToastSection(toast: LoToastState) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("–") }
    Section("LoToast") {
        LoChip("Show undo toast", selected = false, onClick = {
            scope.launch {
                result = if (toast.show("Preset applied", "Undo") == LoToastResult.ActionPerformed) "Undone" else "Dismissed"
            }
        }, modifier = Modifier.padding(horizontal = LoSpacing.M))
        Text(
            "Last result: $result",
            style = LoType.Caption,
            color = LoColors.TextSecondary,
            modifier = Modifier.padding(horizontal = LoSpacing.M),
        )
    }
}

@Composable
private fun EmptyAndPlaceholderSection() {
    Section("LoEmptyState · LoPlaceholderTool") {
        LoEmptyState(LoIcons.Image, "No projects yet", body = "Open a photo to start editing.", actionLabel = "Open photo")
        LoPlaceholderTool("Liquify", stage = 22, icon = LoIcons.WaterDrop, modifier = Modifier.padding(horizontal = LoSpacing.M))
    }
}
