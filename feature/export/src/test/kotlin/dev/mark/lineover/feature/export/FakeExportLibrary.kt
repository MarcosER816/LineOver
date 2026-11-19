package dev.mark.lineover.feature.export

import android.net.Uri
import dev.mark.lineover.core.io.project.DocumentLoad
import dev.mark.lineover.core.io.project.ImportResult
import dev.mark.lineover.core.io.project.PreviewImage
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.Project
import dev.mark.lineover.core.model.ProjectDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** One project in memory; the preview is whatever the test sets. */
class FakeExportLibrary(
    project: Project? = PROJECT,
    private val preview: PreviewImage? = null,
) : ProjectLibrary {
    val project = MutableStateFlow(project)
    var opened = 0
        private set

    override fun observeProjects(): Flow<List<Project>> = project.map(::listOfNotNull)

    override fun observeRecent(): Flow<List<Project>> = observeProjects()

    override fun observe(id: Long): Flow<Project?> = project

    override suspend fun importPicked(uris: List<Uri>) = ImportResult.Imported(emptyList(), uris.size)

    override suspend fun importDocument(uri: Uri) = ImportResult.Imported(emptyList(), 1)

    override suspend fun rename(
        id: Long,
        name: String,
    ) = Unit

    override suspend fun duplicate(id: Long): Long? = null

    override suspend fun delete(id: Long): Project? = null

    override suspend fun restore(project: Project) = Unit

    override suspend fun purge(project: Project) = Unit

    override suspend fun markOpened(id: Long) {
        opened++
    }

    override suspend fun decodePreview(
        project: Project,
        maxEdge: Int,
    ): PreviewImage? = preview

    /** The saved document (null: never saved) and what the editor did with the file. */
    var saved: ProjectDocument? = null
    var savedThumbnails: Map<String, ByteArray> = emptyMap()
    var saves = 0
        private set
    var loadFailure: String? = null
    var editing = false
        private set

    override suspend fun loadDocument(project: Project): DocumentLoad =
        loadFailure?.let { DocumentLoad.Failed(it) }
            ?: saved?.let { DocumentLoad.Loaded(it, fresh = false) }
            ?: DocumentLoad.Loaded(ProjectDocument.of(EditGraph()), fresh = true)

    override suspend fun saveDocument(
        project: Project,
        document: ProjectDocument,
        thumbnails: Map<String, ByteArray>,
    ) {
        saved = document
        savedThumbnails = thumbnails
        saves++
    }

    override suspend fun beginEditing(id: Long) {
        editing = true
    }

    override suspend fun endEditing(id: Long) {
        editing = false
    }

    override suspend fun recoverInterrupted(): List<Project> = emptyList()

    override suspend fun exportProjectFile(
        id: Long,
        target: Uri,
    ) = false

    companion object {
        val PROJECT = Project(1, "Harbour at dusk", "content://photo/1", null, 0, 0)
    }
}
