package dev.mark.lineover.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryLabelsTest {
    private fun graph(vararg ops: OpParams) = EditGraph().withBackgroundOps(ops.toList())

    private fun label(
        before: EditGraph,
        after: EditGraph,
    ) = HistoryLabels.describe(before, after).label

    @Test
    fun namesTheControlAndItsValue() {
        assertEquals("Exposure +0.7", label(graph(), graph(OpParams.Exposure(0.7f))))
        assertEquals("Exposure -1.5", label(graph(OpParams.Exposure(0.7f)), graph(OpParams.Exposure(-1.5f))))
        assertEquals("Exposure 0", label(graph(OpParams.Exposure(0.7f)), graph()))
        assertEquals("Contrast +25", label(graph(), graph(OpParams.Contrast(25f))))
        assertEquals("Highlights -40", label(graph(), graph(OpParams.HighlightsShadows(-40f, 0f))))
        assertEquals("Temperature 5600 K", label(graph(), graph(OpParams.WhiteBalance(5600f, 0f))))
        assertEquals("Tint +12", label(graph(OpParams.WhiteBalance(5600f, 0f)), graph(OpParams.WhiteBalance(5600f, 12f))))
        assertEquals("Invert", label(graph(), graph(OpParams.Invert)))
        assertEquals("Invert off", label(graph(OpParams.Invert), graph()))
        assertEquals("Posterize 12", label(graph(), graph(OpParams.Posterize(12f))))
        assertEquals(
            "Curves",
            label(
                graph(),
                graph(OpParams.Curves(0f, listOf(0f, 0f, 0.5f, 0.6f, 1f, 1f), emptyList(), emptyList(), emptyList(), emptyList())),
            ),
        )
        assertEquals("Exposure +1 + 1 more", label(graph(), graph(OpParams.Exposure(1f), OpParams.Contrast(10f))))
    }

    @Test
    fun theSameControlHasTheSameKey() {
        val a = HistoryLabels.describe(graph(), graph(OpParams.Exposure(0.2f))).key
        val b = HistoryLabels.describe(graph(), graph(OpParams.Exposure(1.4f))).key
        val c = HistoryLabels.describe(graph(), graph(OpParams.Contrast(3f))).key
        assertEquals(a, b)
        assertEquals("exposure.ev", a)
        assertEquals("contrast.amount", c)
    }

    @Test
    fun geometryStepsAreNamedByTheirControl() {
        fun op(g: GeometryAdjustments) = g.toOp(4000, 3000)!!
        assertEquals("Straighten +2.5", label(graph(), graph(op(GeometryAdjustments(straighten = 2.5f)))))
        assertEquals("Rotate 90°", label(graph(), graph(op(GeometryAdjustments(quarterTurns = 1)))))
        assertEquals("Crop", label(graph(), graph(op(GeometryAdjustments(cropLeft = 0.1f, cropTop = 0.2f)))))
        assertEquals("Upright Auto", label(graph(), graph(op(GeometryAdjustments(upright = UprightMode.Auto, uprightPitch = 4f)))))
        assertEquals("Straighten 0", label(graph(op(GeometryAdjustments(straighten = 2.5f))), graph()))
        // A straighten drag keeps one key although the composed matrix and size change every frame.
        val a = HistoryLabels.describe(graph(), graph(op(GeometryAdjustments(straighten = 1f)))).key
        val b = HistoryLabels.describe(graph(), graph(op(GeometryAdjustments(straighten = 12f)))).key
        assertEquals(a, b)
    }
}
