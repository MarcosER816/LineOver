package dev.mark.lineover.core.engine

import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.OpParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RenderSchedulerTest {
    private val scopes = mutableListOf<CoroutineScope>()

    /** A scope on the test scheduler (advanceUntilIdle drives it), cancelled after the test. */
    private fun TestScope.scope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler)).also { scopes += it }

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun graph(stops: Float) = EditGraph().withBackgroundOps(listOf(OpParams.ExposureTest(stops)))

    @Test
    fun aBurstOfEditsRendersOnlyTheLatest() =
        runTest {
            val applied = mutableListOf<EditGraph>()
            val scheduler =
                RenderScheduler({
                    applied += it
                    null
                }, scope(), StandardTestDispatcher(testScheduler))
            // A slider sends 100 values before the render thread gets to run.
            for (i in 1..100) scheduler.submit(graph(i / 100f))
            advanceUntilIdle()
            assertEquals(listOf(graph(1f)), applied)
            assertEquals(graph(1f), scheduler.applied.value)
        }

    @Test
    fun eachSettledEditIsRendered() =
        runTest {
            val applied = mutableListOf<EditGraph>()
            val scheduler =
                RenderScheduler({
                    applied += it
                    null
                }, scope(), StandardTestDispatcher(testScheduler))
            scheduler.submit(graph(1f))
            advanceUntilIdle()
            scheduler.submit(graph(2f))
            advanceUntilIdle()
            assertEquals(listOf(graph(1f), graph(2f)), applied)
        }

    @Test
    fun rejectedGraphsAreReportedAndNotApplied() =
        runTest {
            val scheduler = RenderScheduler({ "bad graph" }, scope(), StandardTestDispatcher(testScheduler))
            scheduler.submit(graph(1f))
            advanceUntilIdle()
            assertEquals("bad graph", scheduler.error.value)
            assertNull(scheduler.applied.value)
        }

    @Test
    fun cancellingStopsRendering() =
        runTest {
            var calls = 0
            val scheduler =
                RenderScheduler({
                    calls++
                    null
                }, scope(), StandardTestDispatcher(testScheduler))
            scheduler.cancel()
            scheduler.submit(graph(1f))
            advanceUntilIdle()
            assertTrue(calls == 0)
        }
}
