package dev.mark.lineover.core.io.projectfile

import dev.mark.lineover.core.model.EditGraphJson
import kotlinx.serialization.json.JsonElement
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Hashing and streaming helpers of ProjectFile.

internal fun writeStored(
    zip: ZipOutputStream,
    name: String,
    original: () -> InputStream,
): String {
    // STORED entries need size and CRC up front: one pass to measure, one to write.
    val crc = java.util.zip.CRC32()
    val digest = MessageDigest.getInstance("SHA-256")
    val size = original().use { input -> copy(input, null, digest, crc) }
    val entry =
        ZipEntry(name).apply {
            method = ZipEntry.STORED
            this.size = size
            compressedSize = size
            this.crc = crc.value
        }
    zip.putNextEntry(entry)
    original().use { it.copyTo(zip, ProjectFile.BUFFER) }
    zip.closeEntry()
    return digest.digest().hex()
}

internal fun ZipFile.text(name: String): String? = getEntry(name)?.let { e -> getInputStream(e).use { String(it.readBytes()) } }

internal fun parse(text: String): JsonElement =
    try {
        EditGraphJson.format.parseToJsonElement(text)
    } catch (e: kotlinx.serialization.SerializationException) {
        throw ProjectFileException("The project file is damaged", e)
    }

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
