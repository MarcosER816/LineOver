package dev.mark.lineover.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Locks the colour and type tokens: any change to them shows up as a screenshot diff. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class DesignTokensScreenshotTest {
    @Test
    fun designTokens() {
        captureRoboImage("design_tokens.png") {
            LineOverTheme { TokenSheet() }
        }
    }
}

private val colorTokens =
    listOf(
        "bg" to LoColors.Bg,
        "surface1" to LoColors.Surface1,
        "surface2" to LoColors.Surface2,
        "surface3" to LoColors.Surface3,
        "outline" to LoColors.Outline,
        "textPrimary" to LoColors.TextPrimary,
        "textSecondary" to LoColors.TextSecondary,
        "textDisabled" to LoColors.TextDisabled,
        "accent" to LoColors.Accent,
        "onAccent" to LoColors.OnAccent,
        "danger" to LoColors.Danger,
        "success" to LoColors.Success,
        "warning" to LoColors.Warning,
        "maskOverlay" to LoColors.MaskOverlay,
        "clipHi" to LoColors.ClipHi,
        "clipLo" to LoColors.ClipLo,
        "canvasSurroundDark" to LoColors.CanvasSurroundDark,
        "canvasSurroundGrey" to LoColors.CanvasSurroundGrey,
    )

private val typeTokens =
    listOf(
        "Display 24 · 0123456789" to LoType.Display,
        "Headline 20 · 0123456789" to LoType.Headline,
        "Title 16 · 0123456789" to LoType.Title,
        "Body 14 · 0123456789" to LoType.Body,
        "Caption 12 · 0123456789" to LoType.Caption,
        "Micro 11 · 0123456789" to LoType.Micro,
    )

@Composable
private fun TokenSheet() {
    Column(
        modifier = Modifier.fillMaxWidth().background(LoColors.Bg).padding(LoSpacing.M),
        verticalArrangement = Arrangement.spacedBy(LoSpacing.Xxs),
    ) {
        colorTokens.forEach { (name, color) -> ColorRow(name, color) }
        Box(Modifier.size(LoSpacing.Xs))
        typeTokens.forEach { (label, style) -> TypeRow(label, style) }
    }
}

@Composable
private fun ColorRow(
    name: String,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = LoDimens.TouchTargetMin, height = LoSpacing.L)
                .background(color)
                .border(width = LoSpacing.Xxs / 4, color = LoColors.Outline),
        )
        Box(Modifier.width(LoSpacing.S))
        Text(text = name, style = LoType.Body, color = LoColors.TextPrimary)
    }
}

@Composable
private fun TypeRow(
    label: String,
    style: TextStyle,
) {
    Text(text = label, style = style, color = LoColors.TextPrimary)
}
