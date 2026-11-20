package dev.mark.lineover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.mark.lineover.core.designsystem.gallery.ComponentGalleryScreen

/** Debug builds: Settings → Debug opens the debug tools (component gallery, AI self-test). */
internal object DebugTools {
    val gallery: (@Composable (onBack: () -> Unit) -> Unit)? = { onBack ->
        var screen by rememberSaveable { mutableStateOf("menu") }
        when (screen) {
            "gallery" -> ComponentGalleryScreen(onBack = { screen = "menu" })
            "ai" -> AiSelfTestScreen(onBack = { screen = "menu" })
            else -> DebugMenu(onBack = onBack, onGallery = { screen = "gallery" }, onAiSelfTest = { screen = "ai" })
        }
    }
}
