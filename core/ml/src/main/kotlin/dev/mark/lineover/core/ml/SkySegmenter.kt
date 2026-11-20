package dev.mark.lineover.core.ml

import android.content.Context
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import com.google.ai.edge.litert.Accelerator as LiteRtAccelerator

/**
 * Select sky (Stage 16): ModelCatalog.SKY (DeepLabV3+ on ADE20K, 512 x 512 RGB 8-bit in, the
 * class of every pixel out) through LiteRT's CompiledModel. The mask is 1 where the class is sky.
 */
class SkySegmenter private constructor(
    private val model: CompiledModel,
    private val environment: Environment,
) : AutoCloseable {
    private val inputs: List<TensorBuffer> = model.createInputBuffers()
    private val outputs: List<TensorBuffer> = model.createOutputBuffers()

    /** The mask of ADE20K class [label] (sky by default; Stage 17's People mask uses [PERSON_CLASS]). */
    fun segment(
        image: ModelImage,
        label: Int = SKY_CLASS,
    ): ModelMask {
        inputs[0].writeInt8(image.rgbResized(SIZE, SIZE))
        model.run(inputs, outputs)
        val labels = outputs[0].readInt()
        return ModelMask(SIZE, SIZE, FloatArray(SIZE * SIZE) { if (labels[it] == label) 1f else 0f })
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
        environment.close()
    }

    companion object {
        const val SIZE = 512

        /** ADE20K's "sky" (MLPerf's 32-class list keeps ADE20K's first classes: wall, building, sky ...). */
        const val SKY_CLASS = 3

        /** ADE20K's "person" (index 13 of the same list), for the People mask (Stage 17). */
        const val PERSON_CLASS = 13

        /**
         * Compiles [file] on the best accelerator (AcceleratorPicker: NPU -> GPU -> CPU, measured).
         * The NPU is tried only when Qualcomm's runtime is already on the device: LineOver neither
         * bundles nor downloads it.
         */
        fun open(
            context: Context,
            file: File,
            picker: AcceleratorPicker,
        ): Picked<SkySegmenter>? {
            val probe = ModelImage(SIZE, SIZE, ByteArray(SIZE * SIZE * RGBA) { GREY })
            return picker.compileOnBest(context, file, ModelCatalog.SKY.id, ::SkySegmenter) { it.segment(probe) }
        }

        private const val RGBA = 4
        private const val GREY: Byte = 0x80.toByte()
    }
}

/**
 * Compiles [file] on the best accelerator (AcceleratorPicker: NPU -> GPU -> CPU, measured by
 * [probe]). The NPU is tried only when Qualcomm's runtime is already on the device: LineOver
 * neither bundles nor downloads it.
 */
internal fun <T : AutoCloseable> AcceleratorPicker.compileOnBest(
    context: Context,
    file: File,
    id: String,
    make: (CompiledModel, Environment) -> T,
    probe: (T) -> Unit,
): Picked<T>? {
    val npu = BuiltinNpuAcceleratorProvider(context)
    val npuReady = runCatching { npu.isDeviceSupported() && npu.isLibraryReady() }.getOrDefault(false)
    return pick(
        id,
        order(npu = npuReady),
        compile = { accelerator ->
            val env = if (accelerator == Accelerator.NPU) Environment.create(context, npu) else Environment.create(context)
            runCatching { make(CompiledModel.create(file.absolutePath, CompiledModel.Options(accelerator.liteRt()), env), env) }
                .onFailure { env.close() }
                .getOrThrow()
        },
        time = { runner -> timeWarm { probe(runner) } },
    )
}

private fun Accelerator.liteRt(): LiteRtAccelerator =
    when (this) {
        Accelerator.NPU -> LiteRtAccelerator.NPU
        Accelerator.GPU -> LiteRtAccelerator.GPU
        Accelerator.CPU -> LiteRtAccelerator.CPU
    }

/** Milliseconds of a second (warm) run of [block]: the first one pays for loading and shaders. */
internal inline fun timeWarm(block: () -> Unit): Long {
    block()
    val start = System.nanoTime()
    block()
    return (System.nanoTime() - start) / NANOS_PER_MS
}

private const val NANOS_PER_MS = 1_000_000L
