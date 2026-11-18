package dev.mark.lineover.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class GradeAdjustmentsTest {
    private val grade =
        GradeAdjustments(
            curves =
                mapOf(CurveChannel.Rgb to listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.2f), CurvePoint(0.75f, 0.8f), CurvePoint(1f, 1f))),
            parametric = listOf(-20f, 10f, 5f, 15f, 0.25f, 0.5f, 0.75f),
            levels = listOf(0.02f, 1.1f, 0.98f, 0f, 1f) + GradeAdjustments.LEVELS_IDENTITY.drop(5),
            wheels = mapOf(GradeRange.Shadows to GradeWheel(190f, 40f, 0f), GradeRange.Highlights to GradeWheel(45f, 35f, 0f)),
            colorBalance = listOf(-10f, 0f, 10f, 0f, 0f, 0f, 10f, 0f, -10f),
            selective =
                List(36) {
                    if (it == 0) {
                        -20f
                    } else if (it == 35) {
                        10f
                    } else {
                        0f
                    }
                },
            mixer = listOf(100f, 0f, 0f, 0f, 100f, 0f, 0f, 0f, 90f),
            blackAndWhite = true,
            bwMix = listOf(10f, 0f, 0f, 0f, -20f, 0f),
            bwTintHue = 40f,
            bwTintSaturation = 10f,
            filterDensity = 20f,
            gradientMap = true,
            calibrationHue = listOf(10f, 0f, -5f),
            calibrationSaturation = listOf(0f, 15f, 0f),
        )

    private val fixture: String by lazy {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        File(requireNotNull(dir), "testdata/graph/grade_v1.json").readText().trimEnd()
    }

    @Test
    fun encodingIsTheSharedNativeFixture() {
        val graph = EditGraph().withBackgroundOps(editOps(LightAdjustments(), ColorAdjustments(), grade))
        assertEquals(fixture, EditGraphJson.encode(graph))
        assertEquals(graph, EditGraphJson.decode(fixture))
    }

    @Test
    fun opsReadBack() {
        assertEquals(grade, GradeAdjustments.fromOps(editOps(LightAdjustments(), ColorAdjustments(), grade)))
    }

    @Test
    fun theFullOrderIsFixed() {
        val ops =
            editOps(
                LightAdjustments().with(LightParam.Exposure, 1f),
                ColorAdjustments(temperature = 5000f, vibrance = 10f, invert = true),
                GradeAdjustments(
                    calibrationHue = listOf(5f, 0f, 0f),
                    filterDensity = 30f,
                    levels =
                        listOf(0.1f, 1f, 1f, 0f, 1f) + GradeAdjustments.LEVELS_IDENTITY.drop(5),
                ),
            )
        assertEquals(
            listOf("Calibration", "WhiteBalance", "Exposure", "Levels", "Vibrance", "PhotoFilter", "Invert"),
            ops.map { it::class.simpleName },
        )
    }

    @Test
    fun defaultsAddNoOps() {
        assertEquals(emptyList<OpParams>(), editOps(LightAdjustments(), ColorAdjustments(), GradeAdjustments()))
    }

    @Test
    fun aTapAddsAPointOnTheCurveAndTooCloseIsRefused() {
        val (g, index) = requireNotNull(GradeAdjustments().addCurvePoint(CurveChannel.Rgb, 0.4f))
        assertEquals(1, index)
        assertEquals(CurvePoint(0.4f, 0.4f), g.curve(CurveChannel.Rgb)[1])
        assertNull(g.addCurvePoint(CurveChannel.Rgb, 0.405f))
    }

    @Test
    fun monotoneModeKeepsPointsBetweenTheirNeighbours() {
        var g = requireNotNull(GradeAdjustments().addCurvePoint(CurveChannel.Rgb, 0.3f)).first
        g = requireNotNull(g.addCurvePoint(CurveChannel.Rgb, 0.7f)).first
        g = g.moveCurvePoint(CurveChannel.Rgb, 1, 0.9f, 0.95f) // x clamps below the next point, y below its y
        val p = g.curve(CurveChannel.Rgb)
        assertTrue(p[1].x < p[2].x)
        assertEquals(p[2].y, p[1].y)
        g = g.moveCurvePoint(CurveChannel.Rgb, 0, 0.5f, 0.2f) // the black end keeps x = 0
        assertEquals(CurvePoint(0f, 0.2f), g.curve(CurveChannel.Rgb)[0])
        // Free mode allows an inversion; leaving it makes the curve rise again.
        val free = g.withFreeCurve(true).moveCurvePoint(CurveChannel.Rgb, 3, 1f, 0.1f)
        assertEquals(0.1f, free.curve(CurveChannel.Rgb)[3].y)
        val fixed = free.withFreeCurve(false).curve(CurveChannel.Rgb)
        assertTrue(fixed.zipWithNext().all { (a, b) -> b.y >= a.y })
    }

    @Test
    fun onlyInteriorPointsCanBeDeleted() {
        val g = requireNotNull(GradeAdjustments().addCurvePoint(CurveChannel.Red, 0.5f)).first
        assertEquals(2, g.deleteCurvePoint(CurveChannel.Red, 1).curve(CurveChannel.Red).size)
        assertEquals(3, g.deleteCurvePoint(CurveChannel.Red, 0).curve(CurveChannel.Red).size)
    }

    /** Property: any sequence of edits in monotone mode leaves a monotone curve. */
    @Test
    fun randomEditsInMonotoneModeNeverInvert() {
        val rng = Random(7)
        repeat(300) {
            var g = GradeAdjustments()
            repeat(20) {
                val points = g.curve(CurveChannel.Rgb)
                when (rng.nextInt(3)) {
                    0 -> g = g.addCurvePoint(CurveChannel.Rgb, rng.nextFloat())?.first ?: g
                    1 -> g = g.moveCurvePoint(CurveChannel.Rgb, rng.nextInt(points.size), rng.nextFloat(), rng.nextFloat())
                    else -> g = g.deleteCurvePoint(CurveChannel.Rgb, rng.nextInt(points.size))
                }
            }
            val curve = MonotoneCurve(g.curve(CurveChannel.Rgb))
            var previous = curve(0f)
            for (i in 1..1000) {
                val y = curve(i / 1000f)
                assertTrue("curve decreased", y >= previous - 1e-5f)
                previous = y
            }
        }
    }

    /** Property: Fritsch-Carlson through random monotone points is monotone and interpolates. */
    @Test
    fun splineIsMonotoneForRandomPoints() {
        val rng = Random(20261118)
        repeat(1000) {
            val n = 2 + rng.nextInt(8)
            val xs = (listOf(0f, 1f) + List(n - 2) { 0.02f + 0.96f * rng.nextFloat() }).sorted()
            if (xs.zipWithNext().any { (a, b) -> b - a < 1e-3f }) return@repeat
            val ys = List(n) { rng.nextFloat() }.sorted()
            val points = xs.zip(ys).map { (x, y) -> CurvePoint(x, y) }
            val curve = MonotoneCurve(points)
            for (p in points) assertEquals(p.y, curve(p.x), 1e-5f)
            var previous = curve(0f)
            for (i in 1..2000) {
                val y = curve(i / 2000f)
                assertTrue(y >= previous - 1e-6f)
                previous = y
            }
        }
    }

    @Test
    fun autoLevelsSetsPerChannelEnds() {
        val g = GradeAdjustments().withAutoLevels(floatArrayOf(0.05f, 0.9f, 0.02f, 0.95f, 0.7f, 0.98f))
        assertEquals(0.05f, g.level(1, 0))
        assertEquals(0.9f, g.level(1, 2))
        assertEquals(0.5f, g.level(3, 0)) // a black point is capped
        assertEquals(listOf(0f, 1f, 1f, 0f, 1f), g.levels.take(5))
    }
}

class HueMapTest {
    @Test
    fun bandCentresMapExactlyAndRoundTrip() {
        assertEquals(52.8f, HueMap.hsvToOklch(30f), 1e-4f) // orange
        assertEquals(264.1f, HueMap.hsvToOklch(240f), 1e-4f) // blue
        assertEquals(29.2f, HueMap.hsvToOklch(0f), 1e-4f)
        for (h in 0 until 360 step 7) assertEquals(h.toFloat(), HueMap.oklchToHsv(HueMap.hsvToOklch(h.toFloat())), 1e-3f)
        assertEquals(10f, HueMap.oklchToHsv(HueMap.hsvToOklch(370f)), 1e-3f)
    }
}
