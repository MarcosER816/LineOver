package dev.mark.lineover.core.io.projectfile

import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.EditGraphJson
import dev.mark.lineover.core.model.OpParams
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ProjectMigrationsTest {
    private val graph =
        EditGraphJson.format.parseToJsonElement(
            EditGraphJson.encode(EditGraph().withBackgroundOps(listOf(OpParams.Exposure(1f)))),
        )

    private fun v(version: Int) =
        ProjectJson(
            JsonObject(mapOf("format" to JsonPrimitive(ProjectFile.FORMAT), "schemaVersion" to JsonPrimitive(version))),
            graph,
            null,
        )

    @Test
    fun v1ToV1IsANoOp() {
        assertEquals(1, ProjectFile.SCHEMA_VERSION)
        val file = v(1)
        assertEquals(file, ProjectMigrations.DEFAULT.migrate(file))
    }

    @Test
    fun stepsRunInOrderUpToTheCurrentVersion() {
        val trail = mutableListOf<Int>()

        fun step(from: Int) =
            ProjectMigration(from) { json ->
                trail += from
                json.copy(manifest = JsonObject(json.manifest + ("trail" to JsonArray(trail.map { JsonPrimitive(it) }))))
            }
        // Listed out of order on purpose: the version decides.
        val migrations = ProjectMigrations(listOf(step(2), step(1), step(3)), current = 4)
        val migrated = migrations.migrate(v(1))
        assertEquals(listOf(1, 2, 3), trail)
        assertEquals(4, migrated.schemaVersion)
        assertEquals(graph, migrated.graph)
        trail.clear()
        migrations.migrate(v(3))
        assertEquals(listOf(3), trail)
    }

    @Test
    fun aMissingStepIsAnError() {
        try {
            ProjectMigrations(listOf(ProjectMigration(1) { it }), current = 3).migrate(v(1))
            fail("version 2 has no step")
        } catch (e: ProjectFileException) {
            assertEquals("No upgrade for project format 2", e.message)
        }
    }
}
