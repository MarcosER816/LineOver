package dev.mark.lineover.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a model runs (Stage 16): NPU -> GPU -> CPU, then the faster of that and the CPU, remembered. */
class AcceleratorPickerTest {
    private class Runner(
        val accelerator: Accelerator,
    ) : AutoCloseable {
        var closed = false

        override fun close() {
            closed = true
        }
    }

    private class Memory : AcceleratorMemory {
        val map = mutableMapOf<String, Accelerator>()

        override fun get(modelId: String) = map[modelId]

        override fun put(
            modelId: String,
            accelerator: Accelerator,
        ) {
            map[modelId] = accelerator
        }
    }

    @Test
    fun npu_failure_falls_back_to_gpu_and_a_faster_cpu_wins() {
        val memory = Memory()
        val picker = AcceleratorPicker(memory, arm64 = true)
        val compiled = mutableListOf<Runner>()
        val picked =
            picker.pick(
                "m",
                picker.order(),
                compile = { a ->
                    if (a == Accelerator.NPU) error("QNN libraries not found")
                    Runner(a).also { compiled += it }
                },
                time = { if (it.accelerator == Accelerator.GPU) 90L else 40L },
            )!!
        assertEquals(Accelerator.CPU, picked.accelerator)
        assertEquals(Accelerator.CPU, memory.map["m"])
        assertTrue(compiled.first { it.accelerator == Accelerator.GPU }.closed)
        assertEquals(listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU), picked.trials.map { it.accelerator })
        assertEquals("QNN libraries not found", picked.trials[0].error)
    }

    @Test
    fun a_faster_gpu_is_kept_and_remembered() {
        val memory = Memory()
        val picker = AcceleratorPicker(memory, arm64 = true)
        val picked =
            picker.pick("m", picker.order(npu = false), compile = ::Runner, time = {
                if (it.accelerator ==
                    Accelerator.GPU
                ) {
                    20L
                } else {
                    60L
                }
            })!!
        assertEquals(Accelerator.GPU, picked.accelerator)
        assertEquals(Accelerator.GPU, memory.map["m"])
        // The next session compiles the remembered one only, without measuring.
        var compiles = 0
        val again =
            picker.pick("m", picker.order(npu = false), compile = {
                compiles++
                Runner(it)
            }, time = { error("not measured again") })!!
        assertEquals(Accelerator.GPU, again.accelerator)
        assertEquals(1, compiles)
    }

    @Test
    fun npu_needs_arm64_and_a_broken_remembered_choice_is_measured_again() {
        assertEquals(listOf(Accelerator.GPU, Accelerator.CPU), AcceleratorPicker(Memory(), arm64 = false).order())
        val memory = Memory().apply { map["m"] = Accelerator.GPU }
        val picker = AcceleratorPicker(memory, arm64 = false)
        val picked =
            picker.pick("m", picker.order(), compile = {
                if (it ==
                    Accelerator.GPU
                ) {
                    error("no OpenCL")
                } else {
                    Runner(it)
                }
            }, time = { 1L })!!
        assertEquals(Accelerator.CPU, picked.accelerator)
        assertEquals(Accelerator.CPU, memory.map["m"])
        assertNull(picker.pick("x", picker.order(), compile = { error("nothing works") }, time = { 1L }))
    }
}
