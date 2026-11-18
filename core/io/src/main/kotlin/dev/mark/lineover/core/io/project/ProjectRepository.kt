package dev.mark.lineover.core.io.project

import dev.mark.lineover.core.model.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Projects of the user. All functions are main-safe (Room runs queries off the main thread). */
interface ProjectRepository {
    /** All projects, most recently modified first (the Projects tab). */
    fun observeProjects(): Flow<List<Project>>

    /** The most recently opened projects (the Recent tab). */
    fun observeRecent(limit: Int = RECENT_LIMIT): Flow<List<Project>>

    fun observe(id: Long): Flow<Project?>

    suspend fun get(id: Long): Project?

    /** Creates a project; [now] is both its creation and its "opened" time. */
    suspend fun create(
        name: String,
        sourceUri: String,
        now: Long,
    ): Long

    suspend fun setThumb(
        id: Long,
        thumbPath: String?,
    )

    suspend fun setSource(
        id: Long,
        sourceUri: String,
    )

    /** The edit changed (an autosave): the project moves to the top of the Projects tab. */
    suspend fun setModified(
        id: Long,
        now: Long,
    )

    suspend fun rename(
        id: Long,
        name: String,
        now: Long,
    )

    suspend fun markOpened(
        id: Long,
        now: Long,
    )

    /** Removes the row and returns it, so the deletion can be undone with [restore]. */
    suspend fun delete(id: Long): Project?

    suspend fun restore(project: Project)

    /** How many projects use [sourceUri] (a shared original is kept until the last one goes). */
    suspend fun countBySource(sourceUri: String): Int

    companion object {
        const val RECENT_LIMIT = 20
    }
}

internal class RoomProjectRepository(
    private val dao: ProjectDao,
) : ProjectRepository {
    override fun observeProjects() = dao.observeByModified().map { rows -> rows.map(ProjectEntity::toProject) }

    override fun observeRecent(limit: Int) = dao.observeRecent(limit).map { rows -> rows.map(ProjectEntity::toProject) }

    override fun observe(id: Long) = dao.observe(id).map { it?.toProject() }

    override suspend fun get(id: Long) = dao.get(id)?.toProject()

    override suspend fun create(
        name: String,
        sourceUri: String,
        now: Long,
    ) = dao.insert(ProjectEntity(name = name, sourceUri = sourceUri, thumbPath = null, modified = now, opened = now))

    override suspend fun setThumb(
        id: Long,
        thumbPath: String?,
    ) = dao.setThumb(id, thumbPath)

    override suspend fun setSource(
        id: Long,
        sourceUri: String,
    ) = dao.setSource(id, sourceUri)

    override suspend fun setModified(
        id: Long,
        now: Long,
    ) = dao.setModified(id, now)

    override suspend fun rename(
        id: Long,
        name: String,
        now: Long,
    ) = dao.rename(id, name, now)

    override suspend fun markOpened(
        id: Long,
        now: Long,
    ) = dao.markOpened(id, now)

    override suspend fun delete(id: Long): Project? {
        val row = dao.get(id) ?: return null
        dao.delete(id)
        return row.toProject()
    }

    override suspend fun restore(project: Project) = dao.upsert(project.toEntity())

    override suspend fun countBySource(sourceUri: String) = dao.countBySource(sourceUri)
}
