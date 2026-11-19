package dev.mark.lineover.core.model

/**
 * Presets → Profiles and 3D LUTs (Stage 11): one base look ([profile], applied after white
 * balance, before Light) and one creative LUT ([lutId], after the grading). Amounts 0..100 %.
 */
data class LookAdjustments(
    val profile: String = NEUTRAL,
    val profileAmount: Float = FULL,
    val lutId: String? = null,
    val lutName: String = "",
    val lutAmount: Float = FULL,
) {
    val isIdentity: Boolean get() = (profileOps() + lutOps()).isEmpty()

    fun profileOps(): List<OpParams> = listOf(OpParams.Profile(profile, profileAmount)).filterNot { it.isIdentity }

    fun lutOps(): List<OpParams> = listOfNotNull(lutId?.let { OpParams.Lut(it, lutName, lutAmount) }).filterNot { it.isIdentity }

    companion object {
        const val NEUTRAL = "neutral"
        const val FULL = 100f

        /** The built-in profiles, in the panel's order (lut.h makes their tables). */
        val PROFILES = listOf(NEUTRAL, "vivid", "mono", "flat")

        fun label(profile: String): String = profile.replaceFirstChar { it.uppercase() }

        fun fromOps(ops: List<OpParams>): LookAdjustments {
            var looks = LookAdjustments()
            for (op in ops) {
                looks =
                    when (op) {
                        is OpParams.Profile -> looks.copy(profile = op.name, profileAmount = op.amount.coerceIn(0f, FULL))
                        is OpParams.Lut -> looks.copy(lutId = op.id, lutName = op.name, lutAmount = op.amount.coerceIn(0f, FULL))
                        else -> looks
                    }
            }
            return looks
        }
    }
}
