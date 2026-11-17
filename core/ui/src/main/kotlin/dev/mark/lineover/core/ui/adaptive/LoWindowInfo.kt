package dev.mark.lineover.core.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass

/** Window width classes of the Material WindowSizeClass breakpoints (600 / 840 dp). */
enum class LoWidthClass { Compact, Medium, Expanded }

/** What layouts need to know about the window (rule D4: phone, tablet, foldable, landscape). */
@Immutable
data class LoWindowInfo(
    val widthClass: LoWidthClass,
    val widthDp: Float,
    val heightDp: Float,
) {
    val isLandscape: Boolean get() = widthDp > heightDp

    /** List and detail side by side (tablets, unfolded foldables, wide landscape). */
    val showsTwoPanes: Boolean get() = widthClass == LoWidthClass.Expanded

    companion object {
        fun of(
            widthDp: Float,
            heightDp: Float,
        ): LoWindowInfo {
            val sizeClass = WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(widthDp, heightDp)
            val widthClass =
                when {
                    sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> LoWidthClass.Expanded
                    sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> LoWidthClass.Medium
                    else -> LoWidthClass.Compact
                }
            return LoWindowInfo(widthClass, widthDp, heightDp)
        }
    }
}

/** The current window's size class; recomposes on rotation, folding and multi-window resizes. */
@Composable
fun rememberLoWindowInfo(): LoWindowInfo {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    return remember(size, density) { LoWindowInfo.of(size.width / density, size.height / density) }
}
