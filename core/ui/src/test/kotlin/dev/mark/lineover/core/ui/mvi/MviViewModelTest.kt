package dev.mark.lineover.core.ui.mvi

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MviViewModelTest {
    private data class CounterState(
        val count: Int = 0,
    )

    private sealed interface CounterIntent {
        data object Increment : CounterIntent

        data object Announce : CounterIntent
    }

    private class CounterViewModel : MviViewModel<CounterState, CounterIntent, String>(CounterState()) {
        override fun onIntent(intent: CounterIntent) {
            when (intent) {
                CounterIntent.Increment -> setState { copy(count = count + 1) }
                CounterIntent.Announce -> sendEffect("count=${state.value.count}")
            }
        }
    }

    @Test
    fun intentsReduceState() {
        val viewModel = CounterViewModel()

        viewModel.onIntent(CounterIntent.Increment)
        viewModel.onIntent(CounterIntent.Increment)

        assertEquals(CounterState(count = 2), viewModel.state.value)
    }

    @Test
    fun effectsAreDeliveredOnce() =
        runTest {
            val viewModel = CounterViewModel()
            viewModel.onIntent(CounterIntent.Increment)

            viewModel.onIntent(CounterIntent.Announce)

            assertEquals("count=1", viewModel.effects.first())
        }
}
