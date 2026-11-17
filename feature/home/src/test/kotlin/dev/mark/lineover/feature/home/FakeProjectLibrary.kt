package dev.mark.lineover.feature.home

import android.net.Uri
import dev.mark.lineover.core.io.project.ImportResult
import dev.mark.lineover.core.io.project.PreviewImage
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.core.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory ProjectLibrary for ViewModel tests. */
class FakeProjectLibrary : ProjectLibrary {
    val projects = MutableStateFlow<List<Project>>(emptyList())
    val purged = mutableListOf<Project>()
    var nextImport: ImportResult = ImportResult.Imported(emptyList(), 0)
    private var nextId = 100L

    fun add(
        name: String,
        modified: Long,
    ): Project = Project(nextId++, name, "content://photo/$name", null, modified, modified).also { projects.value += it }

    override fun observeProjects() = projects.map { list -> list.sortedByDescending { it.modified } }

    override fun observeRecent() = projects.map { list -> list.sortedByDescending { it.opened } }

    override fun observe(id: Long) = projects.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun importPicked(uris: List<Uri>) = nextImport

    override suspend fun importDocument(uri: Uri) = nextImport

    override suspend fun rename(
        id: Long,
        name: String,
    ) {
        projects.value = projects.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    override suspend fun duplicate(id: Long): Long? {
        val original = projects.value.firstOrNull { it.id == id } ?: return null
        return add("${original.name} copy", original.modified + 1).id
    }

    override suspend fun delete(id: Long): Project? {
        val project = projects.value.firstOrNull { it.id == id } ?: return null
        projects.value -= project
        return project
    }

    override suspend fun restore(project: Project) {
        projects.value += project
    }

    override suspend fun purge(project: Project) {
        purged += project
    }

    override suspend fun markOpened(id: Long) = Unit

    override suspend fun decodePreview(
        project: Project,
        maxEdge: Int,
    ): PreviewImage? = null
}
