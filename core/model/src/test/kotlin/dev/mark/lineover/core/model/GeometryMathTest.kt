package dev.mark.lineover.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

class GeometryMathTest {
    private val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        requireNotNull(dir)
    }

    /**
     * The DLT matches OpenCV's getPerspectiveTransform on the 24 fixture quads (tools/golden):
     * each element within 1e-5 relative (both solve the same 8 x 8 system in double; pivoting
     * differs) and every corner mapped within 1e-6 relative.
     */
    @Test
    fun homographiesMatchOpenCvGetPerspectiveTransform() {
        val cases =
            Json
                .parseToJsonElement(File(root, "testdata/golden/geometry/homographies.json").readText())
                .jsonObject["cases"]!!
                .jsonArray
        assertTrue(cases.size >= 20)
        for (case in cases) {
            val o = case.jsonObject

            fun values(key: String) = o[key]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
            val h = requireNotNull(Homography.fromQuads(values("src"), values("dst")))
            val expected = values("matrix")
            for (i in 0 until 9) {
                val tolerance = 1e-5 * maxOf(1.0, abs(expected[i]))
                assertEquals("element $i", expected[i], h.m[i], tolerance)
            }
            // And it maps every source corner onto its destination.
            val src = values("src")
            val dst = values("dst")
            for (k in 0 until 4) {
                val sx = src[2 * k]
                val sy = src[2 * k + 1]
                val w = h.m[6] * sx + h.m[7] * sy + h.m[8]
                val x = (h.m[0] * sx + h.m[1] * sy + h.m[2]) / w
                val y = (h.m[3] * sx + h.m[4] * sy + h.m[5]) / w
                assertEquals(dst[2 * k], x, 1e-6 * maxOf(1.0, abs(dst[2 * k])))
                assertEquals(dst[2 * k + 1], y, 1e-6 * maxOf(1.0, abs(dst[2 * k + 1])))
            }
        }
    }

    @Test
    fun collinearPointsHaveNoHomography() {
        val line = doubleArrayOf(0.0, 0.0, 1.0, 1.0, 2.0, 2.0, 3.0, 3.0)
        assertNull(Homography.fromQuads(line, doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)))
    }

    @Test
    fun noChangeIsNoOp() {
        assertNull(GeometryAdjustments().toOp(4000, 3000))
        val composed = GeometryMath.compose(GeometryAdjustments(), 4000, 3000)
        assertEquals(listOf(4000.0, 0.0, 0.0, 0.0, 3000.0, 0.0, 0.0, 0.0, 1.0), composed.matrix.toList().map { round6(it) })
        assertEquals(4000, composed.outputWidth)
        assertEquals(3000, composed.outputHeight)
    }

    @Test
    fun aQuarterTurnSwapsTheSizeAndMapsTheCorners() {
        val g = GeometryAdjustments(quarterTurns = 1)
        val c = GeometryMath.compose(g, 400, 300)
        assertEquals(300, c.outputWidth)
        assertEquals(400, c.outputHeight)
        // Clockwise: the output's top-left is the source's bottom-left.
        assertPoint(0.0 to 300.0, c.matrix.apply(0.0, 0.0)!!)
        assertPoint(0.0 to 0.0, c.matrix.apply(1.0, 0.0)!!)
        assertPoint(400.0 to 300.0, c.matrix.apply(0.0, 1.0)!!)
    }

    @Test
    fun flipsMirrorTheSource() {
        val h = GeometryMath.compose(GeometryAdjustments(flipHorizontal = true), 400, 300).matrix
        assertPoint(400.0 to 150.0, h.apply(0.0, 0.5)!!)
        val v = GeometryMath.compose(GeometryAdjustments(flipVertical = true), 400, 300).matrix
        assertPoint(200.0 to 300.0, v.apply(0.5, 0.0)!!)
    }

    @Test
    fun cropFourFiveGivesAFourByFiveOutput() {
        val frame = floatArrayOf(0f, 0f, 1f, 1f)
        val crop = CropMath.fitAspect(frame, 4f / 5f, 4000, 3000)
        val g =
            GeometryAdjustments(
                cropLeft = crop[0],
                cropTop = crop[1],
                cropRight = crop[2],
                cropBottom = crop[3],
                aspect = CropAspect.FourFive,
            )
        val c = GeometryMath.compose(g, 4000, 3000)
        assertEquals(3000, c.outputHeight)
        assertEquals(2400, c.outputWidth)
        // Centred: the output's left edge is source x 800.
        assertPoint(800.0 to 0.0, c.matrix.apply(0.0, 0.0)!!)
    }

    /** Straighten with the auto-constrain toggle: no output corner samples outside the photo. */
    @Test
    fun constrainLeavesNoWedges() {
        val w = 4000
        val h = 3000
        for (angle in listOf(-30f, -7.5f, 3f, 12f, 45f)) {
            val on = GeometryMath.compose(GeometryAdjustments(straighten = angle), w, h)
            // Constrained: the bicubic taps (2 px) of every edge pixel stay inside the photo.
            assertTrue("constrained at $angle", corners(on).all { inSource(it, w, h, margin = 2.0 - FLOAT_CROP) })
            val off = GeometryMath.compose(GeometryAdjustments(straighten = angle, constrain = false), w, h)
            assertFalse("unconstrained at $angle shows wedges", corners(off).all { inSource(it, w, h) })
        }
        // Upright-style perspective and barrel correction too.
        val g = GeometryAdjustments(vertical = 60f, horizontal = -30f, distortion = 50f)
        val c = GeometryMath.compose(g, w, h)
        // The lens curves the valid edge (the polygon's chords cut it by < 0.1 px): the output's
        // outer edge keeps 1.5 px, so its pixel centres (0.5 px further in) keep the 2 px reach.
        assertTrue(edges(c).all { inSource(distort(g, w, h, it), w, h, margin = 1.5) })
    }

    /** Past the lens model's fold the corners have no undistorted position: the crop stays inside it. */
    @Test
    fun strongBarrelCorrectionStopsAtTheFold() {
        val w = 4000
        val h = 3000
        val g = GeometryAdjustments(distortion = 100f, distortionEdge = -40f)
        val (x, y) = CropMath.undistort(g, w, h, 0.0, 0.0)
        assertTrue(x.isFinite() && y.isFinite() && x > -w && y > -h)
        val c = GeometryMath.compose(g, w, h)
        assertTrue(c.outputWidth in 100..w && c.outputHeight in 100..h)
        assertTrue(edges(c).all { inSource(distort(g, w, h, it), w, h, margin = 1.5) })
    }

    /** Chromatic aberration widens the margin: red and blue sample further out than green. */
    @Test
    fun constrainLeavesRoomForChromaticAberration() {
        val w = 4000
        val h = 3000
        val g = GeometryAdjustments(straighten = 2f, chromaticRed = 100f)
        val margin = CropMath.edgeMargin(g, w, h)
        assertEquals(2.0 + 0.01 * 2500.0, margin, 1e-9)
        val c = GeometryMath.compose(g, w, h)
        assertTrue(corners(c).all { inSource(it, w, h, margin = margin - FLOAT_CROP) })
        // A crop alone samples pixel centres exactly: no margin, the full frame stays.
        assertEquals(0.0, CropMath.edgeMargin(GeometryAdjustments(quarterTurns = 1, flipVertical = true), w, h), 0.0)
        assertEquals(3000, GeometryMath.compose(GeometryAdjustments(quarterTurns = 1), w, h).outputWidth)
    }

    @Test
    fun constrainKeepsTheCropAspect() {
        val crop = CropMath.fitAspect(floatArrayOf(0f, 0f, 1f, 1f), 16f / 9f, 4000, 3000)
        val g = GeometryAdjustments(cropLeft = crop[0], cropTop = crop[1], cropRight = crop[2], cropBottom = crop[3], straighten = 8f)
        val c = GeometryMath.compose(g, 4000, 3000)
        assertEquals(16.0 / 9.0, c.outputWidth.toDouble() / c.outputHeight, 0.01)
    }

    @Test
    fun perspectiveCropMapsTheCornersToTheOutputCorners() {
        val corners = listOf(0.1f, 0.2f, 0.9f, 0.1f, 0.8f, 0.95f, 0.15f, 0.85f)
        val c = GeometryMath.compose(GeometryAdjustments(perspectiveCorners = corners), 1000, 800)
        assertPoint(100.0 to 160.0, c.matrix.apply(0.0, 0.0)!!)
        assertPoint(900.0 to 80.0, c.matrix.apply(1.0, 0.0)!!)
        assertPoint(800.0 to 760.0, c.matrix.apply(1.0, 1.0)!!)
        assertPoint(150.0 to 680.0, c.matrix.apply(0.0, 1.0)!!)
        // Output size: the mean side lengths.
        val top = hypot(800.0, 80.0)
        val bottom = hypot(650.0, 80.0)
        assertEquals(((top + bottom) / 2).toInt().toDouble(), c.outputWidth.toDouble(), 1.0)
    }

    /** Every adjustment ends up in ONE matrix (the pixels are resampled once). */
    @Test
    fun everythingComposesIntoOneMatrix() {
        val g =
            GeometryAdjustments(
                straighten = 4f,
                quarterTurns = 1,
                flipHorizontal = true,
                uprightPitch = 6f,
                vertical = 20f,
                scale = 110f,
                cropLeft = 0.1f,
                cropRight = 0.8f,
                constrain = false,
            )
        val c = GeometryMath.compose(g, 1200, 900)
        val toFrame = GeometryMath.sourceToFrame(g, 1200, 900)
        for ((u, v) in listOf(0.0 to 0.0, 0.3 to 0.7, 1.0 to 1.0)) {
            val (sx, sy) = c.matrix.apply(u, v)!!
            val (fx, fy) = toFrame.apply(sx, sy)!!
            assertPoint((0.1 + 0.7 * u) * 900 to v * 1200, fx to fy)
        }
    }

    @Test
    fun theOpRoundTripsThroughJsonWithItsSettings() {
        val g = GeometryAdjustments(straighten = 2.5f, upright = UprightMode.Guided, guides = listOf(GuideLine(0.1f, 0.1f, 0.12f, 0.9f)))
        val op = g.toOp(640, 480)!!
        val graph = EditGraph().withBackgroundOps(editOps(LightAdjustments(), ColorAdjustments(), GradeAdjustments(), op))
        val back = EditGraphJson.decode(EditGraphJson.encode(graph))
        assertEquals(graph, back)
        assertEquals(g, GeometryAdjustments.fromOps(back.layers.first().ops))
        // Geometry runs first.
        assertTrue(
            back.layers
                .first()
                .ops
                .first() is OpParams.Geometry,
        )
    }

    @Test
    fun uprightFullRecoversATiltedFacade() {
        val w = 4000
        val h = 3000
        val segments = facade(w, h, pitch = 12.0, yaw = 6.0, roll = 3.0)
        val result = Upright.solve(UprightMode.Full, segments, w, h)
        assertEquals(UprightMode.Full, result.applied)
        assertFalse(result.fellBack)
        val corrected = correct(segments, result, w, h)
        for ((angle, vertical) in corrected) {
            if (vertical) assertEquals(90.0, abs(angle), 0.2) else assertEquals(0.0, angle, 0.2)
        }
    }

    @Test
    fun uprightVerticalMakesVerticalsVerticalAndLevelOnlyRotates() {
        val w = 3000
        val h = 4000
        val segments = facade(w, h, pitch = -15.0, yaw = 0.0, roll = -4.0)
        val vertical = Upright.solve(UprightMode.Vertical, segments, w, h)
        assertEquals(0f, vertical.yaw)
        for ((angle, isVertical) in correct(segments, vertical, w, h)) if (isVertical) assertEquals(90.0, abs(angle), 0.2)
        val level = Upright.solve(UprightMode.Level, segments, w, h)
        assertEquals(0f, level.pitch)
        assertEquals(0f, level.yaw)
        assertTrue(abs(level.roll) > 1f)
    }

    /** IF WRONG rule: angles beyond ±25° fall back to Level. */
    @Test
    fun uprightBeyondTheLimitFallsBackToLevel() {
        val w = 4000
        val h = 3000
        val result = Upright.solve(UprightMode.Full, facade(w, h, pitch = 40.0, yaw = 0.0, roll = 2.0), w, h)
        assertTrue(result.fellBack)
        assertEquals(UprightMode.Level, result.applied)
        assertEquals(0f, result.pitch)
        assertTrue(abs(result.roll) <= Upright.LIMIT)
        // No segments at all: nothing changes.
        val none = Upright.solve(UprightMode.Auto, emptyList(), w, h)
        assertEquals(0f, none.roll)
        assertTrue(none.fellBack)
    }

    @Test
    fun guidedUsesTheDrawnLines() {
        val w = 4000
        val h = 3000
        val segments = facade(w, h, pitch = 10.0, yaw = 0.0, roll = 0.0)
        val verticals = segments.filter { abs(it.x2 - it.x1) * w < abs(it.y2 - it.y1) * h }
        val guides = listOf(verticals.first(), verticals.last()).map { GuideLine(it.x1, it.y1, it.x2, it.y2) }
        val result = Upright.solveGuided(guides, w, h)
        assertEquals(UprightMode.Guided, result.applied)
        for ((angle, _) in correct(guides.map { LineSegment(it.x1, it.y1, it.x2, it.y2) }, result, w, h)) {
            assertEquals(90.0, abs(angle), 0.2)
        }
    }

    // A building facade (5 verticals, 5 horizontals) seen by a camera rotated by (pitch, yaw, roll).
    private fun facade(
        w: Int,
        h: Int,
        pitch: Double,
        yaw: Double,
        roll: Double,
    ): List<LineSegment> {
        val f = GeometryMath.DEFAULT_FOCAL * maxOf(w, h)
        // The camera's rotation is the inverse of the correction.
        val tilt = GeometryMath.cameraRotation(pitch, yaw, roll, f).inverse()!!

        fun project(
            x: Double,
            y: Double,
        ): Pair<Double, Double> {
            val (px, py) = tilt.apply(x * f / 10, y * f / 10)!!
            return (px + w / 2.0) / w to (py + h / 2.0) / h
        }
        val segments = ArrayList<LineSegment>()
        for (i in -2..2) {
            val (x1, y1) = project(i * 1.0, -2.5)
            val (x2, y2) = project(i * 1.0, 2.5)
            segments += LineSegment(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
        }
        for (j in -2..2) {
            val (x1, y1) = project(-2.5, j * 1.0)
            val (x2, y2) = project(2.5, j * 1.0)
            segments += LineSegment(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
        }
        return segments
    }

    // Segment angles after the Upright correction, and whether each was a vertical.
    private fun correct(
        segments: List<LineSegment>,
        r: UprightResult,
        w: Int,
        h: Int,
    ): List<Pair<Double, Boolean>> {
        val m =
            GeometryMath.cameraRotation(r.pitch.toDouble(), r.yaw.toDouble(), r.roll.toDouble(), GeometryMath.DEFAULT_FOCAL * maxOf(w, h))
        return segments.map { s ->
            val (x1, y1) = m.apply(s.x1 * w - w / 2.0, s.y1 * h - h / 2.0)!!
            val (x2, y2) = m.apply(s.x2 * w - w / 2.0, s.y2 * h - h / 2.0)!!
            val vertical = abs(s.y2 - s.y1) * h > abs(s.x2 - s.x1) * w
            GeometryMath.lineAngle(x1, y1, x2, y2) to vertical
        }
    }

    private fun corners(c: ComposedGeometry) =
        listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0).map { (u, v) -> c.matrix.apply(u, v)!! }

    private fun edges(c: ComposedGeometry): List<Pair<Double, Double>> {
        val points = ArrayList<Pair<Double, Double>>()
        for (i in 0..20) {
            val t = i / 20.0
            for ((u, v) in listOf(t to 0.0, t to 1.0, 0.0 to t, 1.0 to t)) points += c.matrix.apply(u, v)!!
        }
        return points
    }

    // The engine's lens model (geometry.cpp mapToSource), green channel.
    private fun distort(
        g: GeometryAdjustments,
        w: Int,
        h: Int,
        p: Pair<Double, Double>,
    ): Pair<Double, Double> {
        val cx = w / 2.0
        val cy = h / 2.0
        val radius = 0.5 * hypot(w.toDouble(), h.toDouble())
        val qx = p.first - cx
        val qy = p.second - cy
        val r2 = (qx * qx + qy * qy) / (radius * radius)
        val s = 1 + g.k1 * r2 + g.k2 * r2 * r2
        return cx + qx * s to cy + qy * s
    }

    private fun inSource(
        p: Pair<Double, Double>,
        w: Int,
        h: Int,
        margin: Double = -0.5,
    ) = p.first >= margin && p.second >= margin && p.first <= w - margin && p.second <= h - margin

    private fun assertPoint(
        expected: Pair<Double, Double>,
        actual: Pair<Double, Double>,
    ) {
        assertEquals(expected.first, actual.first, 1e-6 * maxOf(1.0, abs(expected.first)))
        assertEquals(expected.second, actual.second, 1e-6 * maxOf(1.0, abs(expected.second)))
    }

    private fun round6(v: Double) = Math.round(v * 1e6) / 1e6

    private companion object {
        /** The crop is stored as floats: about 1e-3 px at 4000 px. */
        const val FLOAT_CROP = 0.01
    }
}
