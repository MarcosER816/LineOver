package dev.mark.lineover.core.io.projectfile

import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.EditGraphJson
import dev.mark.lineover.core.model.ProjectDocument
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Where a project's original is: inside the file ([EMBEDDED], entry "original") or a
 * reference to it ([REFERENCE]: a content:// URI with a persisted grant or a file:// copy in
 * app storage). Autosaves reference the original; shared project files embed it.
 */
@Serializable
data class SourceRef(
    val kind: String,
    val uri: String? = null,
    val displayName: String? = null,
) {
    companion object {
        const val EMBEDDED = "embedded"
        const val REFERENCE = "reference"
    }
}

/** manifest.json: what the file is, its schema version and a SHA-256 of every other entry. */
@Serializable
data class ProjectManifest(
    val format: String = ProjectFile.FORMAT,
    val schemaVersion: Int = ProjectFile.SCHEMA_VERSION,
    val app: String = "",
    val name: String,
    val created: Long,
    val modified: Long,
    val source: SourceRef,
    val files: Map<String, String> = emptyMap(),
)

/** A project file's content. [paint] tiles are zstd frames written by the engine (Stage 18). */
class ProjectContent(
    val name: String,
    val created: Long,
    val modified: Long,
    val document: ProjectDocument,
    val source: SourceRef,
    /** Entry name under thumbnails/ ("current.jpg", "v2.jpg") to JPEG bytes. */
    val thumbnails: Map<String, ByteArray> = emptyMap(),
    /** Entry name under paint/ ("<layer>/<x>_<y>.zst") to a compressed tile. */
    val paint: Map<String, ByteArray> = emptyMap(),
    val app: String = "",
) {
    val graph: EditGraph get() = document.graph
}

/** A project file that cannot be read, with a reason the user can be told. */
class ProjectFileException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * The .loproj format: a ZIP of manifest.json (schema version, SHA-256 of each entry),
 * graph.json (the active version's edit graph), document.json (versions with their undo
 * histories, snapshots), the original or a [SourceRef], paint/ tiles and thumbnails/.
 * Never pixels per history step: history entries are graphs.
 */
object ProjectFile {
    const val FORMAT = "lineover-project"
    const val SCHEMA_VERSION = 1
    const val EXTENSION = "loproj"
    const val MIME_TYPE = "application/octet-stream"

    const val MANIFEST = "manifest.json"
    const val GRAPH = "graph.json"
    const val DOCUMENT = "document.json"
    const val ORIGINAL = "original"
    const val PAINT = "paint/"
    const val THUMBNAILS = "thumbnails/"

    /**
     * Writes [content] to [out]. [original] (embedded sources only) streams the original into
     * the "original" entry; it is stored, not deflated (photos are already compressed).
     */
    fun write(
        out: OutputStream,
        content: ProjectContent,
        original: (() -> InputStream)? = null,
    ) {
        require((content.source.kind == SourceRef.EMBEDDED) == (original != null)) { "embedded sources need the original" }
        val entries = linkedMapOf<String, ByteArray>()
        entries[GRAPH] = EditGraphJson.encode(content.graph).toByteArray()
        entries[DOCUMENT] = EditGraphJson.encodeDocument(content.document).toByteArray()
        content.thumbnails.forEach { (name, bytes) -> entries[THUMBNAILS + name] = bytes }
        content.paint.forEach { (name, bytes) -> entries[PAINT + name] = bytes }
        val zip = ZipOutputStream(out)
        val hashes = linkedMapOf<String, String>()
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
            hashes[name] = sha256(bytes)
        }
        if (original != null) hashes[ORIGINAL] = writeStored(zip, ORIGINAL, original)
        val manifest =
            ProjectManifest(
                app = content.app,
                name = content.name,
                created = content.created,
                modified = content.modified,
                source = content.source,
                files = hashes,
            )
        // Last: a file cut short anywhere before this has no manifest and is rejected.
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(EditGraphJson.format.encodeToString(ProjectManifest.serializer(), manifest).toByteArray())
        zip.closeEntry()
        zip.finish()
        zip.flush()
    }

    /**
     * Reads [file] and checks it: a ZIP that opens, a manifest of this format whose schema is
     * not newer than this app's, entries matching their SHA-256. Older schemas are migrated
     * ([ProjectMigrations]). [extractOriginal], when given, receives an embedded original.
     */
    fun read(
        file: File,
        extractOriginal: File? = null,
        migrations: ProjectMigrations = ProjectMigrations.DEFAULT,
    ): ProjectContent =
        open(file).use { zip ->
            val manifestObject = manifestOf(zip)
            val hashes = decodeManifest(manifestObject).files
            val files = verifiedEntries(zip, hashes)
            val migrated =
                migrations.migrate(
                    ProjectJson(manifestObject, files[GRAPH]?.let(::parseBytes), files[DOCUMENT]?.let(::parseBytes)),
                )
            val manifest = decodeManifest(migrated.manifest)
            val document = documentOf(migrated, manifest.created)
            if (extractOriginal != null && manifest.source.kind == SourceRef.EMBEDDED) extract(zip, hashes[ORIGINAL], extractOriginal)
            ProjectContent(
                name = manifest.name,
                created = manifest.created,
                modified = manifest.modified,
                document = document,
                source = manifest.source,
                thumbnails = files.under(THUMBNAILS),
                paint = files.under(PAINT),
                app = manifest.app,
            )
        }

    /** True when [file] reads as a complete, intact project file. */
    fun isValid(file: File): Boolean =
        try {
            file.isFile && read(file).let { true }
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }

    internal const val BUFFER = 64 * 1024
}
