package dev.mark.lineover.core.designsystem

import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Haptic feedback of the design system.
 *
 * Uses View.performHapticFeedback (respects the user's touch-feedback setting).
 * When the view reports that nothing was performed, it falls back to a predefined
 * VibrationEffect, because some OEM builds (e.g. HyperOS) ignore the view constants.
 */
@Stable
class LoHaptics internal constructor(
    private val view: View,
) {
    /** Light detent, e.g. a slider passing its default value. */
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK, VibrationEffect.EFFECT_TICK)

    /** Confirmation, e.g. a double-tap reset. */
    fun confirm() = perform(HapticFeedbackConstants.CONFIRM, VibrationEffect.EFFECT_CLICK)

    private fun perform(
        constant: Int,
        fallbackEffect: Int,
    ) {
        if (view.performHapticFeedback(constant)) return
        val vibrator = view.context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createPredefined(fallbackEffect))
    }
}

@Composable
fun rememberLoHaptics(): LoHaptics {
    val view = LocalView.current
    return remember(view) { LoHaptics(view) }
}
