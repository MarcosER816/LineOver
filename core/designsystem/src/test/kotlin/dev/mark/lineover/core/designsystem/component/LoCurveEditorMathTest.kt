package dev.mark.lineover.core.designsystem.component

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoCurveEditorMathTest {
    private val points = listOf(Offset(0f, 0f), Offset(0.5f, 0.6f), Offset(1f, 1f))

    @Test
    fun hitFindsTheNearestPointWithinTheRadius() {
        assertEquals(1, LoCurveEditorMath.hit(points, 0.52f, 0.58f, 0.05f))
        assertEquals(-1, LoCurveEditorMath.hit(points, 0.3f, 0.1f, 0.05f))
    }

    @Test
    fun aPointIsDeletedOnlyWellOutsideTheGraph() {
        assertFalse(LoCurveEditorMath.isOffGraph(1.05f, 0.5f))
        assertTrue(LoCurveEditorMath.isOffGraph(1.2f, 0.5f))
        assertTrue(LoCurveEditorMath.isOffGraph(0.5f, -0.2f))
    }

    @Test
    fun readoutUsesTheEightBitScale() {
        assertEquals("In 128 · Out 153", LoCurveEditorMath.readout(Offset(0.5f, 0.6f)))
    }
}

class LoGradeWheelMathTest {
    @Test
    fun ringMapsLuminanceAlongTheArc() {
        assertEquals(270f, LoGradeWheelMath.angleOf(0f), 1e-4f) // top
        assertEquals(135f, LoGradeWheelMath.angleOf(-1f), 1e-4f)
        assertEquals(0f, LoGradeWheelMath.luminanceAt(0f, -1f), 1e-4f) // straight up = 0
        assertEquals(1f, LoGradeWheelMath.luminanceAt(1f, 1f), 1e-4f) // bottom right end
        assertEquals(-1f, LoGradeWheelMath.luminanceAt(-1f, 1f), 1e-4f) // bottom left end
        assertEquals(1f, LoGradeWheelMath.luminanceAt(0.05f, 1f), 1e-4f) // in the gap: nearer end
    }
}
