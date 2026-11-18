package dev.mark.lineover.core.model

import kotlinx.serialization.Serializable

/** A named saved graph of the current version (History → Snapshots). */
@Serializable
data class Snapshot(
    val id: String,
    val name: String,
    val created: Long,
    val graph: EditGraph,
)

/**
 * A branch of the edit (History → Versions): its own graph and its own undo history, so
 * versions of the same photo can be compared side by side and edited independently.
 */
@Serializable
data class Version(
    val id: String,
    val name: String,
    val created: Long,
    val history: EditHistory,
) {
    val graph: EditGraph get() = history.current
}

/**
 * Everything a project saves besides pixels (document.json in a .loproj): the versions with
 * their histories, the active one and the snapshots. The active version's graph is also
 * written on its own as graph.json.
 */
@Serializable
data class ProjectDocument(
    val versions: List<Version> = listOf(Version(FIRST_VERSION, "Version 1", 0L, EditHistory.start())),
    val activeVersion: String = versions.first().id,
    val snapshots: List<Snapshot> = emptyList(),
) {
    init {
        require(versions.isNotEmpty() && versions.any { it.id == activeVersion }) { "no active version $activeVersion" }
    }

    val active: Version get() = versions.first { it.id == activeVersion }

    val graph: EditGraph get() = active.graph

    /** This document with the active version's history replaced. */
    fun withHistory(history: EditHistory): ProjectDocument =
        copy(versions = versions.map { if (it.id == activeVersion) it.copy(history = history) else it })

    companion object {
        const val FIRST_VERSION = "v1"

        /** A new project, or one whose saved file only had a graph. */
        fun of(
            graph: EditGraph,
            created: Long = 0L,
        ): ProjectDocument = ProjectDocument(listOf(Version(FIRST_VERSION, "Version 1", created, EditHistory.start(graph))))
    }
}
