package dev.mark.lineover

import androidx.compose.runtime.Composable
import dev.mark.lineover.core.designsystem.gallery.ComponentGalleryScreen

/** Debug builds: Settings → Debug opens the component gallery. */
internal object DebugTools {
    val gallery: (@Composable (onBack: () -> Unit) -> Unit)? = { onBack -> ComponentGalleryScreen(onBack = onBack) }
}
