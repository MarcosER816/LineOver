package dev.mark.lineover.core.io.project

import android.content.ContentResolver
import android.net.Uri
import dev.mark.lineover.core.io.projectfile.AtomicFiles
import dev.mark.lineover.core.io.projectfile.ProjectContent
import dev.mark.lineover.core.io.projectfile.ProjectFile
import dev.mark.lineover.core.io.projectfile.SourceRef
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.Project
import dev.mark.lineover.core.model.ProjectDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A project's edit on disk: its .loproj, crash recovery and project files to share (Stage 8). */
interface ProjectDocuments {
    /** The saved edit of [project] (versions, histories, snapshots); crash leftovers are recovered first. */
    suspend fun loadDocument(project: Project): DocumentLoad

    /**
     * Saves [document] to the project's .loproj atomically (temp file, fsync, rename).
     * [thumbnails] ("current.jpg", "<version id>.jpg") go into the file; "current.jpg" also
     * becomes the Home thumbnail.
     */
    suspend fun saveDocument(
        project: Project,
        document: ProjectDocument,
        thumbnails: Map<String, ByteArray> = emptyMap(),
    )

    /** Marks [id] as being edited; [endEditing] clears it. A marker left behind means a crash or a kill. */
    suspend fun beginEditing(id: Long)

    suspend fun endEditing(id: Long)

    /** Projects whose last editing session did not end normally; their saved files are recovered. */
    suspend fun recoverInterrupted(): List<Project>

    /** Writes a shareable .loproj of [id] (original embedded) to [target]; false when it failed. */
    suspend fun exportProjectFile(
        id: Long,
        target: Uri,
    ): Boolean
}

/** [ProjectDocuments] in files/projects/<id>/project.loproj, written atomically. */
internal class AndroidProjectDocuments(
    private val resolver: ContentResolver,
    private val repository: ProjectRepository,
    private val storage: ProjectStorage,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher,
    private val appVersion: String,
) : ProjectDocuments {
    private val saving = Mutex()
    private val created = ConcurrentHashMap<Long, Long>()
    private val thumbnailer = Thumbnailer(resolver)

    override suspend fun loadDocument(project: Project): DocumentLoad =
        withContext(io) {
            val file = storage.projectFile(project.id)
            val readable = AtomicFiles.recover(file, ProjectFile::isValid)
            if (readable == null) {
                if (file.exists()) return@withContext DocumentLoad.Failed(failure(file))
                created[project.id] = clock()
                return@withContext DocumentLoad.Loaded(ProjectDocument.of(EditGraph(), clock()), fresh = true)
            }
            try {
                val content = ProjectFile.read(readable)
                created[project.id] = content.created
                DocumentLoad.Loaded(content.document, fresh = false)
            } catch (e: IOException) {
                DocumentLoad.Failed(e.message ?: "The project file cannot be read")
            }
        }

    /** Why [file] is not usable (a newer format says so; anything else is damage). */
    private fun failure(file: File): String =
        try {
            ProjectFile.read(file)
            "The project file cannot be read"
        } catch (e: IOException) {
            e.message ?: "The project file cannot be read"
        }

    override suspend fun saveDocument(
        project: Project,
        document: ProjectDocument,
        thumbnails: Map<String, ByteArray>,
    ) = withContext(io) {
        saving.withLock {
            val now = clock()
            val content =
                ProjectContent(
                    name = project.name,
                    created = created.getOrPut(project.id) { now },
                    modified = now,
                    document = document,
                    source = SourceRef(SourceRef.REFERENCE, project.sourceUri),
                    thumbnails = thumbnails,
                    app = appVersion,
                )
            AtomicFiles.write(storage.projectFile(project.id)) { out -> ProjectFile.write(out, content) }
            thumbnails[CURRENT_THUMB]?.let { jpeg -> setEditedThumb(project.id, jpeg, now) }
            repository.setModified(project.id, now)
        }
    }

    private suspend fun setEditedThumb(
        id: Long,
        jpeg: ByteArray,
        stamp: Long,
    ) {
        val thumb = storage.editedThumb(id, stamp)
        AtomicFiles.write(thumb) { it.write(jpeg) }
        AtomicFiles.backup(thumb).delete()
        repository.setThumb(id, thumb.absolutePath)
        storage.dir(id).listFiles()?.forEach { f ->
            if (f.name.startsWith("thumb-") && f.name.endsWith(".jpg") && f != thumb) f.delete()
        }
    }

    override suspend fun beginEditing(id: Long) =
        withContext(io) {
            val marker = storage.editingMarker(id)
            marker.parentFile?.mkdirs()
            marker.writeText(clock().toString())
        }

    override suspend fun endEditing(id: Long) =
        withContext(io) {
            storage.editingMarker(id).delete()
            Unit
        }

    override suspend fun recoverInterrupted(): List<Project> =
        withContext(io) {
            storage.ids().filter { storage.editingMarker(it).exists() }.mapNotNull { id ->
                // The last autosave (or a save cut short between its renames) is put back in place.
                AtomicFiles.recover(storage.projectFile(id), ProjectFile::isValid)
                storage.editingMarker(id).delete()
                repository.get(id)
            }
        }

    override suspend fun exportProjectFile(
        id: Long,
        target: Uri,
    ): Boolean =
        withContext(io) {
            val project = repository.get(id) ?: return@withContext false
            val source = Uri.parse(project.sourceUri)
            val saved = storage.projectFile(id).takeIf { ProjectFile.isValid(it) }?.let { ProjectFile.read(it) }
            val content =
                ProjectContent(
                    name = project.name,
                    created = saved?.created ?: project.modified,
                    modified = project.modified,
                    document = saved?.document ?: ProjectDocument.of(EditGraph(), project.modified),
                    source = SourceRef(SourceRef.EMBEDDED, displayName = resolver.displayName(source)),
                    thumbnails = saved?.thumbnails.orEmpty(),
                    paint = saved?.paint.orEmpty(),
                    app = appVersion,
                )
            try {
                val out = resolver.openOutputStream(target, "wt") ?: return@withContext false
                out.use { stream ->
                    ProjectFile.write(stream, content) { resolver.openInputStream(source) ?: throw FileNotFoundException("$source") }
                }
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }

    /** Opens a shared .loproj as a new project: its original (embedded or still reachable) and its edit. */
    suspend fun importProjectFile(uri: Uri): ImportResult {
        val temp = storage.importTemp(clock())
        return try {
            copyToTemp(uri, temp)
            ImportResult.Imported(listOf(createFrom(ProjectFile.read(temp), temp)), 0)
        } catch (e: IOException) {
            ImportResult.ProjectFileFailed(e.message ?: "The project file cannot be opened")
        } catch (_: SecurityException) {
            ImportResult.ProjectFileFailed("The file cannot be opened")
        } finally {
            temp.delete()
        }
    }

    private fun copyToTemp(
        uri: Uri,
        temp: File,
    ) {
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("The file cannot be opened")
        temp.parentFile?.mkdirs()
        input.use { source -> temp.outputStream().use { source.copyTo(it) } }
    }

    /** A new project from a read project file; nothing is left behind if it fails. */
    private suspend fun createFrom(
        content: ProjectContent,
        file: File,
    ): Long {
        val id = repository.create(ProjectNames.clean(content.name), "", clock())
        try {
            val sourceUri = sourceOf(content, file, id)
            repository.setSource(id, sourceUri.toString())
            created[id] = content.created
            saveDocument(requireNotNull(repository.get(id)), content.document, content.thumbnails)
            if (content.thumbnails[CURRENT_THUMB] == null) {
                val thumb = storage.thumb(id)
                repository.setThumb(id, if (thumbnailer.write(sourceUri, thumb)) thumb.absolutePath else null)
            }
            return id
        } catch (e: IOException) {
            discard(id)
            throw e
        }
    }

    /** The embedded original copied into the project, or the referenced one if it is on this device. */
    private fun sourceOf(
        content: ProjectContent,
        file: File,
        id: Long,
    ): Uri {
        if (content.source.kind == SourceRef.EMBEDDED) {
            ProjectFile.read(file, extractOriginal = storage.original(id))
            return Uri.fromFile(storage.original(id))
        }
        val reference = content.source.uri?.let(Uri::parse)
        if (reference == null || !readable(reference)) {
            throw dev.mark.lineover.core.io.projectfile
                .ProjectFileException("The project's original photo is not on this device")
        }
        return reference
    }

    private fun readable(uri: Uri): Boolean =
        try {
            resolver.openInputStream(uri)?.use { true } ?: false
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private suspend fun discard(id: Long) {
        repository.delete(id)
        storage.delete(id)
    }

    private companion object {
        const val CURRENT_THUMB = "current.jpg"
    }
}
