package dev.mark.lineover.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Local masks (Stage 17): the component JSON shared with graph.cpp, raster names and History labels. */
class LocalMasksTest {
    private fun fixture(name: String): String {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return File(requireNotNull(dir), "testdata/graph/$name").readText().trimEnd()
    }

    private val graph =
        EditGraph(
            layers =
                listOf(
                    Layer("background"),
                    Layer(
                        "m1",
                        ops = listOf(OpParams.Exposure(-0.5f)),
                        type = LayerType.Adjustment,
                        name = "Sky",
                        opacity = 80f,
                        mask =
                            LayerMask(
                                components =
                                    listOf(
                                        MaskComponent.Linear(0f, 0f, 0f, 1800f),
                                        MaskComponent.Raster(
                                            "sky-1",
                                            512f,
                                            384f,
                                            4000f,
                                            3000f,
                                            "sky-1.guide",
                                            AiMaskKind.Sky,
                                            CombineMode.Intersect,
                                        ),
                                    ),
                            ),
                    ),
                    Layer(
                        "m2",
                        ops = listOf(OpParams.Clarity(30f)),
                        type = LayerType.Adjustment,
                        name = "Subject",
                        mask =
                            LayerMask(
                                feather = 4f,
                                components =
                                    listOf(
                                        MaskComponent.Radial(2000f, 1500f, 900f, 600f, 15f, 50f, invert = true),
                                        MaskComponent.Brush(
                                            listOf(
                                                MaskPaint.Stroke(
                                                    1f,
                                                    80f,
                                                    0.5f,
                                                    100f,
                                                    listOf(10f, 20f, 30.5f, 40.25f),
                                                    listOf(62.5f, 1.25f, -3f),
                                                ),
                                                MaskPaint.Stroke(0f, 40f, 1f, 50f, listOf(5f, 5f)),
                                            ),
                                            autoMask = true,
                                        ),
                                        MaskComponent.ColorRange(listOf(50f, 10f, -5f), 20f, CombineMode.Subtract),
                                        MaskComponent.LuminanceRange(0.25f, 0.75f, 0.1f),
                                        MaskComponent.DepthRange(
                                            "depth-1",
                                            518f,
                                            388f,
                                            4000f,
                                            3000f,
                                            lo = 0.6f,
                                            hi = 1f,
                                            smooth = 0.15f,
                                            mode = CombineMode.Intersect,
                                        ),
                                    ),
                            ),
                    ),
                ),
        )

    @Test
    fun componentsRoundTripWithTheEngine() {
        val text = fixture("edit_graph_local_masks.json")
        assertEquals(text, EditGraphJson.encode(graph))
        assertEquals(graph, EditGraphJson.decode(text))
    }

    @Test
    fun rasterNamesIncludeModelAndDepthMasks() {
        assertEquals(setOf("sky-1", "sky-1.guide"), graph.layers[1].mask!!.rasterNames)
        assertEquals(setOf("depth-1"), graph.layers[2].mask!!.rasterNames)
        assertTrue(graph.layers[1].mask!!.isLocal)
        assertFalse(LayerMask().isLocal)
    }

    @Test
    fun historyNamesComponentChanges() {
        val before = graph
        val linear = MaskComponent.Linear(0f, 0f, 0f, 900f)

        fun with(components: List<MaskComponent>) =
            LayerMath.update(before, "m1") { it.copy(mask = it.mask!!.copy(components = components)) }
        val moved = with(listOf(linear, before.layers[1].mask!!.components[1]))
        val movedMore = with(listOf(linear.copy(y1 = 700f), before.layers[1].mask!!.components[1]))
        assertEquals("Sky: Edit Linear gradient", HistoryLabels.describe(before, moved).label)
        // A pin dragged is one step: the same key while it moves.
        assertEquals(HistoryLabels.describe(before, moved).key, HistoryLabels.describe(before, movedMore).key)
        val added = with(before.layers[1].mask!!.components + MaskComponent.LuminanceRange(0f, 0.5f, 0.1f))
        assertEquals("Sky: Add Luminance range", HistoryLabels.describe(before, added).label)
        val inverted =
            with(
                listOf(
                    before.layers[1]
                        .mask!!
                        .components[0]
                        .with(invert = true),
                    before.layers[1].mask!!.components[1],
                ),
            )
        assertEquals("Sky: Invert Linear gradient", HistoryLabels.describe(before, inverted).label)
    }
}
