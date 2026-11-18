package dev.mark.lineover.core.io.project

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import dev.mark.lineover.core.model.Project
import dev.mark.lineover.core.model.ProjectDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/** Result of an import. */
sealed interface ImportResult {
    /** [ids] in the order picked; [failed] sources could not be read. */
    data class Imported(
        val ids: List<Long>,
        val failed: Int,
    ) : ImportResult

    /** A .loproj project file that could not be opened, with the reason. */
    data class ProjectFileFailed(
        val reason: String,
    ) : ImportResult
}

/** A project's saved edit, or why it cannot be used. */
sealed interface DocumentLoad {
    data class Loaded(
        val document: ProjectDocument,
        /** True when nothing was saved yet (a new project). */
        val fresh: Boolean,
    ) : DocumentLoad

    /** The saved file cannot be read (e.g. made by a newer LineOver): it must not be overwritten. */
    data class Failed(
        val reason: String,
    ) : DocumentLoad
}

/** Everything the screens do with projects. Main-safe: file and decode work runs on IO. */
interface ProjectLibrary : ProjectDocuments {
    fun observeProjects(): Flow<List<Project>>

    fun observeRecent(): Flow<List<Project>>

    fun observe(id: Long): Flow<Project?>

    /** Photos from the system photo picker. */
    suspend fun importPicked(uris: List<Uri>): ImportResult

    /** A file chosen with ACTION_OPEN_DOCUMENT (RAW, TIFF, .loproj…). */
    suspend fun importDocument(uri: Uri): ImportResult

    suspend fun rename(
        id: Long,
        name: String,
    )

    /** Copies the project (a copied original is copied too); returns the new id. */
    suspend fun duplicate(id: Long): Long?

    /** Removes the project from the list; its files stay until [purge], so [restore] can undo it. */
    suspend fun delete(id: Long): Project?

    suspend fun restore(project: Project)

    /** Deletes the files of a deleted project and releases its URI permission when unused. */
    suspend fun purge(project: Project)

    suspend fun markOpened(id: Long)

    /**
     * Decodes [project]'s original for the canvas, directly at a long edge of at most
     * [maxEdge] px (never a full-size bitmap). Null when the format cannot be decoded yet
     * (TIFF before Stage 12, RAW before Stage 25) or the original is gone.
     */
    suspend fun decodePreview(
        project: Project,
        maxEdge: Int = PreviewDecoder.MAX_EDGE,
    ): PreviewImage?
}

/** Builds the project library; the database opens lazily on first use. */
object ProjectLibraries {
    fun create(context: Context): ProjectLibrary {
        val app = context.applicationContext
        val db = ProjectDatabase.build(app)
        val version =
            try {
                app.packageManager
                    .getPackageInfo(app.packageName, 0)
                    .versionName
                    .orEmpty()
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                ""
            }
        return AndroidProjectLibrary(
            resolver = app.contentResolver,
            repository = RoomProjectRepository(db.projects()),
            storage = ProjectStorage(app.filesDir, app.cacheDir),
            appVersion = version,
        )
    }
}

internal class AndroidProjectLibrary(
    private val resolver: ContentResolver,
    private val repository: ProjectRepository,
    private val storage: ProjectStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    appVersion: String = "",
    private val documents: AndroidProjectDocuments =
        AndroidProjectDocuments(resolver, repository, storage, clock, io, appVersion),
) : ProjectLibrary,
    ProjectDocuments by documents {
    private val thumbnailer = Thumbnailer(resolver)
    private val previewDecoder = PreviewDecoder(resolver)

    override fun observeProjects() = repository.observeProjects()

    override fun observeRecent() = repository.observeRecent()

    override fun observe(id: Long) = repository.observe(id)

    override suspend fun importPicked(uris: List<Uri>): ImportResult =
        withContext(io) {
            var failed = 0
            val ids =
                uris.mapNotNull { uri ->
                    importOne(uri, displayName(uri)).also { if (it == null) failed++ }
                }
            ImportResult.Imported(ids, failed)
        }

    override suspend fun importDocument(uri: Uri): ImportResult =
        withContext(io) {
            val name = displayName(uri)
            if (ProjectNames.isProjectFile(name)) return@withContext documents.importProjectFile(uri)
            val id = importOne(uri, name)
            ImportResult.Imported(listOfNotNull(id), if (id == null) 1 else 0)
        }

    /**
     * Keeps the original where it is when a persistable read permission can be taken
     * (photo picker and SAF both offer one); otherwise copies it into
     * projects/<id>/original. Either way the original is only ever read (rule R7).
     */
    private suspend fun importOne(
        uri: Uri,
        displayName: String?,
    ): Long? {
        val id = repository.create(ProjectNames.fromDisplayName(displayName), uri.toString(), clock())
        try {
            val source = if (persistReadPermission(uri)) uri else copyToStorage(uri, id)
            if (source != uri) repository.setSource(id, source.toString())
            val thumb = storage.thumb(id)
            repository.setThumb(id, if (thumbnailer.write(source, thumb)) thumb.absolutePath else null)
            return id
        } catch (_: FileNotFoundException) {
            discard(id)
            return null
        } catch (_: SecurityException) {
            discard(id)
            return null
        }
    }

    private suspend fun discard(id: Long) {
        repository.delete(id)
        storage.delete(id)
    }

    private fun persistReadPermission(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        return try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun copyToStorage(
        uri: Uri,
        id: Long,
    ): Uri {
        val target = storage.original(id)
        target.parentFile?.mkdirs()
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("cannot open $uri")
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        return Uri.fromFile(target)
    }

    private fun displayName(uri: Uri): String? = resolver.displayName(uri)

    override suspend fun rename(
        id: Long,
        name: String,
    ) = repository.rename(id, ProjectNames.clean(name), clock())

    override suspend fun duplicate(id: Long): Long? =
        withContext(io) {
            val project = repository.get(id) ?: return@withContext null
            val now = clock()
            val copyId = repository.create(ProjectNames.copyOf(project.name), project.sourceUri, now)
            val sourcePath = Uri.parse(project.sourceUri).takeIf { it.scheme == ContentResolver.SCHEME_FILE }?.path
            if (sourcePath != null && storage.owns(sourcePath)) {
                // A copied original belongs to one project directory: give the copy its own.
                val target = storage.original(copyId)
                target.parentFile?.mkdirs()
                File(sourcePath).copyTo(target, overwrite = true)
                repository.setSource(copyId, Uri.fromFile(target).toString())
            }
            val thumbPath = project.thumbPath
            if (thumbPath != null && File(thumbPath).isFile) {
                val target = storage.thumb(copyId)
                target.parentFile?.mkdirs()
                File(thumbPath).copyTo(target, overwrite = true)
                repository.setThumb(copyId, target.absolutePath)
            }
            copyId
        }

    override suspend fun delete(id: Long): Project? = repository.delete(id)

    override suspend fun restore(project: Project) = repository.restore(project)

    override suspend fun purge(project: Project) =
        withContext(io) {
            storage.delete(project.id)
            val uri = Uri.parse(project.sourceUri)
            if (uri.scheme == ContentResolver.SCHEME_CONTENT && repository.countBySource(project.sourceUri) == 0) {
                // Persisted grants are limited per app: release the ones no project uses.
                try {
                    resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    // Already gone (e.g. the photo was deleted): nothing to release.
                }
            }
        }

    override suspend fun markOpened(id: Long) = repository.markOpened(id, clock())

    override suspend fun decodePreview(
        project: Project,
        maxEdge: Int,
    ): PreviewImage? = withContext(io) { previewDecoder.decode(Uri.parse(project.sourceUri), maxEdge) }
}

/** The name a content URI shows in pickers, or its last path segment. */
internal fun ContentResolver.displayName(uri: Uri): String? {
    if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.lastPathSegment
    return try {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: SecurityException) {
        null
    } ?: uri.lastPathSegment
}
