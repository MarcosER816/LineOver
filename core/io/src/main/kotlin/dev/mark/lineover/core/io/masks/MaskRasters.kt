package dev.mark.lineover.core.io.masks

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.roundToInt

/** A saved selection's pixels (0..1, row by row). */
class MaskRaster(
    val width: Int,
    val height: Int,
    val values: FloatArray,
)

/**
 * Selections saved as mask pixels (Stage 15): a mask made from a selection names one of these
 * files (MaskPaint.Raster), so it stays the same whatever happens to the image afterwards.
 * Values are kept to 16 bits (finer than the engine's R16F), deflate-compressed, named by their
 * content hash.
 */
interface MaskRasters {
    /** Stores [raster]; its name, or null when it cannot be written. Blocking. */
    fun save(raster: MaskRaster): String?

    /** The raster [name], or null when the store has no such file. Blocking. */
    fun load(name: String): MaskRaster?
}

/** [MaskRasters] in [dir] (the app's `files/masks`). */
class FileMaskRasters(
    private val dir: File,
) : MaskRasters {
    override fun save(raster: MaskRaster): String? {
        val bytes = encode(raster)
        val name =
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
                .take(HASH_CHARS) + SUFFIX
        return try {
            dir.mkdirs()
            val file = File(dir, name)
            if (!file.isFile) {
                val temp = File(dir, "$name.tmp")
                temp.writeBytes(bytes)
                if (!temp.renameTo(file)) return null
            }
            name
        } catch (e: IOException) {
            Log.w(TAG, "saving the selection $name failed", e)
            null
        }
    }

    override fun load(name: String): MaskRaster? {
        if (!NAME.matches(name)) return null
        val file = File(dir, name).takeIf { it.isFile } ?: return null
        return try {
            decode(file.readBytes())
        } catch (e: IOException) {
            Log.w(TAG, "reading the selection $name failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "LineOverMasks"
        private const val HASH_CHARS = 32
        private const val SUFFIX = ".lomask"
        private const val MAGIC = 0x4C4F4D53 // "LOMS"
        private const val MAX_CODE = 65535f
        val NAME = Regex("[0-9a-f]{32}\\.lomask")

        fun encode(raster: MaskRaster): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).apply {
                writeInt(MAGIC)
                writeInt(raster.width)
                writeInt(raster.height)
                flush()
            }
            DataOutputStream(DeflaterOutputStream(out)).use { z ->
                for (v in raster.values) z.writeShort((v.coerceIn(0f, 1f) * MAX_CODE).roundToInt())
            }
            return out.toByteArray()
        }

        fun decode(bytes: ByteArray): MaskRaster? {
            val data = DataInputStream(bytes.inputStream())
            if (data.readInt() != MAGIC) return null
            val w = data.readInt()
            val h = data.readInt()
            if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) return null
            val values = FloatArray(w * h)
            DataInputStream(InflaterInputStream(data)).use { z ->
                for (i in values.indices) values[i] = z.readUnsignedShort() / MAX_CODE
            }
            return MaskRaster(w, h, values)
        }

        private const val MAX_PIXELS = 64L * 1024 * 1024
    }
}
