package dev.mark.lineover.feature.editor

import android.net.Uri
import dev.mark.lineover.core.io.project.ImportResult
import dev.mark.lineover.core.io.project.PreviewImage
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.core.model.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** One project in memory; the preview is whatever the test sets. */
class FakeEditorLibrary(
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

    companion object {
        val PROJECT = Project(1, "Harbour at dusk", "content://photo/1", null, 0, 0)
    }
}
