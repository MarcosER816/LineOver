package dev.mark.lineover.core.io.projectfile

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** The JSON parts of a project file, as migrations see them (before decoding). */
data class ProjectJson(
    val manifest: JsonObject,
    val graph: JsonElement?,
    val document: JsonElement?,
) {
    val schemaVersion: Int get() = (manifest["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: 1

    fun withSchemaVersion(version: Int): ProjectJson = copy(manifest = JsonObject(manifest + ("schemaVersion" to JsonPrimitive(version))))
}

/** One schema step: turns a file of version [from] into version [from] + 1. */
class ProjectMigration(
    val from: Int,
    val migrate: (ProjectJson) -> ProjectJson,
)

/**
 * Brings project files of older schemas up to [current], one step at a time, before they
 * are decoded. A step is added for every schema change and never edited afterwards; files
 * newer than this app are refused (they may hold edits this version cannot keep).
 */
class ProjectMigrations(
    private val steps: List<ProjectMigration>,
    val current: Int = ProjectFile.SCHEMA_VERSION,
) {
    init {
        require(steps.map { it.from }.distinct().size == steps.size) { "two migrations from the same version" }
    }

    fun migrate(json: ProjectJson): ProjectJson {
        var file = json
        if (file.schemaVersion > current) {
            throw ProjectFileException(
                "This project was saved by a newer LineOver (format ${file.schemaVersion}); update the app to open it",
            )
        }
        while (file.schemaVersion < current) {
            val from = file.schemaVersion
            val step = steps.firstOrNull { it.from == from } ?: throw ProjectFileException("No upgrade for project format $from")
            file = step.migrate(file).withSchemaVersion(from + 1)
        }
        return file
    }

    companion object {
        /** Schema 1 is the first: nothing to migrate yet. */
        val DEFAULT = ProjectMigrations(emptyList())
    }
}
