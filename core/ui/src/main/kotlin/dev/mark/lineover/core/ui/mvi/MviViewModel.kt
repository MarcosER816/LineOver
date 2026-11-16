package dev.mark.lineover.core.ui.mvi

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/**
 * Base class for the single ViewModel of a screen (docs/ARCHITECTURE.md).
 *
 * - [State] is one immutable value rendered by the screen.
 * - [Intent] is everything the user or the system can ask the screen to do.
 * - [Effect] is a one-shot event (navigation, snackbar) that must not be replayed.
 */
abstract class MviViewModel<State, Intent, Effect>(
    initialState: State,
) : ViewModel() {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val effectChannel = Channel<Effect>(Channel.BUFFERED)
    val effects: Flow<Effect> = effectChannel.receiveAsFlow()

    /** Entry point for the UI. */
    abstract fun onIntent(intent: Intent)

    protected fun setState(reduce: State.() -> State) {
        mutableState.update(reduce)
    }

    protected fun sendEffect(effect: Effect) {
        effectChannel.trySend(effect)
    }
}
