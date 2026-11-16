package dev.mark.lineover.core.designsystem

import androidx.compose.ui.unit.dp

/** Spacing scale: 4 / 8 / 12 / 16 / 24 / 32 dp. */
object LoSpacing {
    val Xxs = 4.dp
    val Xs = 8.dp
    val S = 12.dp
    val M = 16.dp
    val L = 24.dp
    val Xl = 32.dp
}

/** Fixed sizes of the editor shell. */
object LoDimens {
    val TouchTargetMin = 48.dp
    val TopBarHeight = 56.dp
    val CategoryBarHeight = 64.dp
    val ToolStripHeight = 56.dp
    val PanelMinHeight = 160.dp
    val PanelMaxHeight = 280.dp
    val SidePanelWidth = 320.dp

    /** A bottom panel never covers more than this fraction of the window height. */
    const val PANEL_MAX_HEIGHT_FRACTION = 0.4f
}
