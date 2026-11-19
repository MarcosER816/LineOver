package dev.mark.lineover.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.model.BuiltInPresets
import dev.mark.lineover.core.model.CopiedSettings
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.LookAdjustments
import dev.mark.lineover.core.model.LookAdjustments.Companion.PROFILES
import dev.mark.lineover.core.model.LooksState
import dev.mark.lineover.core.model.LutEntry
import dev.mark.lineover.core.model.SettingsGroup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A thumbnail stand-in: a soft gradient tinted by [hue] (screenshots have no engine). */
private fun swatch(hue: Float): Bitmap {
    val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
    for (y in 0 until 48) {
        for (x in 0 until 48) {
            val v = 0.35f + 0.6f * (x + y) / 96f
            bitmap.setPixel(x, y, Color.HSVToColor(floatArrayOf(hue, 0.45f, v)))
        }
    }
    return bitmap
}

/** The Stage 11 sample: thumbnails, a favourite, an applied preset, a LUT, copied settings. */
internal fun EditorState.withLooksSample(): EditorState {
    val presets = BuiltInPresets.ALL
    val luts = listOf(LutEntry("a1", "Warm fade", 17), LutEntry("b2", "Teal amber", 33))
    val thumbs =
        presets.mapIndexed { i, p -> LookTarget.Preset(p.id).key to swatch(i * 18f) } +
            PROFILES.mapIndexed { i, p -> LookTarget.Profile(p).key to swatch(30f + i * 70f) } +
            listOf(LookTarget.Lut(null).key to swatch(200f)) +
            luts.mapIndexed { i, l -> LookTarget.Lut(l.id).key to swatch(20f + i * 160f) }
    val applied = presets.first { it.id == "builtin.golden" }
    return copy(
        looks = LookAdjustments(profile = "vivid", profileAmount = 70f, lutId = "a1", lutName = "Warm fade", lutAmount = 60f),
        looksLibrary =
            LooksState(
                luts = luts,
                favoritePresets = listOf("builtin.golden", "builtin.mono_soft"),
                copied = CopiedSettings(listOf(SettingsGroup.Light, SettingsGroup.WhiteBalance), EditGraph()),
            ),
        appliedPreset = AppliedPreset(applied.id, applied.name, emptyList(), applied.ops, 0.8f),
        lookThumbnails = thumbs.toMap(),
        copyGroups = SettingsGroup.DEFAULTS - SettingsGroup.Detail,
    )
}

/** Every Stage 11 panel as a state sheet at compact and expanded widths, plus fontScale 2.0. */
abstract class LooksPanelScreenshots(
    private val widthClass: String,
    private val panelWidth: Int,
) {
    private val state = EditorState().withLooksSample()

    @Composable
    private fun Label(text: String) {
        Text(text, style = LoType.Caption, color = LoColors.TextSecondary, modifier = Modifier.padding(LoSpacing.Xs))
    }

    private fun sheet(
        name: String,
        tools: List<String>,
        fontScale: Float = 1f,
    ) {
        captureRoboImage("editor/looks_panel/${widthClass}_$name.png") {
            LineOverTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                    Column(Modifier.width(panelWidth.dp).background(LoColors.Surface1)) {
                        tools.forEach { tool ->
                            Label(tool)
                            LooksPanel(tool, state, {}, onClose = {})
                        }
                    }
                }
            }
        }
    }

    @Test
    fun strips() = sheet("strips", listOf(PRESETS_TOOL, PROFILES_TOOL, LUTS_TOOL))

    @Test
    fun copyPasteAndAuto() = sheet("copy_paste_auto", listOf(COPY_PASTE_TOOL, AUTO_TOOL))

    @Test
    fun presetsAtFontScale2() = sheet("font_scale_2", listOf(PRESETS_TOOL), fontScale = 2f)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp-xhdpi")
class CompactLooksPanelScreenshotTest : LooksPanelScreenshots("compact", 411)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h3000dp-mdpi")
class ExpandedLooksPanelScreenshotTest : LooksPanelScreenshots("expanded", 320)
