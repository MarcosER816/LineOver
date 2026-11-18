package dev.mark.lineover.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.gallery.ComponentGalleryScreen
import dev.mark.lineover.core.designsystem.gallery.GalleryCategories
import org.junit.Test

/**
 * Screenshot of every component in its states: default, active (selected / changed /
 * expanded), disabled, and the default state again at font scale 2.0. Subclasses run
 * the same tests at compact and expanded window widths.
 */
abstract class ComponentScreenshots(
    private val widthClass: String,
) {
    private fun snap(
        name: String,
        default: @Composable () -> Unit,
        active: @Composable () -> Unit,
        disabled: @Composable () -> Unit,
    ) {
        captureRoboImage("components/$widthClass/$name.png") {
            LineOverTheme {
                Column(Modifier.fillMaxWidth().background(LoColors.Bg).padding(LoSpacing.M)) {
                    StateLabel("default")
                    default()
                    StateLabel("active")
                    active()
                    StateLabel("disabled")
                    disabled()
                    StateLabel("fontScale 2.0")
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) { default() }
                }
            }
        }
    }

    @Test
    fun loSlider() =
        snap(
            "lo_slider",
            default = {
                Column {
                    LoSlider(0f, {}, "Exposure", valueRange = -5f..5f, step = 0.01f)
                    LoSlider(0f, {}, "Temperature", track = LoSliderTrack.Temperature)
                }
            },
            active = {
                Column {
                    LoSlider(1.35f, {}, "Exposure", valueRange = -5f..5f, step = 0.01f)
                    LoSlider(-40f, {}, "Temperature", track = LoSliderTrack.Temperature)
                    LoSlider(60f, {}, "Grain", valueRange = 0f..100f, fill = LoSliderFill.FromStart)
                    LoSlider(90f, {}, "Hue", valueRange = -180f..180f, track = LoSliderTrack.Hue)
                }
            },
            disabled = { LoSlider(25f, {}, "Contrast", enabled = false) },
        )

    @Test
    fun loToolButton() =
        snap(
            "lo_tool_button",
            default = {
                Row {
                    LoToolButton(LoIcons.LightMode, "Exposure", false, {})
                    LoToolButton(LoIcons.Tune, "Contrast", false, {})
                }
            },
            active = {
                Row {
                    LoToolButton(LoIcons.LightMode, "Exposure", true, {})
                    LoToolButton(LoIcons.Tune, "Contrast", false, {})
                }
            },
            disabled = { Row { LoToolButton(LoIcons.LightMode, "Exposure", true, {}, enabled = false) } },
        )

    @Test
    fun loCategoryBar() =
        snap(
            "lo_category_bar",
            default = { LoCategoryBar(GalleryCategories, "favorites", {}) },
            active = { LoCategoryBar(GalleryCategories, "light", {}) },
            disabled = { LoCategoryBar(GalleryCategories, "light", {}, enabled = false) },
        )

    @Test
    fun loPanel() =
        snap(
            "lo_panel",
            default = { PanelFrame(LoPanelDetent.Collapsed) },
            active = { PanelFrame(LoPanelDetent.Expanded) },
            disabled = { PanelFrame(LoPanelDetent.Collapsed, enabled = false) },
        )

    @Test
    fun loIconButton() =
        snap(
            "lo_icon_button",
            default = {
                Row {
                    LoIconButton(LoIcons.Undo, "Undo", {})
                    LoIconButton(LoIcons.Visibility, "Before and after", {})
                }
            },
            active = {
                Row {
                    LoIconButton(LoIcons.Undo, "Undo", {})
                    LoIconButton(LoIcons.Visibility, "Before and after", {}, selected = true)
                }
            },
            disabled = { Row { LoIconButton(LoIcons.Undo, "Undo", {}, enabled = false) } },
        )

    @Test
    fun loChip() =
        snap(
            "lo_chip",
            default = {
                Row(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
                    LoChip("Original", false, {})
                    LoChip("4:5", false, {}, leadingIcon = LoIcons.Crop)
                }
            },
            active = {
                Row(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
                    LoChip("Original", false, {})
                    LoChip("4:5", true, {}, leadingIcon = LoIcons.Crop)
                }
            },
            disabled = { Row { LoChip("16:9", false, {}, enabled = false) } },
        )

    @Test
    fun loSegmented() =
        snap(
            "lo_segmented",
            default = { LoSegmented(listOf("RGB", "R", "G", "B"), 0, {}) },
            active = { LoSegmented(listOf("RGB", "R", "G", "B"), 2, {}) },
            disabled = { LoSegmented(listOf("RGB", "R", "G", "B"), 0, {}, enabled = false) },
        )

    @Test
    fun loSwitchRow() =
        snap(
            "lo_switch_row",
            default = { LoSwitchRow("Clipping warnings", false, {}, description = "Show clipped highlights and shadows") },
            active = { LoSwitchRow("Clipping warnings", true, {}, description = "Show clipped highlights and shadows") },
            disabled = { LoSwitchRow("Clipping warnings", false, {}, enabled = false) },
        )

    @Test
    fun loColorWheel() =
        snap(
            "lo_color_wheel",
            default = { LoColorWheel(LoHueSat(0f, 0f), {}, "Midtones tint", Modifier.height(160.dp)) },
            active = { LoColorWheel(LoHueSat(40f, 0.7f), {}, "Midtones tint", Modifier.height(160.dp)) },
            disabled = { LoColorWheel(LoHueSat(40f, 0.7f), {}, "Midtones tint", Modifier.height(160.dp), enabled = false) },
        )

    @Test
    fun loCurveEditorPlaceholder() =
        snap(
            "lo_curve_editor_placeholder",
            default = { LoCurveEditorPlaceholder(Modifier.height(160.dp)) },
            active = { LoCurveEditorPlaceholder(Modifier.height(160.dp)) },
            disabled = { LoCurveEditorPlaceholder(Modifier.height(160.dp), enabled = false) },
        )

    @Test
    fun loCurveEditor() {
        val s = listOf(Offset(0f, 0f), Offset(0.25f, 0.18f), Offset(0.75f, 0.84f), Offset(1f, 1f))
        val curve: (Float) -> Float = { x -> x + 0.3f * x * (1 - x) * (x - 0.5f) * 4 * 0.5f }
        val histogram = FloatArray(64) { i -> (0.2f + 0.7f * kotlin.math.exp(-((i - 30) / 12f).let { it * it })) }
        snap(
            "lo_curve_editor",
            default = { LoCurveEditor(s, curve, { null }, { _, _, _ -> }, {}, Modifier.width(200.dp)) },
            active = { LoCurveEditor(s, curve, { null }, { _, _, _ -> }, {}, Modifier.width(200.dp), LoColors.Danger, histogram) },
            disabled = { LoCurveEditor(s, curve, { null }, { _, _, _ -> }, {}, Modifier.width(200.dp), enabled = false) },
        )
    }

    @Test
    fun loGradeWheel() =
        snap(
            "lo_grade_wheel",
            default = { LoGradeWheel(LoHueSat(0f, 0f), 0f, {}, {}, "Shadows", Modifier.height(170.dp)) },
            active = { LoGradeWheel(LoHueSat(190f, 0.6f), -0.4f, {}, {}, "Shadows", Modifier.height(170.dp)) },
            disabled = { LoGradeWheel(LoHueSat(190f, 0.6f), 0.5f, {}, {}, "Shadows", Modifier.height(170.dp), enabled = false) },
        )

    @Test
    fun loToast() =
        snap(
            "lo_toast",
            default = { LoToast("Saved to Pictures/LineOver") },
            active = { LoToast("Preset applied", actionLabel = "Undo") },
            disabled = { LoToast("Nothing to undo") },
        )

    @Test
    fun loEmptyState() =
        snap(
            "lo_empty_state",
            default = { LoEmptyState(LoIcons.Image, "No projects yet", body = "Open a photo to start editing.") },
            active = {
                LoEmptyState(
                    LoIcons.Image,
                    "No projects yet",
                    body = "Open a photo to start editing.",
                    actionLabel = "Open photo",
                )
            },
            disabled = { LoEmptyState(LoIcons.Image, "No projects yet", actionLabel = "Open photo", actionEnabled = false) },
        )

    @Test
    fun loPlaceholderTool() =
        snap(
            "lo_placeholder_tool",
            default = { LoPlaceholderTool("Liquify", 22, LoIcons.WaterDrop) },
            active = { LoPlaceholderTool("Generative fill", 26, LoIcons.AutoFixHigh) },
            disabled = { LoPlaceholderTool("Liquify", 22, LoIcons.WaterDrop, enabled = false) },
        )

    @Test
    fun componentGallery() {
        captureRoboImage("components/$widthClass/component_gallery.png") {
            LineOverTheme { ComponentGalleryScreen(onBack = {}) }
        }
    }
}

@Composable
private fun StateLabel(text: String) {
    Text(text, style = LoType.Micro, color = LoColors.TextSecondary, modifier = Modifier.padding(top = LoSpacing.S, bottom = LoSpacing.Xxs))
}

@Composable
private fun PanelFrame(
    detent: LoPanelDetent,
    enabled: Boolean = true,
) {
    Box(Modifier.fillMaxWidth().height(300.dp).background(LoColors.CanvasSurroundDark), contentAlignment = Alignment.BottomCenter) {
        LoPanel(rememberLoPanelState(detent), title = "Light") {
            LoSlider(0.35f, {}, "Exposure", valueRange = -5f..5f, step = 0.01f, enabled = enabled)
            LoSlider(-20f, {}, "Highlights", enabled = enabled)
            LoSlider(15f, {}, "Shadows", enabled = enabled)
        }
    }
}
