package dev.mark.lineover.core.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion tokens. Values that follow a finger (sliders, curves, canvas gestures)
 * are never animated while dragging; these specs are for UI chrome only.
 */
object LoMotion {
    val EnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val ENTER_MILLIS = 350
    const val EXIT_MILLIS = 200
    const val STANDARD_MILLIS = 250

    const val PANEL_DAMPING_RATIO = 0.85f
    const val PANEL_STIFFNESS = 400f

    fun <T> enter(reducedMotion: Boolean): FiniteAnimationSpec<T> = if (reducedMotion) snap() else tween(ENTER_MILLIS, easing = EnterEasing)

    fun <T> exit(reducedMotion: Boolean): FiniteAnimationSpec<T> = if (reducedMotion) snap() else tween(EXIT_MILLIS, easing = ExitEasing)

    fun <T> standard(reducedMotion: Boolean): FiniteAnimationSpec<T> =
        if (reducedMotion) snap() else tween(STANDARD_MILLIS, easing = StandardEasing)

    /** Spring used by bottom and side panels. */
    fun <T> panel(reducedMotion: Boolean): FiniteAnimationSpec<T> =
        if (reducedMotion) snap() else spring(dampingRatio = PANEL_DAMPING_RATIO, stiffness = PANEL_STIFFNESS)
}

/** True when the user turned animations off in the system settings. */
val LocalReducedMotion = staticCompositionLocalOf { false }
