package dev.mark.lineover.core.io.export

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Where an export goes (Stage 12). */
sealed interface ExportTarget {
    /** MediaStore, Pictures/LineOver (Gallery and Google Photos see it at once). */
    data object Pictures : ExportTarget

    /** A document the person picked with the system file picker (SAF). */
    data class Document(
        val uri: Uri,
    ) : ExportTarget
}

/**
 * Copies a finished file to its [ExportTarget]. A MediaStore entry stays pending (hidden)
 * until the copy is complete, and is removed when it fails.
 */
class ExportDestinations(
    private val resolver: ContentResolver,
) {
    /** Copies [file] to [target]; returns the content URI of the result. */
    fun publish(
        file: File,
        target: ExportTarget,
        displayName: String,
        mimeType: String,
    ): Uri =
        when (target) {
            is ExportTarget.Document -> {
                copy(file, target.uri)
                target.uri
            }

            ExportTarget.Pictures -> {
                val values =
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                        put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = resolver.insert(collection, values) ?: throw IOException("the gallery refused a new picture")
                try {
                    copy(file, uri)
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    uri
                } catch (e: IOException) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            }
        }

    private fun copy(
        file: File,
        uri: Uri,
    ) {
        val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("cannot write $uri")
        out.use { stream -> file.inputStream().use { it.copyTo(stream, BUFFER) } }
    }

    companion object {
        /** D1: exports go to Pictures/LineOver. */
        val RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/LineOver"
        private const val BUFFER = 1 shl 20
    }
}
