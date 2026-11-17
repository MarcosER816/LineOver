package dev.mark.lineover

import androidx.compose.runtime.Composable

/** Release (and other non-debug) builds: no debug tools, no component gallery. */
internal object DebugTools {
    val gallery: (@Composable (onBack: () -> Unit) -> Unit)? = null
}
