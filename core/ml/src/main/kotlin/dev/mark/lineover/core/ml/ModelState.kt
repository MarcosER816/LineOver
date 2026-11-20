package dev.mark.lineover.core.ml

/** Where a model is (Stage 16). */
sealed interface ModelState {
    /** Not on the device: the first use asks to download it. */
    data object Missing : ModelState

    /** Downloading: [bytes] of [total] so far (a resumed download starts above 0). */
    data class Downloading(
        val bytes: Long,
        val total: Long,
    ) : ModelState {
        val fraction: Float get() = if (total > 0L) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    /** Checking the SHA-256. */
    data object Verifying : ModelState

    /** Downloaded and verified. */
    data object Ready : ModelState

    /** The last attempt failed with [error]; a partial download is kept to resume. */
    data class Failed(
        val error: ModelError,
    ) : ModelState
}

/** Why a model is not available. */
enum class ModelError {
    /** No network: nothing was requested. */
    Offline,

    /** Only mobile data, and models download on Wi-Fi only (Settings). */
    NeedsWifi,

    /** The file's SHA-256 is not the published one: it was deleted. */
    Integrity,

    /** The connection failed or the server refused (the part already downloaded is kept). */
    Network,

    /** The file could not be written (storage full?). */
    Storage,

    /** The runtime could not load the model on this device. */
    Unavailable,
}

/** A one-line, user-facing explanation of [error] for the model [name] of [sizeText]. */
fun ModelError.message(
    name: String,
    sizeText: String,
): String =
    when (this) {
        ModelError.Offline -> "You're offline. Connect to the internet to download $name ($sizeText); it's needed once."
        ModelError.NeedsWifi -> "$name ($sizeText) downloads on Wi-Fi only. Connect to Wi-Fi, or download it on mobile data."
        ModelError.Integrity -> "The downloaded $name didn't match its published checksum and was deleted. Try again."
        ModelError.Network -> "The download of $name was interrupted. Try again: it continues where it stopped."
        ModelError.Storage -> "There isn't enough free storage for $name ($sizeText)."
        ModelError.Unavailable -> "$name can't run on this device."
    }

/** [bytes] as "30.5 MB" / "2.3 MB" / "512 KB". */
fun sizeText(bytes: Long): String =
    when {
        bytes >= MB -> "%.1f MB".format(java.util.Locale.ROOT, bytes / MB.toDouble())
        bytes >= KB -> "${(bytes + KB / 2) / KB} KB"
        else -> "$bytes B"
    }

private const val KB = 1000L
private const val MB = 1000L * 1000L
