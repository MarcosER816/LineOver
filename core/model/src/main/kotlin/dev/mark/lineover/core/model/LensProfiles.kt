package dev.mark.lineover.core.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

/**
 * A lens profile converted exactly to LineOver's lens model (Stage 9, r = 1 at the image
 * corner): distortion s(r) += p1 r + p2 r² + p3 r³ + p4 r⁴ ([distortion]), red / blue scale
 * += tv + tc rd + tb rd² on the distorted radius ([tcaRed], [tcaBlue]), and the "pa"
 * vignetting polynomial ([vignette], k1..k3). Lensfun's ptlens / poly3 / poly5 distortion and
 * linear / poly3 TCA are all of these forms, so nothing is approximated.
 */
@Serializable
data class LensProfile(
    val lens: String,
    val camera: String? = null,
    val distortion: List<Double> = emptyList(),
    val tcaRed: List<Double> = emptyList(),
    val tcaBlue: List<Double> = emptyList(),
    val vignette: List<Double> = emptyList(),
)

/** What the photo's EXIF says about its camera and lens. */
data class PhotoExif(
    val make: String? = null,
    val model: String? = null,
    val lensMake: String? = null,
    val lensModel: String? = null,
    val focal: Double? = null,
    val focal35: Double? = null,
    val aperture: Double? = null,
    val distance: Double? = null,
)

/** The lens the database has for a photo, the camera body if known, and the image crop factor. */
data class LensMatch(
    val lens: LensEntry,
    val camera: LensCamera?,
    val crop: Double,
)

/**
 * Lensfun calibrations -> LineOver's lens model (Stage 9). Lensfun's distortion and TCA
 * use the Hugin coordinate system (r = 1 at half the short side of the calibration sensor,
 * its vignetting r = 1 at that sensor's corner); both are rescaled here to r = 1 at the
 * photo's corner using the crop factors, as lensfun's `rescale_polynomial_coefficients`
 * does, including its normalisation by d (the scale at the centre). The conversion is
 * exact. `tools/golden/lensfun_reference.py` computes the same corrections with lensfun's
 * own arithmetic; `LensProfilesTest` compares.
 */
@Suppress("MagicNumber") // polynomial powers and term positions of the Lensfun models
object LensProfiles {
    val FULL_FRAME_DIAGONAL = hypot(36.0, 24.0)
    private const val DEFAULT_DISTANCE = 1000.0

    fun profile(
        lens: LensEntry,
        focal: Double,
        aperture: Double?,
        distance: Double?,
        imageCrop: Double,
        camera: String? = null,
    ): LensProfile {
        // Our r = 1 (the photo's half diagonal) in Hugin units of the calibration sensor.
        val alpha = lens.crop * hypot(lens.aspect, 1.0) / imageCrop
        val vignette =
            aperture
                ?.let { vignettingTerms(lens, focal, it, distance ?: DEFAULT_DISTANCE) }
                ?.let { k ->
                    val v = lens.crop / imageCrop
                    listOf(k[0] * v.pow(2), k[1] * v.pow(4), k[2] * v.pow(6))
                }.orEmpty()
        val (red, blue) = tca(lens, focal, alpha)
        return LensProfile(lens.name, camera, distortion(lens, focal, alpha), red, blue, vignette)
    }

    /** p1..p4 of s(r) = 1 + p1 r + p2 r² + p3 r³ + p4 r⁴ (empty without a calibration). */
    private fun distortion(
        lens: LensEntry,
        focal: Double,
        alpha: Double,
    ): List<Double> {
        val (model, t) = interpolate(lens.distortion, focal) ?: return emptyList()
        return when (model) {
            "poly3" -> {
                val k = t["k1"] ?: 0.0
                listOf(0.0, k * alpha * alpha / (1 - k).pow(3), 0.0, 0.0)
            }

            "poly5" -> {
                listOf(0.0, (t["k1"] ?: 0.0) * alpha.pow(2), 0.0, (t["k2"] ?: 0.0) * alpha.pow(4))
            }

            "ptlens" -> {
                val a = t["a"] ?: 0.0
                val b = t["b"] ?: 0.0
                val c = t["c"] ?: 0.0
                val d = 1 - a - b - c
                listOf(c * alpha / (d * d), b * alpha.pow(2) / d.pow(3), a * alpha.pow(3) / d.pow(4), 0.0)
            }

            else -> {
                emptyList()
            }
        }
    }

    /** (red, blue) as (tv, tc, tb): scale = 1 + tv + tc rd + tb rd², rd the distorted radius. */
    private fun tca(
        lens: LensEntry,
        focal: Double,
        alpha: Double,
    ): Pair<List<Double>, List<Double>> {
        val (model, t) = interpolate(lens.tca, focal) ?: return emptyList<Double>() to emptyList()
        return when (model) {
            "linear" -> {
                listOf((t["kr"] ?: 1.0) - 1, 0.0, 0.0) to listOf((t["kb"] ?: 1.0) - 1, 0.0, 0.0)
            }

            "poly3" -> {
                fun terms(
                    v: Double,
                    c: Double,
                    b: Double,
                ) = listOf(v - 1, c * alpha, b * alpha * alpha)
                terms(t["vr"] ?: 1.0, t["cr"] ?: 0.0, t["br"] ?: 0.0) to terms(t["vb"] ?: 1.0, t["cb"] ?: 0.0, t["bb"] ?: 0.0)
            }

            else -> {
                emptyList<Double>() to emptyList()
            }
        }
    }

    /**
     * The calibration terms at [focal]: exact, linear between the two calibrated neighbours
     * (Lensfun uses a spline here), or the nearest end outside the calibrated range.
     */
    fun interpolate(
        entries: List<LensCalibration>,
        focal: Double,
    ): Pair<String, Map<String, Double>>? {
        if (entries.isEmpty()) return null
        val model = entries.minBy { abs(it.focal - focal) }.model
        val rows = entries.filter { it.model == model }.sortedBy { it.focal }
        val lo = rows.lastOrNull { it.focal <= focal + EXACT } ?: rows.first()
        val hi = rows.firstOrNull { it.focal >= focal - EXACT } ?: rows.last()
        val terms =
            if (hi.focal - lo.focal < EXACT) {
                lo.terms
            } else {
                lerp(lo.terms, hi.terms, (focal - lo.focal) / (hi.focal - lo.focal))
            }
        return model to terms
    }

    private const val EXACT = 1e-9

    private fun lerp(
        a: Map<String, Double>,
        b: Map<String, Double>,
        t: Double,
    ): Map<String, Double> = (a.keys + b.keys).associateWith { k -> (a[k] ?: 0.0) + ((b[k] ?: 0.0) - (a[k] ?: 0.0)) * t }

    /**
     * Vignetting k1..k3 at ([focal], [aperture]) for the calibrated distance nearest to
     * [distance] (compared as 1 / distance): bilinear in focal and log aperture between the
     * calibrated neighbours, the nearest end outside them. Null when the lens has none.
     */
    fun vignettingTerms(
        lens: LensEntry,
        focal: Double,
        aperture: Double,
        distance: Double,
    ): List<Double>? {
        val pa = lens.vignetting.filter { it.model == "pa" && it.aperture != null }
        if (pa.isEmpty()) return null
        val nearest = pa.minBy { abs(1 / (it.distance ?: DEFAULT_DISTANCE) - 1 / distance) }.distance
        val group = pa.filter { it.distance == nearest }
        val focals = group.map { it.focal }.distinct().sorted()
        val (fLo, fHi, ft) = bracket(focals, focal)

        fun atFocal(f: Double): List<Double> {
            val rows = group.filter { it.focal == f }.sortedBy { it.aperture }
            val (aLo, aHi, at) = bracket(rows.map { ln(requireNotNull(it.aperture)) }, ln(aperture))
            val lo = rows.first { ln(requireNotNull(it.aperture)) == aLo }.terms
            val hi = rows.first { ln(requireNotNull(it.aperture)) == aHi }.terms
            return listOf("k1", "k2", "k3").map { k -> (lo[k] ?: 0.0) + ((hi[k] ?: 0.0) - (lo[k] ?: 0.0)) * at }
        }
        val lo = atFocal(fLo)
        val hi = atFocal(fHi)
        return lo.indices.map { lo[it] + (hi[it] - lo[it]) * ft }
    }

    /** The neighbours of [x] in sorted [values] and the fraction between them (clamped). */
    private fun bracket(
        values: List<Double>,
        x: Double,
    ): Triple<Double, Double, Double> {
        val sorted = values.distinct().sorted()
        if (x <= sorted.first()) return Triple(sorted.first(), sorted.first(), 0.0)
        if (x >= sorted.last()) return Triple(sorted.last(), sorted.last(), 0.0)
        val hi = sorted.first { it >= x }
        val lo = sorted.last { it <= x }
        return Triple(lo, hi, if (hi == lo) 0.0 else (x - lo) / (hi - lo))
    }
}
