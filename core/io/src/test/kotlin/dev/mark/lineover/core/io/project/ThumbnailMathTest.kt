package dev.mark.lineover.core.io.project

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailMathTest {
    @Test
    fun longestSideBecomes512KeepingAspect() {
        assertEquals(512 to 384, ThumbnailMath.targetSize(4000, 3000))
        assertEquals(288 to 512, ThumbnailMath.targetSize(2250, 4000))
        assertEquals(512 to 1, ThumbnailMath.targetSize(100000, 10))
    }

    @Test
    fun smallImagesAreNotUpscaled() {
        assertEquals(300 to 200, ThumbnailMath.targetSize(300, 200))
        assertEquals(512 to 512, ThumbnailMath.targetSize(512, 512))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyImagesAreRejected() {
        ThumbnailMath.targetSize(0, 10)
    }
}
