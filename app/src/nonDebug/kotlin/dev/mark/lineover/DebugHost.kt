package dev.mark.lineover

import androidx.compose.runtime.Composable

/** Release (and other non-debug) builds: no debug menu, no component gallery. */
@Composable
internal fun DebugHost(content: @Composable () -> Unit) {
    content()
}
