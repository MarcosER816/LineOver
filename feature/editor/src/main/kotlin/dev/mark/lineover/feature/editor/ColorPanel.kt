package dev.mark.lineover.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoChip
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.designsystem.component.LoSegmented
import dev.mark.lineover.core.designsystem.component.LoSlider
import dev.mark.lineover.core.designsystem.component.LoSliderDefaults
import dev.mark.lineover.core.designsystem.component.LoSliderFill
import dev.mark.lineover.core.designsystem.component.LoSliderTrack
import dev.mark.lineover.core.designsystem.component.LoSwitchRow
import dev.mark.lineover.core.model.ColorAdjustments
import dev.mark.lineover.core.model.HslBand
import dev.mark.lineover.core.model.HslChannel
import dev.mark.lineover.feature.editor.canvas.PickMode
import kotlin.math.roundToInt

/** The tools of the Color category built in Stage 6 (the others keep their placeholder). */
internal val COLOR_TOOLS =
    setOf(
        WHITE_BALANCE,
        "color.vibrance",
        "color.saturation",
        HSL_MIXER,
        "color.invert",
        "color.posterize",
        "color.threshold",
    )

internal const val WHITE_BALANCE = "color.white_balance"
internal const val HSL_MIXER = "color.hsl_color_mixer"

/** The panel of one Color tool (Stage 6). */
@Composable
internal fun ColorPanel(
    toolId: String,
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    onClose: () -> Unit,
) {
    val color = state.color
    Column(Modifier.fillMaxWidth().testTag(EditorTags.COLOR_PANEL)) {
        val title = requireNotNull(dev.mark.lineover.core.ui.tools.ToolRegistry[toolId]).name
        Row(
            Modifier.fillMaxWidth().padding(start = LoSpacing.M),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
        ) {
            Text(title, style = LoType.Title, color = LoColors.TextPrimary, modifier = Modifier.weight(1f))
            when (toolId) {
                WHITE_BALANCE -> {
                    LoChip(
                        "As shot",
                        selected = false,
                        onClick = { onIntent(EditorIntent.AsShot) },
                        modifier = Modifier.testTag(EditorTags.AS_SHOT),
                    )
                    LoIconButton(
                        LoIcons.Colorize,
                        if (state.pickMode == PickMode.Eyedropper) "Eyedropper on: tap something grey" else "Eyedropper",
                        onClick = { onIntent(EditorIntent.ToggleEyedropper) },
                        selected = state.pickMode == PickMode.Eyedropper,
                        modifier = Modifier.testTag(EditorTags.EYEDROPPER),
                    )
                }

                HSL_MIXER -> {
                    LoIconButton(
                        LoIcons.AdsClick,
                        if (state.pickMode ==
                            PickMode.Targeted
                        ) {
                            "Targeted adjustment on: drag a colour up or down"
                        } else {
                            "Targeted adjustment"
                        },
                        onClick = { onIntent(EditorIntent.ToggleTargeted) },
                        selected = state.pickMode == PickMode.Targeted,
                        modifier = Modifier.testTag(EditorTags.TARGETED),
                    )
                }
            }
            LoIconButton(LoIcons.Close, "Close $title", onClick = onClose)
        }
        val slot = Modifier.testTag(EditorTags.slot(toolId))
        when (toolId) {
            WHITE_BALANCE -> {
                WhiteBalanceControls(color, onIntent, slot)
            }

            "color.vibrance" -> {
                LoSlider(color.vibrance, {
                    onIntent(EditorIntent.SetVibrance(it))
                }, "Vibrance", slot, valueRange = -100f..100f, onValueChangeFinished = { onIntent(EditorIntent.EndEdit) })
            }

            "color.saturation" -> {
                LoSlider(color.saturation, {
                    onIntent(EditorIntent.SetSaturation(it))
                }, "Saturation", slot, valueRange = -100f..100f, onValueChangeFinished = { onIntent(EditorIntent.EndEdit) })
            }

            HSL_MIXER -> {
                HslControls(state, onIntent, slot)
            }

            "color.invert" -> {
                LoSwitchRow("Invert", color.invert, { onIntent(EditorIntent.SetInvert(it)) }, slot, description = "Mid grey stays mid grey")
            }

            "color.posterize" -> {
                PosterizeControls(color, onIntent, slot)
            }

            "color.threshold" -> {
                ThresholdControls(color, onIntent, slot)
            }
        }
    }
}

@Composable
private fun WhiteBalanceControls(
    color: ColorAdjustments,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        // Linear in mireds: equal steps look equally warmer or cooler.
        LoSlider(
            value = ColorAdjustments.temperaturePosition(color.temperature),
            onValueChange = { onIntent(EditorIntent.SetWhiteBalance(ColorAdjustments.kelvinAt(it), color.tint)) },
            onValueChangeFinished = { onIntent(EditorIntent.EndEdit) },
            label = "Temperature",
            valueRange = 0f..1f,
            defaultValue = ColorAdjustments.temperaturePosition(ColorAdjustments.DEFAULT_KELVIN),
            step = 0f,
            track = LoSliderTrack.Temperature,
            valueText = { ColorAdjustments.kelvinText(ColorAdjustments.kelvinAt(it)) },
            modifier = Modifier.testTag(EditorTags.TEMPERATURE),
        )
        LoSlider(
            value = color.tint,
            onValueChange = { onIntent(EditorIntent.SetWhiteBalance(color.temperature, it)) },
            onValueChangeFinished = { onIntent(EditorIntent.EndEdit) },
            label = "Tint",
            valueRange = -ColorAdjustments.TINT_RANGE..ColorAdjustments.TINT_RANGE,
            track = LoSliderTrack.Tint,
            modifier = Modifier.testTag(EditorTags.TINT),
        )
    }
}

@Composable
private fun HslControls(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier,
) {
    val channel = state.hslChannel
    Column(modifier) {
        LoSegmented(
            options = HslChannel.entries.map { it.label },
            selectedIndex = channel.ordinal,
            onSelect = { onIntent(EditorIntent.SelectHslChannel(HslChannel.entries[it])) },
            modifier = Modifier.padding(horizontal = LoSpacing.M, vertical = LoSpacing.Xxs),
        )
        val values = state.color.hsl(channel)
        HslBand.entries.forEach { band ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .padding(start = LoSpacing.M)
                        .size(BAND_DOT)
                        .clip(CircleShape)
                        .background(band.dot)
                        .testTag(EditorTags.hslDot(band.name)),
                )
                LoSlider(
                    value = values[band.ordinal],
                    onValueChange = { onIntent(EditorIntent.SetHsl(channel, band, it)) },
                    onValueChangeFinished = { onIntent(EditorIntent.EndEdit) },
                    label = band.label,
                    valueRange = -100f..100f,
                    modifier = Modifier.weight(1f).testTag(EditorTags.hslSlider(band.name)),
                )
            }
        }
    }
}

@Composable
private fun PosterizeControls(
    color: ColorAdjustments,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        val on = color.posterize >= 2
        LoSwitchRow("Posterize", on, { onIntent(EditorIntent.SetPosterize(if (it) ColorAdjustments.DEFAULT_POSTERIZE else 0)) })
        LoSlider(
            value = (if (on) color.posterize else ColorAdjustments.DEFAULT_POSTERIZE).toFloat(),
            onValueChange = { onIntent(EditorIntent.SetPosterize(it.roundToInt())) },
            onValueChangeFinished = { onIntent(EditorIntent.EndEdit) },
            label = "Levels",
            valueRange = 2f..EditorViewModel.MAX_POSTERIZE.toFloat(),
            defaultValue = ColorAdjustments.DEFAULT_POSTERIZE.toFloat(),
            fill = LoSliderFill.FromStart,
            enabled = on,
        )
    }
}

@Composable
private fun ThresholdControls(
    color: ColorAdjustments,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        val level = color.threshold
        LoSwitchRow(
            "Threshold",
            level != null,
            { onIntent(EditorIntent.SetThreshold(if (it) ColorAdjustments.DEFAULT_THRESHOLD else null)) },
        )
        LoSlider(
            value = (level ?: ColorAdjustments.DEFAULT_THRESHOLD) * PERCENT,
            onValueChange = { onIntent(EditorIntent.SetThreshold(it / PERCENT)) },
            onValueChangeFinished = { onIntent(EditorIntent.EndEdit) },
            label = "Level",
            valueRange = 0f..PERCENT,
            defaultValue = ColorAdjustments.DEFAULT_THRESHOLD * PERCENT,
            fill = LoSliderFill.FromStart,
            valueText = { "${LoSliderDefaults.formatValue(it, 1f, signed = false)} %" },
            enabled = level != null,
        )
    }
}

/** The colour of a band's dot (sRGB, roughly the band's centre hue). */
internal val HslBand.dot: Color
    get() =
        when (this) {
            HslBand.Red -> Color(0xFFE5484D)
            HslBand.Orange -> Color(0xFFF08A24)
            HslBand.Yellow -> Color(0xFFF2CC2E)
            HslBand.Green -> Color(0xFF46B35A)
            HslBand.Aqua -> Color(0xFF2EC4C4)
            HslBand.Blue -> Color(0xFF3A7BFF)
            HslBand.Purple -> Color(0xFF8B5CF6)
            HslBand.Magenta -> Color(0xFFD94BD0)
        }

private val BAND_DOT = 12.dp
private const val PERCENT = 100f
