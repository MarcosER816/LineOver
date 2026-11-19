package dev.mark.lineover.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoChip
import dev.mark.lineover.core.designsystem.component.LoSegmented
import dev.mark.lineover.core.designsystem.component.LoSlider
import dev.mark.lineover.core.designsystem.component.LoSliderFill
import dev.mark.lineover.core.designsystem.component.LoSwitchRow
import dev.mark.lineover.core.io.lens.LensProfileState
import dev.mark.lineover.core.io.lens.LensfunManifest
import dev.mark.lineover.core.model.CropAspect
import dev.mark.lineover.core.model.CropOverlay
import dev.mark.lineover.core.model.GeometryParam
import dev.mark.lineover.core.model.UprightMode
import dev.mark.lineover.core.ui.tools.ToolRegistry
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The Crop category's panels (Stage 9). */
@Composable
internal fun CropPanel(
    toolId: String,
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    onClose: () -> Unit,
) {
    val act = { action: GeometryAction -> onIntent(EditorIntent.Geometry(action)) }
    val g = state.geometry
    Column(Modifier.fillMaxWidth().testTag(EditorTags.GEOMETRY_PANEL)) {
        PanelHeader(requireNotNull(ToolRegistry[toolId]).name, state.outputLabel, onClose)
        val slot = Modifier.testTag(EditorTags.slot(toolId))
        Column(slot.padding(horizontal = LoSpacing.M), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
            when (toolId) {
                CROP_TOOL -> {
                    AspectChips(state, act)
                    LoSegmented(
                        CropOverlay.entries.map { it.label },
                        CropOverlay.entries.indexOf(state.cropOverlay),
                        { act(GeometryAction.Overlay(CropOverlay.entries[it])) },
                        descriptions = CropOverlay.entries.map { "${it.label} overlay while dragging" },
                    )
                    ConstrainSwitch(g.constrain, act)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
                        LoChip("Reset crop", false, { act(GeometryAction.ResetCrop) })
                        LoChip("Reset all", false, { act(GeometryAction.Reset) })
                    }
                    Hint("Drag the handles. Pinch inside the frame to zoom; double tap the frame to reset.")
                }

                ASPECT_TOOL -> {
                    AspectChips(state, act)
                    if (g.aspect == CropAspect.Custom) {
                        LoSlider(g.customWidth, {
                            act(GeometryAction.Custom(it, g.customHeight))
                        }, "Width", valueRange = 1f..CUSTOM_MAX, defaultValue = 1f, fill = LoSliderFill.FromStart)
                        LoSlider(g.customHeight, {
                            act(GeometryAction.Custom(g.customWidth, it))
                        }, "Height", valueRange = 1f..CUSTOM_MAX, defaultValue = 1f, fill = LoSliderFill.FromStart)
                    }
                }

                STRAIGHTEN_TOOL -> {
                    StraightenRuler(
                        value = g.straighten,
                        onValueChange = { act(GeometryAction.Set(GeometryParam.Straighten, it)) },
                        onDragging = { act(GeometryAction.Dragging(it)) },
                        onFinished = { onIntent(EditorIntent.EndEdit) },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
                        LoChip("Auto", false, {
                            act(GeometryAction.AutoStraighten)
                        }, Modifier.testTag(EditorTags.AUTO_STRAIGHTEN), leadingIcon = LoIcons.AutoFixHigh)
                        LoChip("Level", false, { act(GeometryAction.Set(GeometryParam.Straighten, 0f)) })
                    }
                    ConstrainSwitch(g.constrain, act)
                }

                ROTATE_TOOL -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
                        LoChip("Rotate left", false, { act(GeometryAction.Rotate(clockwise = false)) })
                        LoChip("Rotate right", false, { act(GeometryAction.Rotate(clockwise = true)) }, leadingIcon = LoIcons.RotateRight)
                    }
                }

                FLIP_TOOL -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
                        LoChip(
                            "Flip horizontal",
                            g.flipHorizontal,
                            { act(GeometryAction.Flip(horizontal = true)) },
                            leadingIcon = LoIcons.Flip,
                        )
                        LoChip("Flip vertical", g.flipVertical, { act(GeometryAction.Flip(horizontal = false)) })
                    }
                }

                PERSPECTIVE_CROP_TOOL -> {
                    LoSwitchRow(
                        "Perspective crop",
                        g.hasPerspectiveCrop,
                        { act(GeometryAction.PerspectiveCrop(it)) },
                        description = "Drag the four corners onto a tilted rectangle (a page, a painting): it becomes the photo",
                    )
                }
            }
        }
    }
}

/** The Optics category's panels (Stage 9). */
@Composable
internal fun OpticsPanel(
    toolId: String,
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    onClose: () -> Unit,
) {
    val act = { action: GeometryAction -> onIntent(EditorIntent.Geometry(action)) }
    val g = state.geometry
    Column(Modifier.fillMaxWidth().testTag(EditorTags.GEOMETRY_PANEL)) {
        PanelHeader(requireNotNull(ToolRegistry[toolId]).name, null, onClose)
        Column(
            Modifier.testTag(EditorTags.slot(toolId)).padding(horizontal = LoSpacing.M),
            verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
        ) {
            when (toolId) {
                UPRIGHT_TOOL -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
                        for (mode in UprightMode.entries) {
                            LoChip(
                                mode.label,
                                g.upright == mode,
                                { act(GeometryAction.Upright(mode)) },
                                Modifier.testTag(EditorTags.upright(mode.name)),
                            )
                        }
                    }
                    if (g.upright == UprightMode.Guided) {
                        Hint("Draw 2 to 4 lines along edges that should be vertical or horizontal (${g.guides.size} drawn).")
                        LoChip("Clear lines", false, { act(GeometryAction.ClearGuides) }, enabled = g.guides.isNotEmpty())
                    }
                    for (param in TRANSFORM_PARAMS) GeometrySlider(param, state, onIntent)
                    ConstrainSwitch(g.constrain, act)
                }

                DISTORTION_TOOL -> {
                    GeometrySlider(GeometryParam.Distortion, state, onIntent)
                    GeometrySlider(GeometryParam.DistortionEdge, state, onIntent)
                    Hint("+ straightens barrel distortion (lines bowing out), − pincushion.")
                    LensProfileSection(state, act)
                }

                CHROMATIC_TOOL -> {
                    LoChip(
                        "Auto",
                        false,
                        { act(GeometryAction.AutoChromatic) },
                        Modifier.testTag(EditorTags.AUTO_CHROMATIC),
                        leadingIcon = LoIcons.AutoFixHigh,
                    )
                    GeometrySlider(GeometryParam.ChromaticRed, state, onIntent)
                    GeometrySlider(GeometryParam.ChromaticBlue, state, onIntent)
                }

                LENS_VIGNETTING_TOOL -> {
                    GeometrySlider(GeometryParam.Vignetting, state, onIntent)
                    GeometrySlider(GeometryParam.VignettingMidpoint, state, onIntent, enabled = g.vignetting != 0f)
                }
            }
        }
    }
}

/**
 * Lens profile (Stage 9, Lensfun): download the database (explicit tap, size and licence
 * shown), then the photo's lens is matched from its EXIF or picked from the list; the
 * profile's distortion and vignetting amounts and its chromatic aberration apply on top of
 * the manual sliders, in the same single resample.
 */
@Composable
private fun LensProfileSection(
    state: EditorState,
    act: (GeometryAction) -> Unit,
) {
    val g = state.geometry
    val lens = state.lensProfiles
    Column(Modifier.fillMaxWidth().testTag(EditorTags.LENS_PROFILE), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        Text("Lens profile", style = LoType.Body, color = LoColors.TextPrimary)
        when (lens) {
            is LensProfileState.Missing, is LensProfileState.Failed -> {
                Hint(
                    "Corrections for thousands of camera lenses (Lensfun database, " +
                        "${"%.1f".format(Locale.ROOT, LensfunManifest.TOTAL_BYTES / BYTES_PER_MB)} MB, ${LensfunManifest.LICENCE}). " +
                        "Phone cameras are not in it." + if (lens is LensProfileState.Failed) " Last try failed: ${lens.message}." else "",
                )
                LoChip("Download lens profiles", false, { act(GeometryAction.DownloadProfiles) }, leadingIcon = LoIcons.Download)
            }

            is LensProfileState.Downloading -> {
                Hint("Downloading lens profiles… ${lens.bytes * PERCENT / lens.total.coerceAtLeast(1)} %")
            }

            is LensProfileState.Installed -> {
                LaunchedEffect(state.image) { act(GeometryAction.LoadLensCandidates) }
                LoSwitchRow(
                    "Use lens profile",
                    g.profile != null,
                    { act(GeometryAction.UseProfile(it)) },
                    description = g.profile?.lens ?: "Matched from the photo's camera and lens",
                )
                if (state.lensCandidates.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
                        for (name in state.lensCandidates) {
                            LoChip(name, g.profile?.lens == name, { act(GeometryAction.UseProfile(true, name)) })
                        }
                    }
                }
                val profile = g.profile
                if (profile != null) {
                    LoSlider(
                        g.profileDistortion,
                        { act(GeometryAction.Set(GeometryParam.ProfileDistortion, it)) },
                        "Profile distortion",
                        valueRange = 0f..200f,
                        defaultValue = 100f,
                        fill = LoSliderFill.FromStart,
                        enabled = profile.distortion.isNotEmpty(),
                    )
                    LoSlider(
                        g.profileVignetting,
                        { act(GeometryAction.Set(GeometryParam.ProfileVignetting, it)) },
                        "Profile vignetting",
                        valueRange = 0f..200f,
                        defaultValue = 100f,
                        fill = LoSliderFill.FromStart,
                        enabled = profile.vignette.isNotEmpty(),
                    )
                    LoSwitchRow(
                        "Profile chromatic aberration",
                        g.profileChromatic,
                        { act(GeometryAction.ProfileChromatic(it)) },
                        enabled = profile.tcaRed.isNotEmpty(),
                    )
                }
                Hint(LensfunManifest.ATTRIBUTION)
            }
        }
    }
}

private const val BYTES_PER_MB = 1_048_576.0
private const val PERCENT = 100

private val TRANSFORM_PARAMS =
    listOf(
        GeometryParam.Vertical,
        GeometryParam.Horizontal,
        GeometryParam.Rotate,
        GeometryParam.Aspect,
        GeometryParam.Scale,
        GeometryParam.OffsetX,
        GeometryParam.OffsetY,
    )

private const val CUSTOM_MAX = 20f

/** "2400 × 3000 · 4:5": the size the edit will export at. */
private val EditorState.outputLabel: String?
    get() {
        val op = geometryOp ?: return image?.let { "${it.width} × ${it.height}" }
        val aspect = geometry.aspect.takeIf { it != CropAspect.Free }?.label
        return "${op.outputWidth} × ${op.outputHeight}" + (aspect?.let { " · $it" } ?: "")
    }

@Composable
private fun GeometrySlider(
    param: GeometryParam,
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    enabled: Boolean = true,
) {
    LoSlider(
        state.geometry[param],
        { onIntent(EditorIntent.Geometry(GeometryAction.Set(param, it))) },
        param.label,
        Modifier.testTag(EditorTags.slider(param.toolId)),
        valueRange = param.min..param.max,
        defaultValue = param.default,
        step = param.step,
        fill = LoSliderFill.FromDefault,
        enabled = enabled,
        onValueChangeFinished = { onIntent(EditorIntent.EndEdit) },
    )
}

@Composable
private fun AspectChips(
    state: EditorState,
    act: (GeometryAction) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        for (aspect in CropAspect.entries) {
            LoChip(aspect.label, state.geometry.aspect == aspect, {
                act(GeometryAction.Aspect(aspect))
            }, Modifier.testTag(EditorTags.aspect(aspect.name)))
        }
        LoChip("Portrait ⇄ landscape", false, { act(GeometryAction.SwapOrientation) }, enabled = state.geometry.aspect != CropAspect.Free)
    }
}

@Composable
private fun ConstrainSwitch(
    on: Boolean,
    act: (GeometryAction) -> Unit,
) {
    LoSwitchRow(
        "Constrain crop",
        on,
        { act(GeometryAction.Constrain(it)) },
        Modifier.testTag(EditorTags.CONSTRAIN),
        description = "Keep the crop inside the photo: no empty corners",
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = LoType.Caption, color = LoColors.TextSecondary)
}

/**
 * The straighten ruler: drag it sideways (12 dp per degree, ±45°). It snaps to 0 within
 * 0.25° with a haptic tick when it crosses or lands on 0.
 */
@Composable
internal fun StraightenRuler(
    value: Float,
    onValueChange: (Float) -> Unit,
    onDragging: (Boolean) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    var raw by remember { mutableFloatStateOf(value) }
    val measurer = rememberTextMeasurer()
    val label = String.format(Locale.ROOT, "%.1f°", value)
    Column(modifier.fillMaxWidth()) {
        Text("Straighten $label", style = LoType.Caption, color = LoColors.TextSecondary)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(RULER_HEIGHT)
                .testTag(EditorTags.STRAIGHTEN_RULER)
                .semantics {
                    contentDescription = "Straighten ruler"
                    stateDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(value, -MAX_ANGLE..MAX_ANGLE)
                    setProgress { target ->
                        onValueChange(target.coerceIn(-MAX_ANGLE, MAX_ANGLE))
                        onFinished()
                        true
                    }
                }.pointerInput(Unit) {
                    val perDegree = PER_DEGREE.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            raw = current
                            onDragging(true)
                        },
                        onDragEnd = {
                            onDragging(false)
                            onFinished()
                        },
                        onDragCancel = {
                            onDragging(false)
                            onFinished()
                        },
                    ) { change, dx ->
                        change.consume()
                        val before = current
                        raw = (raw - dx / perDegree).coerceIn(-MAX_ANGLE, MAX_ANGLE)
                        val snapped = if (abs(raw) < SNAP) 0f else (raw * TENTHS).roundToInt() / TENTHS
                        // A tick when the value reaches 0 or crosses it.
                        if (snapped != before && (snapped == 0f || before * snapped < 0f)) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        if (snapped != before) onValueChange(snapped)
                    }
                },
        ) {
            val perDegree = PER_DEGREE.toPx()
            val center = size.width / 2
            val first = (value - center / perDegree).toInt() - 1
            val last = (value + center / perDegree).toInt() + 1
            for (d in first..last) {
                if (d < -MAX_ANGLE || d > MAX_ANGLE) continue
                val x = center + (d - value) * perDegree
                val major = d % MAJOR_EVERY == 0
                val h = if (major) size.height * 0.5f else size.height * 0.25f
                drawLine(
                    if (d ==
                        0
                    ) {
                        LoColors.TextPrimary
                    } else {
                        LoColors.TextSecondary
                    },
                    Offset(x, size.height - h),
                    Offset(x, size.height),
                    1.dp.toPx(),
                )
                if (d % LABEL_EVERY == 0) {
                    val text = measurer.measure("$d", LoType.Caption)
                    drawText(text, color = LoColors.TextSecondary, topLeft = Offset(x - text.size.width / 2f, 0f))
                }
            }
            drawLine(LoColors.Accent, Offset(center, 0f), Offset(center, size.height), 2.dp.toPx())
        }
    }
}

private val RULER_HEIGHT = 48.dp
private val PER_DEGREE = 12.dp
private const val MAX_ANGLE = 45f
private const val SNAP = 0.25f
private const val TENTHS = 10f
private const val MAJOR_EVERY = 5
private const val LABEL_EVERY = 15
