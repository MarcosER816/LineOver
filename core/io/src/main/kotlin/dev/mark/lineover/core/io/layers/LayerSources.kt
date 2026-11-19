package dev.mark.lineover.core.io.layers

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import dev.mark.lineover.core.io.source.SourceReader
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A photo imported as a layer (Stage 13): its file name (the graph's `source`) and its size. */
data class LayerSource(
    val name: String,
    val width: Int,
    val height: Int,
)

/**
 * The images of raster layers and smart objects (Stage 13). A layer never points at the
 * person's photo directly (it may be moved or deleted): importing copies it into the app's
 * own storage under a content hash, so the same photo imported twice is stored once and the
 * graph names it by that file name.
 */
interface LayerSources {
    /** Copies the photo at [uri] into the store; null when it cannot be read as an image. Blocking. */
    fun import(uri: String): LayerSource?

    /** The stored file of [name], or null when the store has no such file. */
    fun file(name: String): File?
}

/** [LayerSources] in [dir] (the app's `files/layers`). */
class FileLayerSources(
    private val resolver: ContentResolver,
    private val dir: File,
) : LayerSources {
    override fun import(uri: String): LayerSource? {
        val source = Uri.parse(uri)
        val reader = SourceReader(resolver)
        // Read the size first: anything the decoder cannot open is refused before it is copied.
        val original = reader.info(source) ?: return null
        val extension = extensionOf(original.mimeType)
        dir.mkdirs()
        val temp = File.createTempFile("import", ".tmp", dir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val copied =
                resolver.openInputStream(source)?.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                    }
                    true
                } ?: false
            if (!copied) return null
            val name = digest.digest().joinToString("") { "%02x".format(it) }.take(HASH_CHARS) + "." + extension
            val target = File(dir, name)
            if (target.isFile) {
                temp.delete()
            } else if (!temp.renameTo(target)) {
                return null
            }
            return LayerSource(name, original.width, original.height)
        } catch (e: IOException) {
            Log.w(TAG, "import of $uri failed", e)
            return null
        } catch (e: SecurityException) {
            Log.w(TAG, "import of $uri failed", e)
            return null
        } finally {
            temp.delete()
        }
    }

    override fun file(name: String): File? {
        if (!NAME.matches(name)) return null
        return File(dir, name).takeIf { it.isFile }
    }

    companion object {
        private const val TAG = "LineOverLayers"
        private const val BUFFER = 64 * 1024
        private const val HASH_CHARS = 32

        /** What a stored name looks like (no path separators can reach the file system). */
        val NAME = Regex("[0-9a-f]{32}\\.[a-z0-9]{2,5}")

        fun extensionOf(mimeType: String?): String =
            when (mimeType?.lowercase()) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/heic", "image/heif" -> "heic"
                "image/avif" -> "avif"
                "image/tiff" -> "tiff"
                else -> "jpg"
            }
    }
}
