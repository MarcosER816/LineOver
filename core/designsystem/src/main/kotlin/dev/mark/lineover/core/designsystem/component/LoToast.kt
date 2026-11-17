package dev.mark.lineover.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoDimens
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.LocalReducedMotion
import dev.mark.lineover.core.designsystem.MotionTokens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** What happened to a toast shown with [LoToastState.show]. */
enum class LoToastResult { ActionPerformed, Dismissed }

@Immutable
data class LoToastData(
    val message: String,
    val actionLabel: String?,
)

/** Shows one toast at a time; a new one replaces the current one. */
@Stable
class LoToastState {
    var current: LoToastData? by mutableStateOf(null)
        private set
    private var pending: CompletableDeferred<LoToastResult>? = null

    /** Shows [message] (with an optional action such as "Undo") and suspends until it is acted on or times out. */
    suspend fun show(
        message: String,
        actionLabel: String? = null,
        durationMillis: Long = DEFAULT_DURATION_MILLIS,
    ): LoToastResult {
        pending?.complete(LoToastResult.Dismissed)
        val result = CompletableDeferred<LoToastResult>()
        pending = result
        current = LoToastData(message, actionLabel)
        return try {
            withTimeoutOrNull(durationMillis) { result.await() } ?: LoToastResult.Dismissed
        } finally {
            if (pending === result) {
                pending = null
                current = null
            }
        }
    }

    fun performAction() {
        pending?.complete(LoToastResult.ActionPerformed)
    }

    fun dismiss() {
        pending?.complete(LoToastResult.Dismissed)
    }

    companion object {
        const val DEFAULT_DURATION_MILLIS = 4000L
    }
}

/** Hosts the toasts of a [LoToastState], with the enter/exit motion tokens. */
@Composable
fun LoToastHost(
    state: LoToastState,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    var last by remember { mutableStateOf<LoToastData?>(null) }
    state.current?.let { last = it }
    AnimatedVisibility(
        visible = state.current != null,
        modifier = modifier,
        enter = fadeIn(MotionTokens.enter(reducedMotion)) + slideInVertically(MotionTokens.enter(reducedMotion)) { it / 2 },
        exit = fadeOut(MotionTokens.exit(reducedMotion)) + slideOutVertically(MotionTokens.exit(reducedMotion)) { it / 2 },
    ) {
        last?.let { data -> LoToast(data.message, actionLabel = data.actionLabel, onAction = state::performAction) }
    }
}

/** A toast / undo snackbar: a short message with an optional action. */
@Composable
fun LoToast(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier =
            modifier
                .widthIn(max = 480.dp)
                .heightIn(min = LoDimens.TouchTargetMin)
                .clip(LoShapes.Medium)
                .background(LoColors.Surface3)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = LoSpacing.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = LoType.Body,
            color = LoColors.TextPrimary,
            modifier = Modifier.weight(1f, fill = false).padding(vertical = LoSpacing.S),
        )
        if (actionLabel != null) {
            Box(
                modifier =
                    Modifier
                        .heightIn(min = LoDimens.TouchTargetMin)
                        .clickable(role = Role.Button, onClick = onAction)
                        .padding(horizontal = LoSpacing.M),
                contentAlignment = Alignment.Center,
            ) {
                Text(actionLabel, style = LoType.Body.copy(fontWeight = LoType.Title.fontWeight), color = LoColors.Accent)
            }
        } else {
            Box(Modifier.padding(end = LoSpacing.M))
        }
    }
}

@Preview(name = "LoToast", widthDp = 360)
@Composable
private fun LoToastPreview() {
    LineOverTheme { LoToast("Preset applied", actionLabel = "Undo") }
}
