package dev.mark.lineover.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Lens profiles (Stage 9): the Lensfun database fixture (testdata/lensfun, CC BY-SA 3.0)
 * converted to LineOver's lens model, against the corrections Lensfun's own arithmetic gives
 * (tools/golden/lensfun_reference.py -> testdata/golden/lensfun/corrections.json).
 */
class LensProfilesTest {
    private val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        requireNotNull(dir)
    }

    private val db by lazy { LensDatabase.parse(listOf(File(root, "testdata/lensfun/fixture.xml").readText())) }

    @Test
    fun theFixtureParses() {
        assertEquals(4, db.lenses.size)
        assertEquals(4, db.cameras.size)
        val gm = db.lenses.single { it.model == "FE 24-70mm f/2.8 GM" }
        assertEquals(listOf("Sony E"), gm.mounts)
        assertEquals(1.0, gm.crop, 0.0)
        assertEquals("ptlens", gm.distortion.first().model)
        assertEquals(0.02824, gm.distortion.first { it.focal == 24.0 }.terms["a"]!!, 0.0)
        assertTrue(gm.vignetting.size > 100)
        assertEquals(4.0 / 3.0, db.lenses.single { it.model.startsWith("Canon PowerShot G12") }.aspect, 1e-12)
        assertTrue("Sony E takes M42 lenses", "M42" in db.fitting("Sony E"))
    }

    /** Every fixture case: the source position of each channel and the devignetting gain, vs Lensfun. */
    @Test
    fun profilesMatchLensfunsOwnCorrections() {
        val golden = Json.parseToJsonElement(File(root, "testdata/golden/lensfun/corrections.json").readText()).jsonObject
        val cases = golden["cases"]!!.jsonArray
        assertTrue(cases.size >= 7)
        for (case in cases) {
            val c = case.jsonObject
            val lens = db.lenses.single { it.model == c.string("lens") }
            val w = c["width"]!!.jsonPrimitive.int
            val h = c["height"]!!.jsonPrimitive.int
            val focal = c["focal"]!!.jsonPrimitive.double
            val profile =
                LensProfiles.profile(
                    lens,
                    focal,
                    c["aperture"]!!.jsonPrimitive.doubleOrNull,
                    c["distance"]!!.jsonPrimitive.doubleOrNull,
                    c["crop"]!!.jsonPrimitive.double,
                )
            var worst = 0.0
            var worstGain = 0.0
            for (p in c["points"]!!.jsonArray) {
                val point = p.jsonObject
                val pixel = point["pixel"]!!.jsonArray.map { it.jsonPrimitive.double }
                val sources = point["source"]!!.jsonArray
                for (channel in 0 until 3) {
                    val expected = sources[channel].jsonArray.map { it.jsonPrimitive.double }
                    val (x, y) = engineSource(profile, w to h, pixel[0], pixel[1], channel)
                    worst = max(worst, hypot(x - expected[0], y - expected[1]))
                }
                val gain = point["gain"]!!.jsonPrimitive.double
                worstGain = max(worstGain, abs(engineGain(profile, w, h, pixel[0], pixel[1]) - gain) / gain)
            }
            println("${lens.model} @ ${focal}mm crop ${c["crop"]}: max ${"%.2e".format(worst)} px, gain ${"%.2e".format(worstGain)}")
            assertTrue("${lens.model} @ $focal: position within $worst px", worst <= TOLERANCE_PX)
            assertTrue("${lens.model} @ $focal: gain off by $worstGain", worstGain <= 1e-6)
        }
    }

    @Test
    fun aFullFrameZoomOnAFullFrameBodyMatches() {
        val exif = PhotoExif("SONY", "ILCE-7M3", "Sony", "FE 24-70mm F2.8 GM", focal = 35.0, focal35 = 35.0, aperture = 4.0)
        val match = assertNotNullAndGet(LensMatcher.match(db, exif))
        assertEquals("FE 24-70mm f/2.8 GM", match.lens.model)
        assertEquals("ILCE-7M3", match.camera?.model)
        assertEquals(1.0, match.crop, 0.0)
    }

    @Test
    fun anApsCBodyUsesItsCropFactor() {
        val exif = PhotoExif("SONY", "ILCE-6000", null, "E 24mm F1.8 ZA", focal = 24.0)
        val match = assertNotNullAndGet(LensMatcher.match(db, exif))
        assertEquals("E 24mm f/1.8 ZA", match.lens.model)
        assertEquals(1.534, match.crop, 1e-9)
    }

    @Test
    fun aCompactFindsItsBuiltInLensWithoutALensName() {
        val exif = PhotoExif("Canon", "Canon PowerShot G12", focal = 6.1)
        val match = assertNotNullAndGet(LensMatcher.match(db, exif))
        assertTrue(match.lens.model.startsWith("Canon PowerShot G12"))
        assertEquals(4.63, match.crop, 1e-9)
    }

    @Test
    fun aPhoneHasNoProfileButItsCropComesFromTheExif() {
        val exif = PhotoExif("Xiaomi", "25010PN30G", null, null, focal = 6.7, focal35 = 23.0)
        assertNull(LensMatcher.match(db, exif))
        assertEquals(23.0 / 6.7, LensMatcher.imageCrop(db, exif)!!, 1e-9)
        // An unrelated lens name does not match either.
        assertNull(LensMatcher.match(db, PhotoExif("SONY", "ILCE-7M3", "Sony", "FE 85mm F1.4 GM", focal = 85.0)))
    }

    @Test
    fun theProfileFlowsIntoTheGeometryOp() {
        val lens = db.lenses.single { it.model == "FE 24-70mm f/2.8 GM" }
        val profile = LensProfiles.profile(lens, 24.0, 2.8, 1000.0, 1.0)
        val g = GeometryAdjustments(profile = profile, profileDistortion = 50f, profileVignetting = 0f, profileChromatic = false)
        val op = requireNotNull(g.toOp(6000, 4000))
        assertEquals(profile.distortion.map { it * 0.5 }, op.lensDistortion)
        assertTrue(op.vignetteProfile.isEmpty())
        assertTrue(op.lensTcaRed.isEmpty())
        val full = requireNotNull(GeometryAdjustments(profile = profile).toOp(6000, 4000))
        assertEquals(profile.vignette, full.vignetteProfile)
        assertEquals(profile.tcaRed, full.lensTcaRed)
        // Every edge pixel of the corrected (constrained) output samples inside the photo, all channels.
        val m = Mat3(full.matrix.toDoubleArray())
        for (i in 0..40) {
            val t = i / 40.0
            for ((u, v) in listOf(t to 0.0, t to 1.0, 0.0 to t, 1.0 to t)) {
                val (x, y) = m.apply(u, v)!!
                for (channel in 0 until 3) {
                    val (sx, sy) = engineSource(profile, 6000 to 4000, x - 0.5, y - 0.5, channel)
                    assertTrue("($u, $v) channel $channel -> ($sx, $sy)", sx >= 1.0 && sy >= 1.0 && sx <= 5998.0 && sy <= 3998.0)
                }
            }
        }
        assertEquals(GeometryAdjustments(profile = profile), GeometryAdjustments.fromOps(listOf(full)))
    }

    // LineOver's engine mapping (geometry.cpp mapToSource, identity matrix), in lensfun's pixel
    // coordinates (pixel centres at integers).
    private fun engineSource(
        p: LensProfile,
        size: Pair<Int, Int>,
        px: Double,
        py: Double,
        channel: Int,
    ): Pair<Double, Double> {
        val (w, h) = size
        val cx = w / 2.0
        val cy = h / 2.0
        val radius = 0.5 * hypot(w.toDouble(), h.toDouble())
        val qx = px + 0.5 - cx
        val qy = py + 0.5 - cy
        val r2 = (qx * qx + qy * qy) / (radius * radius)
        val g = GeometryAdjustments(profile = p)
        var s = g.radialScale(r2)
        val t =
            if (channel == 0) {
                p.tcaRed
            } else if (channel == 2) {
                p.tcaBlue
            } else {
                emptyList()
            }
        if (t.isNotEmpty()) {
            val rd = kotlin.math.sqrt(r2) * s
            s *= 1 + t[0] + rd * (t[1] + rd * t[2])
        }
        return cx + qx * s - 0.5 to cy + qy * s - 0.5
    }

    // The engine's vignetting op (geometry.cpp VignettingOp) with the profile only.
    private fun engineGain(
        p: LensProfile,
        w: Int,
        h: Int,
        px: Double,
        py: Double,
    ): Double {
        if (p.vignette.isEmpty()) return 1.0
        val r = hypot(px + 0.5 - w / 2.0, py + 0.5 - h / 2.0) / (0.5 * hypot(w.toDouble(), h.toDouble()))
        val r2 = r * r
        return 1.0 / (1 + r2 * (p.vignette[0] + r2 * (p.vignette[1] + r2 * p.vignette[2])))
    }

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private companion object {
        /** Exact conversion: double arithmetic in two routes (lensfun's coordinates and ours). */
        const val TOLERANCE_PX = 1e-6
    }
}
