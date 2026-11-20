package dev.mark.lineover.core.ml

import android.content.Context
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File

/**
 * The Depth range mask (Stage 17): ModelCatalog.DEPTH (Depth Anything V2 Small, 518 x 518 RGB
 * float 0..1 in, relative inverse depth out) through LiteRT's CompiledModel. The map is
 * normalised to 0..1 with 1 the nearest point of the photo.
 */
class DepthEstimator private constructor(
    private val model: CompiledModel,
    private val environment: Environment,
) : AutoCloseable {
    private val inputs: List<TensorBuffer> = model.createInputBuffers()
    private val outputs: List<TensorBuffer> = model.createOutputBuffers()

    fun estimate(image: ModelImage): ModelMask {
        val rgb = image.rgbResized(SIZE, SIZE)
        inputs[0].writeFloat(FloatArray(rgb.size) { (rgb[it].toInt() and BYTE) / BYTE.toFloat() })
        model.run(inputs, outputs)
        return ModelMask(SIZE, SIZE, normalised(outputs[0].readFloat()))
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
        environment.close()
    }

    companion object {
        const val SIZE = 518
        private const val BYTE = 255
        private const val RGBA = 4
        private const val GREY: Byte = 0x80.toByte()

        /** [disparity] (larger = nearer) mapped to 0..1 by its minimum and maximum; flat when constant. */
        fun normalised(disparity: FloatArray): FloatArray {
            val finite = disparity.filter { it.isFinite() }
            val lo = finite.minOrNull() ?: 0f
            val hi = finite.maxOrNull() ?: 0f
            val span = hi - lo
            return FloatArray(disparity.size) { if (span > 0f && disparity[it].isFinite()) ((disparity[it] - lo) / span) else 0f }
        }

        fun open(
            context: Context,
            file: File,
            picker: AcceleratorPicker,
        ): Picked<DepthEstimator>? {
            val probe = ModelImage(SIZE, SIZE, ByteArray(SIZE * SIZE * RGBA) { GREY })
            return picker.compileOnBest(context, file, ModelCatalog.DEPTH.id, ::DepthEstimator) { it.estimate(probe) }
        }
    }
}
