package dev.mark.lineover.core.engine

import dev.mark.lineover.core.model.EditGraph
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Where the scheduler sends graphs: the engine session (a fake in unit tests). */
fun interface GraphTarget {
    /** Applies [graph]; null on success, otherwise why it was rejected. */
    fun apply(graph: EditGraph): String?
}

/**
 * Coalesces edits (e.g. a slider moving at 120 Hz) into preview renders: the latest
 * graph wins, intermediate ones that arrive while a render is being handed over are
 * dropped. Preview renders happen on every change; the full-resolution render runs only
 * for export ([EngineSession.renderFullResolution]), never while editing.
 */
class RenderScheduler(
    private val target: GraphTarget,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val latest = MutableStateFlow<EditGraph?>(null)
    private val appliedFlow = MutableStateFlow<EditGraph?>(null)
    private val errorFlow = MutableStateFlow<String?>(null)

    /** The last graph the engine accepted. */
    val applied: StateFlow<EditGraph?> = appliedFlow.asStateFlow()

    /** Why the last graph was rejected, or null. */
    val error: StateFlow<String?> = errorFlow.asStateFlow()

    private val job: Job =
        scope.launch(dispatcher) {
            latest.filterNotNull().collectLatest { graph ->
                val failure = target.apply(graph)
                if (failure == null) appliedFlow.value = graph
                errorFlow.value = failure
            }
        }

    /** Never blocks: called from the UI on every edit event. */
    fun submit(graph: EditGraph) {
        latest.value = graph
    }

    fun cancel() = job.cancel()
}
