package dev.mark.lineover.core.ml

/**
 * Describes one downloadable AI model. Models are never bundled in the APK
 * (rule R0 / D3): a tool downloads its model on first use after showing
 * [sizeBytes] and [license], verifies [sha256], and Settings can delete it.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val license: String,
    val sha256: String,
) {
    init {
        require(sizeBytes > 0) { "sizeBytes must be positive" }
        require(SHA256_HEX.matches(sha256)) { "sha256 must be 64 lowercase hex characters" }
    }

    private companion object {
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}
