package dev.mark.lineover.core.designsystem

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private val LoColorScheme =
    darkColorScheme(
        primary = LoColors.Accent,
        onPrimary = LoColors.OnAccent,
        secondary = LoColors.Accent,
        onSecondary = LoColors.OnAccent,
        background = LoColors.Bg,
        onBackground = LoColors.TextPrimary,
        surface = LoColors.Surface1,
        onSurface = LoColors.TextPrimary,
        surfaceVariant = LoColors.Surface2,
        onSurfaceVariant = LoColors.TextSecondary,
        surfaceContainerLowest = LoColors.Bg,
        surfaceContainerLow = LoColors.Surface1,
        surfaceContainer = LoColors.Surface2,
        surfaceContainerHigh = LoColors.Surface3,
        surfaceContainerHighest = LoColors.Surface3,
        outline = LoColors.Outline,
        outlineVariant = LoColors.Outline,
        error = LoColors.Danger,
        onError = LoColors.OnAccent,
    )

/** The one theme of the app. Wrap every screen and every screenshot test in it. */
@Composable
fun LineOverTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reducedMotion =
        remember(context) {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = LoColorScheme,
            typography = LoMaterialTypography,
            content = content,
        )
    }
}
