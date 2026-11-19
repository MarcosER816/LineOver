package dev.mark.lineover.core.model

/**
 * Finds a photo's camera and lens in the Lensfun database (Stage 9): the camera body by EXIF
 * make and model (its crop factor and mount), the lens by how much of the database name the
 * EXIF lens name covers, among lenses that fit the mount and the focal length. A compact's
 * built-in lens shares the camera's own mount, so it matches without a lens name.
 */
object LensMatcher {
    private const val MATCH_THRESHOLD = 0.75
    private const val FOCAL_SLACK = 0.05

    /** The camera body of [exif] in [db], or null. */
    fun camera(
        db: LensDatabase,
        exif: PhotoExif,
    ): LensCamera? {
        val model = normalise(exif.model ?: return null)
        val maker = normalise(exif.make.orEmpty())
        return db.cameras.firstOrNull { normalise(it.model) == model && (maker.isEmpty() || makerMatches(it.maker, maker)) }
            ?: db.cameras.firstOrNull { normalise(it.model).removePrefix(normalise(it.maker)).trim() == model.removePrefix(maker).trim() }
    }

    /** The photo's crop factor: its camera's, else from the 35 mm-equivalent focal length. */
    fun imageCrop(
        db: LensDatabase,
        exif: PhotoExif,
    ): Double? {
        val f = exif.focal ?: 0.0
        val f35 = exif.focal35 ?: 0.0
        return camera(db, exif)?.crop ?: if (f > 0 && f35 > 0) f35 / f else null
    }

    /** Lenses of [db] that fit the photo's camera and focal length, best match first. */
    fun candidates(
        db: LensDatabase,
        exif: PhotoExif,
    ): List<LensEntry> {
        val camera = camera(db, exif)
        val mounts = camera?.let { db.fitting(it.mount) }
        val focal = exif.focal
        return db.lenses
            .filter { it.hasCorrections }
            .filter { mounts == null || it.mounts.any { m -> m in mounts } }
            .filter { focal == null || focal in it.focalRange.start * (1 - FOCAL_SLACK)..it.focalRange.endInclusive * (1 + FOCAL_SLACK) }
            .sortedByDescending { score(it, exif) }
    }

    /** The best lens for [exif] when it matches well enough (or the only lens of a fixed-lens camera). */
    fun match(
        db: LensDatabase,
        exif: PhotoExif,
    ): LensMatch? {
        val camera = camera(db, exif)
        val candidates = candidates(db, exif)
        val crop = imageCrop(db, exif)
        val best = candidates.firstOrNull()
        val lens =
            when {
                best != null && score(best, exif) >= MATCH_THRESHOLD -> best

                // A compact: its lens has the camera's own mount.
                camera != null -> candidates.singleOrNull { it.mounts.contains(camera.mount) && camera.mount !in GENERIC_MOUNTS }

                else -> null
            } ?: return null
        return LensMatch(lens, camera, crop ?: lens.crop)
    }

    private val GENERIC_MOUNTS = setOf("Generic")

    /** How well the database lens's name is covered by the EXIF lens name (0..1). */
    fun score(
        lens: LensEntry,
        exif: PhotoExif,
    ): Double {
        val wanted = tokens(lens.model) - tokens(lens.maker)
        if (wanted.isEmpty()) return 0.0
        val have = tokens(listOfNotNull(exif.lensMake, exif.lensModel).joinToString(" "))
        if (have.isEmpty()) return 0.0
        val covered = wanted.count { it in have }.toDouble() / wanted.size
        val makerBonus = if (exif.lensMake != null && makerMatches(lens.maker, normalise(exif.lensMake))) BONUS else 0.0
        return covered + makerBonus
    }

    private const val BONUS = 0.01

    private fun makerMatches(
        maker: String,
        normalisedOther: String,
    ): Boolean {
        val m = normalise(maker)
        return m == normalisedOther || m.startsWith(normalisedOther) || normalisedOther.startsWith(m)
    }

    private fun normalise(s: String): String = s.lowercase().replace(Regex("\\s+"), " ").trim()

    private val NON_TOKEN = Regex("[^a-z0-9.\\-]+")

    /** "FE 24-70mm f/2.8 GM" -> {fe, 24-70mm, f2.8, gm}. */
    fun tokens(s: String): Set<String> =
        s
            .lowercase()
            .replace("f/", "f")
            .replace(NON_TOKEN, " ")
            .split(' ')
            .map { it.trim('.', '-') }
            .filter { it.isNotEmpty() }
            .toSet()
}
