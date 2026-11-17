package dev.mark.lineover.core.designsystem.component

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoToastStateTest {
    @Test
    fun actionCompletesTheToast() =
        runTest {
            val state = LoToastState()
            val result = async { state.show("Preset applied", "Undo") }
            runCurrent()
            assertEquals(LoToastData("Preset applied", "Undo"), state.current)

            state.performAction()

            assertEquals(LoToastResult.ActionPerformed, result.await())
            assertNull(state.current)
        }

    @Test
    fun toastTimesOut() =
        runTest {
            val state = LoToastState()
            val result = async { state.show("Saved", durationMillis = 1000) }
            runCurrent()
            advanceTimeBy(1001)

            assertEquals(LoToastResult.Dismissed, result.await())
            assertNull(state.current)
        }

    @Test
    fun newToastReplacesTheCurrentOne() =
        runTest {
            val state = LoToastState()
            val first = async { state.show("First") }
            runCurrent()
            val second = async { state.show("Second") }
            runCurrent()

            assertEquals(LoToastResult.Dismissed, first.await())
            assertEquals("Second", state.current?.message)
            state.dismiss()
            assertEquals(LoToastResult.Dismissed, second.await())
        }
}
