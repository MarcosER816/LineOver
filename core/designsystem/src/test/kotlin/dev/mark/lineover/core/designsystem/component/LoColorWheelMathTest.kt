package dev.mark.lineover.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

class LoColorWheelMathTest {
    private val c = 100f
    private val r = 100f

    @Test
    fun anglesFollowTheScreenClockwiseFromRight() {
        assertEquals(LoHueSat(0f, 1f), LoColorWheelMath.hueSatAt(200f, 100f, c, c, r))
        assertEquals(LoHueSat(90f, 1f), LoColorWheelMath.hueSatAt(100f, 200f, c, c, r)) // straight down
        assertEquals(LoHueSat(180f, 0.5f), LoColorWheelMath.hueSatAt(50f, 100f, c, c, r))
        assertEquals(270f, LoColorWheelMath.hueSatAt(100f, 0f, c, c, r).hue, 1e-4f)
    }

    @Test
    fun saturationClampsAndCentreHasNoHue() {
        assertEquals(1f, LoColorWheelMath.hueSatAt(500f, 100f, c, c, r).saturation, 0f)
        assertEquals(LoHueSat(0f, 0f), LoColorWheelMath.hueSatAt(100f, 100f, c, c, r))
    }

    @Test
    fun positionOfIsTheInverse() {
        val value = LoHueSat(135f, 0.4f)
        val p = LoColorWheelMath.positionOf(value, c, c, r)
        val back = LoColorWheelMath.hueSatAt(p.x, p.y, c, c, r)
        assertEquals(value.hue, back.hue, 1e-3f)
        assertEquals(value.saturation, back.saturation, 1e-5f)
    }
}
