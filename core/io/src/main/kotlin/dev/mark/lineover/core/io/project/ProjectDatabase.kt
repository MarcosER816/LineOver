package dev.mark.lineover.core.io.project

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Project database. Every version's schema is exported to core/io/schemas and covered
 * by ProjectDatabaseMigrationTest; add a migration for every new version, never a
 * destructive fallback (users' projects must survive updates).
 */
@Database(entities = [ProjectEntity::class], version = 2, exportSchema = true)
internal abstract class ProjectDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao

    companion object {
        const val NAME = "projects.db"

        /** v2 adds `opened` (Recent tab); existing projects count as opened when last modified. */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE projects ADD COLUMN opened INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE projects SET opened = modified")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_projects_modified ON projects (modified)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_projects_opened ON projects (opened)")
                }
            }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)

        @Suppress("SpreadOperator") // runs once, on a two-element array
        fun build(context: Context): ProjectDatabase =
            Room
                .databaseBuilder(context, ProjectDatabase::class.java, NAME)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
    }
}
