package dev.mark.lineover.feature.editor.canvas

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The canvas crop handles (Stage 9): 48 dp targets, aspect lock, move, corners, guide lines. */
class CropGesturesTest {
    // A 1000 x 800 view showing a 2000 x 1000 frame fitted: 0.5 view px per image px, top at y 150.
    private val transform =
        CanvasTransform().apply {
            setViewSize(1000, 800)
            setImageSize(2000, 1000)
        }

    private val rects = mutableListOf<Pair<FloatArray, Boolean>>()
    private val draggingEvents = mutableListOf<Boolean>()
    private var cornersOut: FloatArray? = null
    private var guide: FloatArray? = null
    private val gestures =
        CropGestures(transform, handleRadiusPx = 24f).apply {
            listener =
                object : CropGestureListener {
                    override fun onCropRect(
                        rect: FloatArray,
                        done: Boolean,
                    ) {
                        rects += rect to done
                    }

                    override fun onCropDragging(dragging: Boolean) {
                        draggingEvents += dragging
                    }

                    override fun onCorners(
                        corners: FloatArray,
                        done: Boolean,
                    ) {
                        cornersOut = corners
                    }

                    override fun onGuideLine(line: FloatArray) {
                        guide = line
                    }
                }
        }

    private fun view(
        u: Float,
        v: Float,
    ): FloatArray {
        val out = FloatArray(2)
        transform.imageToView(u * 2000f, v * 1000f, out)
        return out
    }

    @Test
    fun theTopLeftHandleResizesTheCrop() {
        gestures.frame = CropFrame(CropMode.Crop)
        val start = view(0f, 0f)
        assertTrue(gestures.begin(start[0] + 5f, start[1] + 5f))
        val to = view(0.25f, 0.2f)
        gestures.move(to[0] + 5f, to[1] + 5f)
        gestures.end(to[0] + 5f, to[1] + 5f, lifted = true)
        val (rect, done) = rects.last()
        assertTrue(done)
        assertArrayEquals(floatArrayOf(0.25f, 0.2f, 1f, 1f), rect, 1e-3f)
        assertEquals(listOf(true, false), draggingEvents)
    }

    @Test
    fun aLockedRatioIsKept() {
        gestures.frame = CropFrame(CropMode.Crop, ratio = 4f / 5f, rect = floatArrayOf(0.3f, 0f, 0.7f, 1f))
        val handle = view(0.7f, 1f)
        assertTrue(gestures.begin(handle[0], handle[1]))
        val to = view(0.6f, 0.8f)
        gestures.end(to[0], to[1], lifted = true)
        val r = rects.last().first
        val ratio = (r[2] - r[0]) * 2000f / ((r[3] - r[1]) * 1000f)
        assertEquals(0.8f, ratio, 1e-3f)
        // Anchored at the opposite (top-left) corner.
        assertEquals(0.3f, r[0], 1e-4f)
        assertEquals(0f, r[1], 1e-4f)
    }

    @Test
    fun aDragInsideMovesTheCropWithinTheFrame() {
        gestures.frame = CropFrame(CropMode.Crop, rect = floatArrayOf(0.2f, 0.2f, 0.6f, 0.6f))
        val from = view(0.4f, 0.4f)
        assertTrue(gestures.begin(from[0], from[1]))
        val to = view(0.9f, 0.4f)
        gestures.end(to[0], to[1], lifted = true)
        assertArrayEquals(floatArrayOf(0.6f, 0.2f, 1f, 0.6f), rects.last().first, 1e-3f)
    }

    @Test
    fun touchesOutsideTheHandlesAreLeftToTheCanvas() {
        gestures.frame = CropFrame(CropMode.Crop, rect = floatArrayOf(0.4f, 0.4f, 0.6f, 0.6f))
        val far = view(0.05f, 0.9f)
        assertFalse(gestures.begin(far[0], far[1]))
        gestures.frame = null
        assertFalse(gestures.begin(500f, 400f))
    }

    @Test
    fun perspectiveCornersMoveOneAtATime() {
        gestures.frame = CropFrame(CropMode.Corners)
        val corner = view(1f, 1f)
        assertTrue(gestures.begin(corner[0], corner[1]))
        val to = view(0.9f, 0.8f)
        gestures.end(to[0], to[1], lifted = true)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0.9f, 0.8f, 0f, 1f), assertNotNullArray(cornersOut), 1e-3f)
    }

    @Test
    fun aGuideLineIsReportedOnLift() {
        gestures.frame = CropFrame(CropMode.Guides)
        val a = view(0.2f, 0.1f)
        val b = view(0.25f, 0.9f)
        assertTrue(gestures.begin(a[0], a[1]))
        gestures.move(b[0], b[1])
        gestures.end(b[0], b[1], lifted = true)
        assertArrayEquals(floatArrayOf(0.2f, 0.1f, 0.25f, 0.9f), assertNotNullArray(guide), 1e-3f)
    }

    private fun assertNotNullArray(a: FloatArray?): FloatArray {
        assertNotNull(a)
        return a!!
    }
}
