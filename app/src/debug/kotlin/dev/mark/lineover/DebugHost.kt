package dev.mark.lineover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.designsystem.gallery.ComponentGalleryScreen

/** Debug builds only: a debug menu (top-right) that opens the component gallery. */
@Composable
internal fun DebugHost(content: @Composable () -> Unit) {
    var showGallery by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    if (showGallery) {
        BackHandler { showGallery = false }
        ComponentGalleryScreen(onBack = { showGallery = false })
        return
    }
    Box(Modifier.fillMaxSize()) {
        content()
        Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(LoSpacing.Xs)) {
            LoIconButton(LoIcons.BugReport, "Debug menu", onClick = { menuOpen = true })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = LoColors.Surface2) {
                DropdownMenuItem(
                    text = { Text("Component gallery", style = LoType.Body, color = LoColors.TextPrimary) },
                    onClick = {
                        menuOpen = false
                        showGallery = true
                    },
                )
            }
        }
    }
}
