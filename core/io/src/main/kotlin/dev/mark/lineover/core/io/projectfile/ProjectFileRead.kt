package dev.mark.lineover.core.io.projectfile

import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.EditGraphJson
import dev.mark.lineover.core.model.ProjectDocument
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile

// Reading and checking the parts of a .loproj (ProjectFile.read).

internal fun open(file: File): ZipFile =
    try {
        ZipFile(file)
    } catch (e: ZipException) {
        throw ProjectFileException("Not a complete LineOver project file", e)
    }

/** The manifest as JSON, checked to be one of ours (the format name). */
internal fun manifestOf(zip: ZipFile): JsonObject {
    val text = zip.text(ProjectFile.MANIFEST) ?: throw ProjectFileException("Not a LineOver project file (no manifest)")
    val json = parse(text).jsonObject
    if ((json["format"] as? JsonPrimitive)?.content != ProjectFile.FORMAT) throw ProjectFileException("Not a LineOver project file")
    return json
}

internal fun decodeManifest(json: JsonObject): ProjectManifest =
    EditGraphJson.format.decodeFromJsonElement(ProjectManifest.serializer(), json)

/** Every entry but the manifest and the original, each checked against its SHA-256. */
internal fun verifiedEntries(
    zip: ZipFile,
    hashes: Map<String, String>,
): Map<String, ByteArray> {
    val files = mutableMapOf<String, ByteArray>()
    for (entry in zip.entries().toList().filter { !it.isDirectory && it.name != ProjectFile.MANIFEST && it.name != ProjectFile.ORIGINAL }) {
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        val expected = hashes[entry.name]
        if (expected != null && expected != sha256(bytes)) throw ProjectFileException("The project file is damaged (${entry.name})")
        files[entry.name] = bytes
    }
    return files
}

internal fun documentOf(
    json: ProjectJson,
    created: Long,
): ProjectDocument {
    json.document?.let { return EditGraphJson.format.decodeFromJsonElement(ProjectDocument.serializer(), it) }
    val graph = json.graph ?: throw ProjectFileException("The project file has no edit")
    return ProjectDocument.of(EditGraphJson.format.decodeFromJsonElement(EditGraph.serializer(), graph), created)
}

/** Copies the embedded original to [target], checking its SHA-256. */
internal fun extract(
    zip: ZipFile,
    expected: String?,
    target: File,
) {
    val entry = zip.getEntry(ProjectFile.ORIGINAL) ?: throw ProjectFileException("The project file has no original")
    target.parentFile?.mkdirs()
    val digest = MessageDigest.getInstance("SHA-256")
    zip.getInputStream(entry).use { input -> target.outputStream().use { output -> copy(input, output, digest) } }
    if (expected != null && expected != digest.digest().hex()) {
        target.delete()
        throw ProjectFileException("The project file is damaged (original)")
    }
}

internal fun copy(
    input: InputStream,
    output: OutputStream?,
    digest: MessageDigest,
    crc: java.util.zip.CRC32? = null,
): Long {
    val buffer = ByteArray(ProjectFile.BUFFER)
    var size = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return size
        digest.update(buffer, 0, n)
        crc?.update(buffer, 0, n)
        output?.write(buffer, 0, n)
        size += n
    }
}

internal fun Map<String, ByteArray>.under(prefix: String): Map<String, ByteArray> =
    filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }

internal fun parseBytes(bytes: ByteArray): JsonElement = parse(String(bytes))
