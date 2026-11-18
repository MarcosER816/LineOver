package dev.mark.lineover.core.io.project

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY modified DESC, id DESC")
    fun observeByModified(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY opened DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: Long): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: Long): ProjectEntity?

    @Insert
    suspend fun insert(project: ProjectEntity): Long

    /** Restores a deleted row with its original id (undo). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("UPDATE projects SET name = :name, modified = :now WHERE id = :id")
    suspend fun rename(
        id: Long,
        name: String,
        now: Long,
    )

    @Query("UPDATE projects SET modified = :now WHERE id = :id")
    suspend fun setModified(
        id: Long,
        now: Long,
    )

    @Query("UPDATE projects SET thumb_path = :thumbPath WHERE id = :id")
    suspend fun setThumb(
        id: Long,
        thumbPath: String?,
    )

    @Query("UPDATE projects SET source_uri = :sourceUri WHERE id = :id")
    suspend fun setSource(
        id: Long,
        sourceUri: String,
    )

    @Query("UPDATE projects SET opened = :now WHERE id = :id")
    suspend fun markOpened(
        id: Long,
        now: Long,
    )

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM projects WHERE source_uri = :sourceUri")
    suspend fun countBySource(sourceUri: String): Int
}
