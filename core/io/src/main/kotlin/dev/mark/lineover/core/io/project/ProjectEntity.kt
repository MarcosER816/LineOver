package dev.mark.lineover.core.io.project

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.mark.lineover.core.model.Project

/** Row of the `projects` table (schema version 2). */
@Entity(
    tableName = "projects",
    indices = [Index("modified"), Index("opened")],
)
internal data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "source_uri") val sourceUri: String,
    @ColumnInfo(name = "thumb_path") val thumbPath: String?,
    val modified: Long,
    @ColumnInfo(defaultValue = "0") val opened: Long,
) {
    fun toProject() = Project(id, name, sourceUri, thumbPath, modified, opened)
}

internal fun Project.toEntity() = ProjectEntity(id, name, sourceUri, thumbPath, modified, opened)
