package dev.mark.lineover.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EditHistoryTest {
    /** A random edit of [graph]: one op of one of two layers set, changed or removed. */
    private fun Random.edit(graph: EditGraph): EditGraph {
        val layerIds = listOf(EditGraph.BACKGROUND, "layer-2")
        val layers = layerIds.map { id -> graph.layers.firstOrNull { it.id == id } ?: Layer(id) }
        val target = nextInt(layers.size)
        val ops = layers[target].ops.toMutableList()
        val op =
            when (nextInt(6)) {
                0 -> OpParams.Exposure(nextInt(-50, 50) / 10f)
                1 -> OpParams.Contrast(nextInt(-100, 100).toFloat())
                2 -> OpParams.HighlightsShadows(nextInt(-100, 100).toFloat(), nextInt(-100, 100).toFloat())
                3 -> OpParams.WhiteBalance(nextInt(2000, 12000).toFloat(), nextInt(-50, 50).toFloat())
                4 -> OpParams.HslMixer(List(8) { nextInt(-30, 30).toFloat() }, List(8) { 0f }, List(8) { 0f })
                else -> OpParams.Invert
            }
        val existing = ops.indexOfFirst { it::class == op::class }
        when {
            existing >= 0 && nextInt(4) == 0 -> ops.removeAt(existing)
            existing >= 0 -> ops[existing] = op
            else -> ops += op
        }
        return EditGraph(layers = layers.mapIndexed { i, layer -> if (i == target) layer.copy(ops = ops) else layer })
    }

    @Test
    fun randomOpSequencesUndoToTheOriginalAndRedoToTheFinalGraph() {
        repeat(TRIALS) { trial ->
            val random = Random(trial)
            val original = random.edit(EditGraph())
            var history = EditHistory.start(original)
            var graph = original
            repeat(random.nextInt(1, MAX_STEPS)) {
                graph = random.edit(graph)
                val change = HistoryLabels.describe(history.current, graph)
                // Some steps are drags (open, coalescing), some taps; some drags end, some not.
                history = history.record(graph, change.label, change.key, open = random.nextBoolean())
                if (random.nextInt(3) == 0) history = history.close()
                assertEquals(graph, history.current)
            }
            val final = history.current
            while (history.canUndo) history = history.undo()
            assertEquals("trial $trial: undo all", original, history.current)
            while (history.canRedo) history = history.redo()
            assertEquals("trial $trial: redo all", final, history.current)
        }
    }

    @Test
    fun theHistoryKeepsTheLast500StepsAndUndoesToTheOldestKept() {
        var history = EditHistory.start()
        val graphs = (1..LONG).map { EditGraph().withBackgroundOps(listOf(OpParams.Contrast(it.toFloat()))) }
        graphs.forEach { history = history.record(it, "Contrast", "contrast.amount") }
        assertEquals(EditHistory.LIMIT, history.entries.size)
        while (history.canUndo) history = history.undo()
        assertEquals(graphs[LONG - EditHistory.LIMIT], history.current)
    }

    @Test
    fun aDragCoalescesIntoOneStepOnRelease() {
        var history = EditHistory.start()
        for (i in 1..30) {
            val graph = EditGraph().withBackgroundOps(listOf(OpParams.Exposure(i / 10f)))
            val change = HistoryLabels.describe(history.entries[history.entries.lastIndex - if (history.open) 1 else 0].graph, graph)
            history = history.record(graph, change.label, change.key, open = true)
        }
        history = history.close()
        assertEquals(2, history.entries.size)
        assertEquals("Exposure +3", history.entries.last().label)
        // The next drag of the same slider is a new step.
        history = history.record(EditGraph().withBackgroundOps(listOf(OpParams.Exposure(1f))), "Exposure +1", "exposure.ev", open = true)
        assertEquals(3, history.entries.size)
        // A different control ends the open step.
        history =
            history.record(
                EditGraph().withBackgroundOps(listOf(OpParams.Exposure(1f), OpParams.Contrast(5f))),
                "Contrast +5",
                "contrast.amount",
            )
        assertEquals(4, history.entries.size)
    }

    @Test
    fun draggingBackToTheStartRemovesTheStep() {
        val start = EditGraph().withBackgroundOps(listOf(OpParams.Exposure(1f)))
        var history = EditHistory.start(start)
        history = history.record(start.withBackgroundOps(listOf(OpParams.Exposure(2f))), "Exposure +2", "exposure.ev", open = true)
        history = history.record(start, "Exposure +1", "exposure.ev", open = true)
        assertEquals(1, history.entries.size)
        assertFalse(history.canUndo)
    }

    @Test
    fun aNewEditDropsTheRedoStepsAndRestoreKeepsThem() {
        var history = EditHistory.start()
        val a = EditGraph().withBackgroundOps(listOf(OpParams.Exposure(1f)))
        val b = a.withBackgroundOps(listOf(OpParams.Exposure(1f), OpParams.Contrast(10f)))
        history = history.record(a, "a", "x").record(b, "b", "y")
        val jumped = history.jumpTo(1)
        assertEquals(a, jumped.current)
        assertTrue(jumped.canRedo)
        val c = a.withBackgroundOps(listOf(OpParams.Exposure(2f)))
        val edited = jumped.record(c, "c", "x")
        assertFalse(edited.canRedo)
        assertEquals(listOf("Opened", "a", "c"), edited.entries.map { it.label })
    }

    @Test
    fun stepsShareUnchangedLayersAndOps() {
        val contrast = OpParams.Contrast(20f)
        val other = Layer("layer-2", listOf(OpParams.Invert))
        val start = EditGraph(layers = listOf(Layer(EditGraph.BACKGROUND, listOf(contrast, OpParams.Exposure(0.5f))), other))
        var history = EditHistory.start(start)
        // A rebuilt graph: equal values, new instances (as editOps() builds them).
        val next =
            EditGraph(
                layers =
                    listOf(
                        Layer(EditGraph.BACKGROUND, listOf(OpParams.Contrast(20f), OpParams.Exposure(0.7f))),
                        Layer("layer-2", listOf(OpParams.Invert)),
                    ),
            )
        history = history.record(next, "Exposure +0.7", "exposure.ev")
        val stored = history.current
        assertEquals(next, stored)
        assertSame("unchanged layer shared", other, stored.layers[1])
        assertSame("unchanged op shared", contrast, stored.layers[0].ops[0])
        assertNotSame(start.layers[0].ops[1], stored.layers[0].ops[1])
    }

    @Test
    fun documentsRoundTripThroughJson() {
        var history = EditHistory.start()
        history = history.record(EditGraph().withBackgroundOps(listOf(OpParams.Exposure(0.7f))), "Exposure +0.7", "exposure.ev")
        history = history.record(EditGraph().withBackgroundOps(listOf(OpParams.Exposure(0.7f), OpParams.Invert)), "Invert", "invert").undo()
        val document =
            ProjectDocument(
                versions =
                    listOf(
                        Version("v1", "Version 1", 1L, history),
                        Version(
                            "v2",
                            "Warm",
                            2L,
                            EditHistory.start(EditGraph().withBackgroundOps(listOf(OpParams.WhiteBalance(5000f, 3f)))),
                        ),
                    ),
                activeVersion = "v2",
                snapshots = listOf(Snapshot("s1", "Before crop", 3L, history.current)),
            )
        val decoded = EditGraphJson.decodeDocument(EditGraphJson.encodeDocument(document))
        assertEquals(document, decoded)
        assertEquals(1, decoded.versions[0].history.index)
    }

    private companion object {
        const val TRIALS = 300
        const val MAX_STEPS = 80
        const val LONG = 620
    }
}
