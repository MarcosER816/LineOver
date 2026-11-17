package dev.mark.lineover.core.io.project

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Migrations of the project database, built from the committed schemas in core/io/schemas. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProjectDatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ProjectDatabase::class.java,
        )

    @Test
    fun migrate1To2KeepsProjectsAndSetsOpenedToModified() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO projects (id, name, source_uri, thumb_path, modified) " +
                    "VALUES (7, 'Beach', 'content://media/1', '/thumbs/7.jpg', 1700000000000)",
            )
            db.execSQL(
                "INSERT INTO projects (id, name, source_uri, thumb_path, modified) VALUES (8, 'Night', 'file:///x', NULL, 1600000000000)",
            )
        }

        // Validates the migrated schema against the exported v2 schema.
        helper.runMigrationsAndValidate(DB, 2, true, ProjectDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT id, name, opened, modified, thumb_path FROM projects ORDER BY id").use { c ->
                c.moveToFirst()
                assertEquals(7L, c.getLong(0))
                assertEquals("Beach", c.getString(1))
                assertEquals(1700000000000L, c.getLong(2))
                assertEquals("/thumbs/7.jpg", c.getString(4))
                c.moveToNext()
                assertEquals(1600000000000L, c.getLong(2))
                assertEquals(true, c.isNull(4))
            }
        }
    }

    @Test
    fun allMigrationsLeadToTheCurrentVersionAndRoomCanOpenIt() {
        helper.createDatabase(DB, 1).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db =
            Room
                .databaseBuilder(context, ProjectDatabase::class.java, DB)
                .addMigrations(*ProjectDatabase.ALL_MIGRATIONS)
                .allowMainThreadQueries()
                .build()
        try {
            // Opening runs every migration and validates against the entities.
            val projects = runBlocking { db.projects().observeByModified().first() }
            assertEquals(emptyList<ProjectEntity>(), projects)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
