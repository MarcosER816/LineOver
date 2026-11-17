package dev.mark.lineover.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoSliderModelTest {
    private val centred = LoSliderModel(min = -100f, max = 100f, step = 1f, default = 0f)
    private val exposure = LoSliderModel(min = -5f, max = 5f, step = 0.01f, default = 0f)

    // A 1000 px track over a 200-wide range: 5 px per unit. Row 100 px tall.
    private val track = 1000f
    private val row = 100f

    @Test
    fun coerceClampsAndRoundsToStep() {
        assertEquals(42f, centred.coerce(41.6f), 0f)
        assertEquals(-42f, centred.coerce(-41.6f), 0f)
        assertEquals(100f, centred.coerce(250f), 0f)
        assertEquals(-100f, centred.coerce(-250f), 0f)
        assertEquals(1.23f, exposure.coerce(1.2349f), 0f)
    }

    @Test
    fun stepArithmeticHasNoFloatNoise() {
        val tenths = LoSliderModel(min = 0f, max = 1f, step = 0.1f, default = 0f)
        assertEquals(0.3f, tenths.coerce(0.3000001f), 0f)
        assertEquals("0.3", tenths.coerce(0.29f).toString())
    }

    @Test
    fun rangeNotMultipleOfStepNeverExceedsMax() {
        val odd = LoSliderModel(min = 0f, max = 10f, step = 3f, default = 0f)
        assertEquals(9f, odd.coerce(9.4f), 0f)
        assertEquals(10f, odd.coerce(10f), 0f)
    }

    @Test
    fun continuousSliderDoesNotQuantise() {
        val continuous = LoSliderModel(min = 0f, max = 1f, step = 0f, default = 0.5f)
        assertEquals(0.123456f, continuous.coerce(0.123456f), 0f)
        assertEquals(0.01f, continuous.tapStep, 1e-7f)
    }

    @Test
    fun fractionAndValueAtAreInverse() {
        assertEquals(0.5f, centred.fraction(0f), 0f)
        assertEquals(0f, centred.fraction(-100f), 0f)
        assertEquals(1f, centred.fraction(100f), 0f)
        assertEquals(50f, centred.valueAt(0.75f), 0f)
    }

    @Test
    fun tapsStepByOneStepAndClamp() {
        assertEquals(11f, centred.stepBy(10f, +1), 0f)
        assertEquals(9f, centred.stepBy(10f, -1), 0f)
        assertEquals(100f, centred.stepBy(100f, +1), 0f)
        assertEquals(-100f, centred.stepBy(-100f, -5), 0f)
        assertEquals(10f, centred.stepBy(10f, 0), 0f)
        assertEquals(0.01f, exposure.stepBy(0f, +1), 0f)
    }

    @Test
    fun resetReturnsDefault() {
        assertEquals(0f, centred.reset(), 0f)
        assertEquals(180f, LoSliderModel(0f, 360f, 1f, 180f).reset(), 0f)
    }

    @Test
    fun dragIsRelativeAndProportionalToTrackWidth() {
        val drag = centred.startDrag(10f)
        drag.dragBy(dxPx = 100f, trackWidthPx = track, verticalOffsetPx = 0f, rowHeightPx = row)
        assertEquals(30f, drag.value, 0f) // 100 px = 20 units
        drag.dragBy(dxPx = -250f, trackWidthPx = track, verticalOffsetPx = 0f, rowHeightPx = row)
        assertEquals(-20f, drag.value, 0f)
    }

    @Test
    fun subStepMovementsAccumulate() {
        val drag = centred.startDrag(10f)
        repeat(5) { drag.dragBy(dxPx = 1f, trackWidthPx = track, verticalOffsetPx = 0f, rowHeightPx = row) }
        // Each 1 px move is 0.2 units, below one step, but five of them make one step.
        assertEquals(11f, drag.value, 0f)
    }

    @Test
    fun fineModeStartsTwoRowsBelowAndMovesAtQuarterSpeed() {
        assertEquals(1f, centred.speedFactor(verticalOffsetPx = 199f, rowHeightPx = row), 0f)
        assertEquals(0.25f, centred.speedFactor(verticalOffsetPx = 200f, rowHeightPx = row), 0f)
        assertEquals(1f, centred.speedFactor(verticalOffsetPx = -500f, rowHeightPx = row), 0f)

        val normal = centred.startDrag(10f)
        val fine = centred.startDrag(10f)
        normal.dragBy(dxPx = 100f, trackWidthPx = track, verticalOffsetPx = 0f, rowHeightPx = row)
        fine.dragBy(dxPx = 100f, trackWidthPx = track, verticalOffsetPx = 250f, rowHeightPx = row)
        assertEquals(30f, normal.value, 0f)
        assertEquals(15f, fine.value, 0f)
    }

    @Test
    fun dragClampsAtTheEnds() {
        val drag = centred.startDrag(90f)
        drag.dragBy(dxPx = 500f, trackWidthPx = track, verticalOffsetPx = 0f, rowHeightPx = row)
        assertEquals(100f, drag.value, 0f)
        // Clamped movement is not stored: coming back responds immediately.
        drag.dragBy(dxPx = -5f, trackWidthPx = track, verticalOffsetPx = 0f, rowHeightPx = row)
        assertEquals(99f, drag.value, 0f)
    }

    @Test
    fun detentSnapsNearDefaultAndReportsReachingIt() {
        val drag = centred.startDrag(10f)
        assertFalse(drag.dragBy(dxPx = -20f, trackWidthPx = track, verticalOffsetPx = 0f, rowHeightPx = row)) // 6
        // 6 -> 1.6: within 2 units (1 % of 200) of the default, so it sticks at 0.
        assertTrue(drag.dragBy(dxPx = -22f, trackWidthPx = track, verticalOffsetPx = 0f, rowHeightPx = row))
        assertEquals(0f, drag.value, 0f)
        // Still inside the sticky zone: no second tick.
        assertFalse(drag.dragBy(dxPx = -5f, trackWidthPx = track, verticalOffsetPx = 0f, rowHeightPx = row))
        assertEquals(0f, drag.value, 0f)
    }

    @Test
    fun fastDragAcrossDefaultStillTicks() {
        val drag = centred.startDrag(30f)
        assertTrue(drag.dragBy(dxPx = -300f, trackWidthPx = track, verticalOffsetPx = 0f, rowHeightPx = row))
        assertEquals(-30f, drag.value, 0f)
    }

    @Test
    fun zeroWidthTrackIsIgnored() {
        val drag = centred.startDrag(10f)
        assertFalse(drag.dragBy(dxPx = 100f, trackWidthPx = 0f, verticalOffsetPx = 0f, rowHeightPx = row))
        assertEquals(10f, drag.value, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun defaultOutsideRangeIsRejected() {
        LoSliderModel(min = 0f, max = 1f, step = 0.1f, default = 2f)
    }
}
