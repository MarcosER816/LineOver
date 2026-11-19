package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.model.ColorAdjustments
import dev.mark.lineover.core.model.DetailAdjustments
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.EditHistory
import dev.mark.lineover.core.model.GeometryAdjustments
import dev.mark.lineover.core.model.GradeAdjustments
import dev.mark.lineover.core.model.HistoryLabels
import dev.mark.lineover.core.model.LightAdjustments
import dev.mark.lineover.core.model.ProjectDocument
import dev.mark.lineover.core.model.Snapshot
import dev.mark.lineover.core.model.Version

// History, snapshots and versions of the editor (Stage 8), as pure functions of the state.

/** [graph] as the edit, with the sliders read back from it (undo, restore, a version, a load). */
internal fun EditorState.showingGraph(graph: EditGraph): EditorState {
    val ops =
        graph.layers
            .firstOrNull()
            ?.ops
            .orEmpty()
    return copy(
        graph = graph,
        light = LightAdjustments.fromOps(ops),
        color = ColorAdjustments.fromOps(ops),
        grade = GradeAdjustments.fromOps(ops),
        geometry = GeometryAdjustments.fromOps(ops),
        detail = DetailAdjustments.fromOps(ops),
        preview = null,
    )
}

/**
 * Records the edit in the history. A drag ([open]) of the same control as the open top step
 * replaces it, so a slider drag is one step; [label] names a one-off action ("Auto light")
 * and closes the step at once.
 */
internal fun EditorState.recordEdit(
    label: String? = null,
    open: Boolean = true,
): EditorState {
    val h = history
    if (graph == h.current) return this
    val top = h.entries[h.index]
    val stepBase = if (h.open && h.index == h.entries.lastIndex && h.index > 0) h.entries[h.index - 1].graph else null
    val fromBase = stepBase?.let { HistoryLabels.describe(it, graph) }
    val continues = label == null && fromBase != null && fromBase.key == top.key
    val change = if (continues) requireNotNull(fromBase) else HistoryLabels.describe(h.current, graph)
    val next =
        (if (continues) h else h.close()).record(
            graph,
            label ?: change.label,
            if (label != null) "label:$label" else change.key,
            open = open && label == null,
        )
    return copy(history = next, preview = null)
}

internal fun EditorState.undone(): EditorState? {
    val h = history.close()
    if (!h.canUndo) return null
    val next = h.undo()
    return copy(history = next).showingGraph(next.current)
}

internal fun EditorState.redone(): EditorState? {
    val h = history.close()
    if (!h.canRedo) return null
    val next = h.redo()
    return copy(history = next).showingGraph(next.current)
}

internal fun EditorState.jumpedTo(index: Int): EditorState {
    val next = history.close().jumpTo(index)
    return copy(history = next).showingGraph(next.current)
}

/** The document to save: the active version holds the current history. */
internal fun EditorState.documentNow(): ProjectDocument = document.withHistory(history.close())

/** Applies the previewed History row (moves the step) or snapshot (a new step); null if none. */
internal fun EditorState.restored(): Pair<EditorState, String>? =
    when (val p = preview) {
        is HistoryPreview.Step -> {
            val label = history.entries.getOrNull(p.index)?.label ?: return null
            jumpedTo(p.index) to label
        }

        is HistoryPreview.SnapshotState -> {
            val snapshot = document.snapshots.firstOrNull { it.id == p.id } ?: return null
            showingGraph(snapshot.graph).recordEdit("Snapshot: ${snapshot.name}", open = false) to snapshot.name
        }

        null -> {
            null
        }
    }

internal fun EditorState.withSnapshot(
    name: String,
    now: Long,
): EditorState {
    val id = nextId("s", document.snapshots.map { it.id })
    return copy(document = documentNow().copy(snapshots = document.snapshots + Snapshot(id, name, now, graph)), history = history.close())
}

internal fun EditorState.withoutSnapshot(id: String): EditorState =
    copy(document = documentNow().copy(snapshots = document.snapshots.filterNot { it.id == id }), preview = null)

/** A new version from the current edit, with its own history; it becomes the active one. */
internal fun EditorState.withNewVersion(
    name: String,
    now: Long,
): EditorState {
    val doc = documentNow()
    val id = nextId("v", doc.versions.map { it.id })
    val version = Version(id, name, now, EditHistory.start(graph, "From ${doc.active.name}"))
    return copy(document = doc.copy(versions = doc.versions + version, activeVersion = id), history = version.history, preview = null)
}

internal fun EditorState.withActiveVersion(id: String): EditorState {
    val doc = documentNow()
    val target = doc.versions.firstOrNull { it.id == id } ?: return this
    return copy(document = doc.copy(activeVersion = id), history = target.history).showingGraph(target.graph)
}

internal fun EditorState.withVersionName(
    id: String,
    name: String,
): EditorState =
    copy(
        document =
            documentNow().let { d ->
                d.copy(
                    versions =
                        d.versions.map {
                            if (it.id ==
                                id
                            ) {
                                it.copy(name = name)
                            } else {
                                it
                            }
                        },
                )
            },
    )

/** Deletes version [id] (never the last one); deleting the active version shows the first other. */
internal fun EditorState.withoutVersion(id: String): EditorState? {
    val doc = documentNow()
    if (doc.versions.size <= 1 || doc.versions.none { it.id == id }) return null
    val remaining = doc.versions.filterNot { it.id == id }
    if (doc.activeVersion != id) return copy(document = doc.copy(versions = remaining))
    val next = remaining.first()
    return copy(document = ProjectDocument(remaining, next.id, doc.snapshots), history = next.history).showingGraph(next.graph)
}

internal fun EditorState.nextSnapshotName(): String = "Snapshot ${document.snapshots.size + 1}"

internal fun EditorState.nextVersionName(): String = "Version ${document.versions.size + 1}"

private fun nextId(
    prefix: String,
    existing: List<String>,
): String = prefix + ((existing.mapNotNull { it.removePrefix(prefix).toIntOrNull() }.maxOrNull() ?: 0) + 1)
