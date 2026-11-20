package dev.mark.lineover.core.ml

import android.content.Context
import android.os.Build

/** Where a model runs (Stage 16). */
enum class Accelerator { NPU, GPU, CPU }

/** Remembers the accelerator each model ran fastest on, so it is measured only once per device. */
interface AcceleratorMemory {
    fun get(modelId: String): Accelerator?

    fun put(
        modelId: String,
        accelerator: Accelerator,
    )
}

/** [AcceleratorMemory] in the app's preferences. */
class PrefsAcceleratorMemory(
    context: Context,
) : AcceleratorMemory {
    private val prefs = context.getSharedPreferences("lineover_ml", Context.MODE_PRIVATE)

    override fun get(modelId: String): Accelerator? =
        prefs.getString("accelerator.$modelId", null)?.let { name ->
            Accelerator.entries.firstOrNull {
                it.name ==
                    name
            }
        }

    override fun put(
        modelId: String,
        accelerator: Accelerator,
    ) = prefs.edit().putString("accelerator.$modelId", accelerator.name).apply()
}

/** One measured run: what was tried and how long a warm inference took (or why it failed). */
data class AcceleratorTrial(
    val accelerator: Accelerator,
    val millis: Long?,
    val error: String? = null,
)

/** A compiled model on its accelerator. */
class Picked<R>(
    val runner: R,
    val accelerator: Accelerator,
    val trials: List<AcceleratorTrial>,
)

/**
 * Picks where a model runs (the brief's IF WRONG): the first of NPU -> GPU -> CPU that compiles
 * (NPU only on arm64), then, unless that is the CPU, the CPU too, and keeps the faster of the two
 * by a warm inference measured on this phone (MediaPipe's own benchmark has MagicTouch faster on
 * CPU than GPU). The choice is remembered; a remembered accelerator that stops compiling is
 * measured again.
 */
class AcceleratorPicker(
    private val memory: AcceleratorMemory,
    private val arm64: Boolean = Build.SUPPORTED_ABIS.contains("arm64-v8a"),
) {
    fun order(npu: Boolean = true): List<Accelerator> =
        listOfNotNull(Accelerator.NPU.takeIf { npu && arm64 }, Accelerator.GPU, Accelerator.CPU)

    fun <R : AutoCloseable> pick(
        modelId: String,
        candidates: List<Accelerator>,
        compile: (Accelerator) -> R,
        time: (R) -> Long,
    ): Picked<R>? {
        val trials = mutableListOf<AcceleratorTrial>()
        val tryCompile = { a: Accelerator -> compileOrNull(a, compile, trials) }
        val remembered =
            memory.get(modelId)?.takeIf { it in candidates }?.let { a ->
                tryCompile(a)?.let { Picked(it, a, trials) }
            }
        return remembered ?: measure(modelId, candidates, tryCompile, time, trials)
    }

    private fun <R : AutoCloseable> measure(
        modelId: String,
        candidates: List<Accelerator>,
        tryCompile: (Accelerator) -> R?,
        time: (R) -> Long,
        trials: MutableList<AcceleratorTrial>,
    ): Picked<R>? {
        val (first, where) = candidates.firstNotNullOfOrNull { a -> tryCompile(a)?.let { it to a } } ?: return null
        val best =
            if (where == Accelerator.CPU || Accelerator.CPU !in candidates) {
                Picked(first, where, trials)
            } else {
                fasterOf(first, where, tryCompile, time, trials)
            }
        memory.put(modelId, best.accelerator)
        return best
    }

    /** [first] (on [where]) or the CPU, whichever runs a warm inference faster; the other is closed. */
    private fun <R : AutoCloseable> fasterOf(
        first: R,
        where: Accelerator,
        tryCompile: (Accelerator) -> R?,
        time: (R) -> Long,
        trials: MutableList<AcceleratorTrial>,
    ): Picked<R> {
        val firstMs = time(first)
        trials += AcceleratorTrial(where, firstMs)
        val cpu = tryCompile(Accelerator.CPU) ?: return Picked(first, where, trials)
        val cpuMs = time(cpu)
        trials += AcceleratorTrial(Accelerator.CPU, cpuMs)
        return if (cpuMs < firstMs) {
            first.close()
            Picked(cpu, Accelerator.CPU, trials)
        } else {
            cpu.close()
            Picked(first, where, trials)
        }
    }

    // Each runtime fails in its own way (LiteRtException, MediaPipeException, a missing
    // library's UnsatisfiedLinkError is not caught): any exception means "try the next one".
    @Suppress("TooGenericExceptionCaught")
    private fun <R> compileOrNull(
        a: Accelerator,
        compile: (Accelerator) -> R,
        trials: MutableList<AcceleratorTrial>,
    ): R? =
        try {
            compile(a)
        } catch (e: Exception) {
            trials += AcceleratorTrial(a, null, e.message ?: e.javaClass.simpleName)
            null
        }
}
