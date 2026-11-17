package dev.mark.lineover.core.model

/**
 * A LineOver project: one source image plus (from Stage 4) its edit graph.
 *
 * @param sourceUri where the original lives: a `content://` URI with a persisted read
 *   permission, or a `file://` URI of a copy in app storage. The original is never
 *   modified (rule R7).
 * @param thumbPath absolute path of a JPEG thumbnail (longest side <= 512 px), or null
 *   when the source could not be decoded (e.g. TIFF before Stage 12, RAW before Stage 25)
 * @param modified last change, epoch milliseconds
 * @param opened last time the project was opened (or created), epoch milliseconds
 */
data class Project(
    val id: Long,
    val name: String,
    val sourceUri: String,
    val thumbPath: String?,
    val modified: Long,
    val opened: Long,
)
