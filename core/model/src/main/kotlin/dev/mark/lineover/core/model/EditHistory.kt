package dev.mark.lineover.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * One step of the history: the whole graph after it (graphs share unchanged layers and ops
 * with the step before, see [EditGraph.sharing]) and what it did ("Exposure +0.7").
 * [key] says which controls it changed: further changes of the same controls while the step
 * is open (a slider still being dragged) replace it instead of adding a step.
 */
@Serializable
data class HistoryEntry(
    val graph: EditGraph,
    val label: String,
    val key: String = "",
)

/**
 * Undo / redo: an immutable list of graph snapshots, never pixels. [index] is the step the
 * photo shows; steps after it can be redone until a new edit drops them. At most [LIMIT]
 * entries: the oldest step is dropped (it becomes the new start) when the list is full.
 *
 * [open] is true while the top step can still absorb changes of the same controls (a drag in
 * progress); [close] ends it (slider released), and undo / redo / restore close it first.
 */
@Serializable
data class EditHistory(
    val entries: List<HistoryEntry>,
    val index: Int = entries.lastIndex,
    @Transient val open: Boolean = false,
) {
    init {
        require(entries.isNotEmpty() && index in entries.indices) { "index $index outside ${entries.size} entries" }
    }

    val current: EditGraph get() = entries[index].graph

    val canUndo: Boolean get() = index > 0

    val canRedo: Boolean get() = index < entries.lastIndex

    /** The label of the step undo would take back, or null. */
    val undoLabel: String? get() = if (canUndo) entries[index].label else null

    val redoLabel: String? get() = if (canRedo) entries[index + 1].label else null

    /**
     * [graph] as the newest step. An open top step with the same [key] is replaced (a drag
     * coalesces into one step); otherwise the redo steps are dropped and a step is added.
     * [open] keeps the new step open for further changes of the same controls.
     */
    fun record(
        graph: EditGraph,
        label: String,
        key: String,
        open: Boolean = false,
    ): EditHistory {
        if (graph == current) return this
        val coalesce = this.open && index == entries.lastIndex && index > 0 && key == entries[index].key
        val base = if (coalesce) entries.subList(0, index) else entries.subList(0, index + 1)
        val previous = base.last().graph
        if (graph == previous && coalesce) {
            // Dragged back to where the step started: the step disappears.
            return EditHistory(base.toList(), base.lastIndex, open = false)
        }
        val entry = HistoryEntry(graph.sharing(previous), label, key)
        val all = base + entry
        val trimmed = if (all.size > LIMIT) all.drop(all.size - LIMIT) else all
        return EditHistory(trimmed, trimmed.lastIndex, open = open)
    }

    fun close(): EditHistory = if (open) copy(open = false) else this

    fun undo(): EditHistory = if (canUndo) EditHistory(entries, index - 1) else close()

    fun redo(): EditHistory = if (canRedo) EditHistory(entries, index + 1) else close()

    /** Shows step [target] (a History row's Restore): later steps stay redoable until the next edit. */
    fun jumpTo(target: Int): EditHistory = EditHistory(entries, target.coerceIn(entries.indices))

    companion object {
        const val LIMIT = 500
        const val START_LABEL = "Opened"

        fun start(
            graph: EditGraph = EditGraph(),
            label: String = START_LABEL,
        ): EditHistory = EditHistory(listOf(HistoryEntry(graph, label)))
    }
}
