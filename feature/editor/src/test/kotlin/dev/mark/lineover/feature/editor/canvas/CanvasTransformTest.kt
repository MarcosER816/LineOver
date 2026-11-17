package dev.mark.lineover.feature.editor.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasTransformTest {
    private val eps = 1e-2f

    /** A 50 MP photo (8160 × 6120) on a 1440 × 2400 canvas. */
    private fun transform() =
        CanvasTransform().apply {
            setImageSize(8160, 6120)
            setViewSize(1440, 2400)
        }

    private fun CanvasTransform.mapToView(
        x: Float,
        y: Float,
    ) = FloatArray(2).also { imageToView(x, y, it) }

    private fun CanvasTransform.mapToImage(
        x: Float,
        y: Float,
    ) = FloatArray(2).also { viewToImage(x, y, it) }

    @Test
    fun fitCentersAndFitsTheLongSide() {
        val t = transform()
        assertEquals(1440f / 8160f, t.scale, 1e-6f)
        val center = t.mapToView(4080f, 3060f)
        assertEquals(720f, center[0], eps)
        assertEquals(1200f, center[1], eps)
        assertEquals(0f, t.mapToView(0f, 0f)[0], eps)
        assertTrue(t.isFitted)
    }

    @Test
    fun pinchKeepsTheFocalPointUnderTheFingers() {
        val t = transform()
        val focus = floatArrayOf(300f, 900f)
        val before = t.mapToImage(focus[0], focus[1])
        t.beginGesture()
        t.applyGesture(focus[0], focus[1], 0f, 0f, scaleFactor = 3f, rotationDelta = 0f)
        val after = t.mapToView(before[0], before[1])
        assertEquals(focus[0], after[0], eps)
        assertEquals(focus[1], after[1], eps)
        assertEquals(3f * 1440f / 8160f, t.scale, 1e-5f)
        assertFalse(t.isFitted)
    }

    @Test
    fun zoomIsClampedTo0_1And32() {
        val t = transform()
        t.beginGesture()
        repeat(40) { t.applyGesture(720f, 1200f, 0f, 0f, 2f, 0f) }
        assertEquals(32f, t.scale, 0f)
        repeat(80) { t.applyGesture(720f, 1200f, 0f, 0f, 0.5f, 0f) }
        assertEquals(0.1f, t.scale, 0f)
    }

    @Test
    fun aHugePanoramaMayZoomOutToFitBelow0_1() {
        val t =
            CanvasTransform().apply {
                setImageSize(40000, 4000)
                setViewSize(1440, 2400)
            }
        assertEquals(1440f / 40000f, t.minScale, 1e-6f)
        assertEquals(t.fitScale(), t.scale, 1e-6f)
    }

    @Test
    fun panMovesTheImageByTheFingerDistance() {
        val t = transform()
        t.beginGesture()
        t.applyGesture(720f, 1200f, 100f, -50f, 1f, 0f)
        val center = t.mapToView(4080f, 3060f)
        assertEquals(820f, center[0], eps)
        assertEquals(1150f, center[1], eps)
    }

    @Test
    fun panIsLimitedSoThePhotoCannotBeLost() {
        val t = transform()
        t.beginGesture()
        t.applyGesture(720f, 1200f, 5000f, 9000f, 1f, 0f)
        // The top-left corner of the photo stops at the middle of the view.
        val corner = t.mapToView(0f, 0f)
        assertEquals(720f, corner[0], eps)
        assertEquals(1200f, corner[1], eps)
    }

    @Test
    fun rotationAroundTheFocalPointKeepsItFixed() {
        val t = transform()
        val before = t.mapToImage(400f, 700f)
        t.beginGesture()
        t.applyGesture(400f, 700f, 0f, 0f, 1f, 30f)
        assertEquals(30f, t.rotation, 1e-4f)
        val after = t.mapToView(before[0], before[1])
        assertEquals(400f, after[0], eps)
        assertEquals(700f, after[1], eps)
    }

    @Test
    fun rotationSnapsToRightAnglesAndReportsTheDetentOnce() {
        val t = transform()
        t.beginGesture()
        assertFalse(t.applyGesture(720f, 1200f, 0f, 0f, 1f, 80f))
        assertEquals(80f, t.rotation, 1e-4f)
        // 86° is within 5° of 90°: shown as 90, haptic once.
        assertTrue(t.applyGesture(720f, 1200f, 0f, 0f, 1f, 6f))
        assertEquals(90f, t.rotation, 1e-4f)
        // Still inside the detent (92°): no second tick.
        assertFalse(t.applyGesture(720f, 1200f, 0f, 0f, 1f, 6f))
        assertEquals(90f, t.rotation, 1e-4f)
        // Leaving it (98°) follows the fingers again.
        t.applyGesture(720f, 1200f, 0f, 0f, 1f, 6f)
        assertEquals(98f, t.rotation, 1e-4f)
    }

    @Test
    fun snapWorksAt0_180_270AcrossTheWrap() {
        assertEquals(0f, CanvasTransform.snapRotation(357f), 0f)
        assertEquals(0f, CanvasTransform.snapRotation(3f), 0f)
        assertEquals(180f, CanvasTransform.snapRotation(184f), 0f)
        assertEquals(270f, CanvasTransform.snapRotation(266f), 0f)
        assertEquals(45f, CanvasTransform.snapRotation(45f), 0f)
        assertEquals(350f, CanvasTransform.normalize(-10f), 0f)
    }

    @Test
    fun fitOfARotatedPhotoUsesItsRotatedBounds() {
        val t = transform()
        t.beginGesture()
        t.applyGesture(720f, 1200f, 0f, 0f, 1f, 90f)
        t.fit()
        // Rotated 90°, the 6120 side runs across the 1440 view.
        assertEquals(1440f / 6120f, t.scale, 1e-6f)
        assertEquals(90f, t.rotation, 0f)
    }

    @Test
    fun doubleTapGoesFrom_fit_to100PercentAroundTheTapAndBack() {
        val t = transform()
        val out = FloatArray(3)
        assertFalse(t.doubleTapTarget(200f, 900f, out))
        assertEquals(1f, out[0], 0f)
        val tapped = t.mapToImage(200f, 900f)
        t.set(out[0], t.rotation, out[1], out[2], fitted = false)
        val after = t.mapToView(tapped[0], tapped[1])
        assertEquals(200f, after[0], eps)
        assertEquals(900f, after[1], eps)

        assertTrue(t.doubleTapTarget(200f, 900f, out))
        assertEquals(t.fitScale(), out[0], 1e-6f)
        assertEquals(720f, out[1], 0f)
        assertEquals(1200f, out[2], 0f)
    }

    @Test
    fun resizingAFittedPhotoRefitsIt() {
        val t = transform()
        t.setViewSize(2400, 1440)
        assertEquals(1440f / 6120f, t.scale, 1e-6f)
        assertEquals(1200f, t.centerX, 0f)
    }

    @Test
    fun viewAndImageMappingsAreInverse() {
        val t = transform()
        t.beginGesture()
        t.applyGesture(500f, 600f, 37f, -12f, 2.7f, 33f)
        val v = t.mapToView(1234f, 4321f)
        val back = t.mapToImage(v[0], v[1])
        assertEquals(1234f, back[0], 0.05f)
        assertEquals(4321f, back[1], 0.05f)
    }

    @Test
    fun glMatrixMapsTheQuadCornersToTheirScreenPositions() {
        val t = transform()
        t.beginGesture()
        t.applyGesture(500f, 600f, 37f, -12f, 2.7f, 33f)
        val m = FloatArray(9)
        t.writeGlMatrix(m)
        for ((u, v) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f, 0.25f to 0.75f)) {
            val ndcX = m[0] * u + m[3] * v + m[6]
            val ndcY = m[1] * u + m[4] * v + m[7]
            val view = t.mapToView(u * 8160f, v * 6120f)
            assertEquals((view[0] / 1440f) * 2f - 1f, ndcX, 1e-4f)
            assertEquals(1f - (view[1] / 2400f) * 2f, ndcY, 1e-4f)
        }
    }
}

class TwoFingerTrackerTest {
    @Test
    fun spreadingTheFingersZoomsAroundTheirMidpoint() {
        val tracker = TwoFingerTracker()
        tracker.start(100f, 100f, 300f, 100f)
        tracker.move(50f, 100f, 350f, 100f)
        assertEquals(1.5f, tracker.scaleFactor, 1e-5f)
        assertEquals(200f, tracker.focusX, 0f)
        assertEquals(0f, tracker.panX, 0f)
        assertEquals(0f, tracker.rotationDelta, 0f)
    }

    @Test
    fun movingBothFingersPans() {
        val tracker = TwoFingerTracker()
        tracker.start(100f, 100f, 300f, 100f)
        tracker.move(130f, 80f, 330f, 80f)
        assertEquals(30f, tracker.panX, 1e-5f)
        assertEquals(-20f, tracker.panY, 1e-5f)
        assertEquals(1f, tracker.scaleFactor, 1e-5f)
    }

    @Test
    fun rotationStartsOnlyAfterTheSlop() {
        val tracker = TwoFingerTracker()
        tracker.start(0f, 0f, 100f, 0f)
        // Turn 5°: below the 10° slop, no rotation yet.
        tracker.move(0f, 0f, 99.62f, 8.72f)
        assertEquals(0f, tracker.rotationDelta, 0f)
        assertFalse(tracker.rotating)
        // Turn to 12°: rotating now, but the slop itself is not applied (no jump).
        tracker.move(0f, 0f, 97.81f, 20.79f)
        assertTrue(tracker.rotating)
        assertEquals(0f, tracker.rotationDelta, 0f)
        // Further turns rotate 1:1.
        tracker.move(0f, 0f, 96.59f, 25.88f)
        assertEquals(3f, tracker.rotationDelta, 0.05f)
    }
}

class FlingDecayTest {
    @Test
    fun theDistanceDoesNotDependOnTheFrameRate() {
        fun run(frameSeconds: Float): Float {
            val fling = FlingDecay().apply { start(3000f, 0f) }
            val out = FloatArray(2)
            var x = 0f
            repeat((2f / frameSeconds).toInt()) {
                fling.step(frameSeconds, out)
                x += out[0]
            }
            return x
        }
        val at120 = run(1f / 120f)
        val at60 = run(1f / 60f)
        assertEquals(at120, at60, 0.5f)
        // After 2 s the fling has covered almost all of v / friction.
        assertEquals(FlingDecay.totalDistance(3000f), at120, 3000f / FlingDecay.FRICTION * 0.001f + 1f)
    }

    @Test
    fun itStopsBelowTheStopSpeed() {
        val fling = FlingDecay().apply { start(100f, 0f) }
        val out = FloatArray(2)
        assertTrue(fling.isRunning)
        repeat(120) { fling.step(1f / 120f, out) }
        assertFalse(fling.isRunning)
    }
}
