package dev.mark.lineover.core.ml

import android.content.Context
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenterOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.Stroke
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Object select (Stage 16): MediaPipe's Interactive Segmenter with MagicTouch V2. [setImage]
 * encodes the photo once (the expensive part); each [segment] with taps / scribbles only runs the
 * decoder, so refining a selection is quick.
 */
class ObjectSegmenter private constructor(
    private val segmenter: InteractiveSegmenter,
) : AutoCloseable {
    private var image: ModelImage? = null

    fun setImage(next: ModelImage) {
        if (next === image) return
        val bitmap = next.toBitmap()
        segmenter.setImage(BitmapImageBuilder(bitmap).build())
        image = next
    }

    fun segment(prompts: List<SegmentPrompt>): ModelMask? {
        if (image == null || prompts.isEmpty()) return null
        val strokes =
            prompts.map { prompt ->
                Stroke
                    .builder()
                    .setBrushMode(if (prompt.positive) Stroke.BrushMode.POSITIVE else Stroke.BrushMode.NEGATIVE)
                    .setPoints(prompt.points.map { (x, y) -> NormalizedKeypoint.create(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)) })
                    .setCompleted(true)
                    .build()
            }
        return segmenter.segment(strokes).use(::maskOf)
    }

    override fun close() = segmenter.close()

    companion object {
        /** Opens [file] on the best delegate (AcceleratorPicker, measured: NPU -> GPU -> CPU). */
        fun open(
            context: Context,
            file: File,
            picker: AcceleratorPicker,
        ): Picked<ObjectSegmenter>? {
            val buffer = RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
            val probe = ModelImage(PROBE, PROBE, ByteArray(PROBE * PROBE * RGBA) { GREY })
            val tap = listOf(SegmentPrompt(listOf(HALF to HALF), positive = true))
            return picker.pick(
                ModelCatalog.MAGIC_TOUCH.id,
                picker.order(),
                compile = { accelerator ->
                    val base =
                        BaseOptions
                            .builder()
                            .setModelAssetBuffer(buffer.duplicate())
                            .setDelegate(accelerator.delegate())
                            .build()
                    ObjectSegmenter(
                        InteractiveSegmenter.createFromOptions(context, InteractiveSegmenterOptions.builder().setBaseOptions(base).build()),
                    )
                },
                time = { segmenter ->
                    timeWarm {
                        segmenter.image = null
                        segmenter.setImage(probe)
                        segmenter.segment(tap)
                    }
                },
            )
        }

        private fun Accelerator.delegate(): Delegate =
            when (this) {
                Accelerator.NPU -> Delegate.NPU
                Accelerator.GPU -> Delegate.GPU
                Accelerator.CPU -> Delegate.CPU
            }

        /** The mask MediaPipe returns: float confidences, or 8-bit (0 / 255 or a category). */
        internal fun maskOf(mask: MPImage): ModelMask {
            val w = mask.width
            val h = mask.height
            val format = mask.containedImageProperties.firstOrNull()?.imageFormat ?: MPImage.IMAGE_FORMAT_UNKNOWN
            val values = FloatArray(w * h)
            if (format == MPImage.IMAGE_FORMAT_VEC32F1) {
                val floats = ByteBufferExtractor.extract(mask, format).order(ByteOrder.nativeOrder()).asFloatBuffer()
                floats.get(values)
            } else {
                val bytes = ByteBufferExtractor.extract(mask, MPImage.IMAGE_FORMAT_ALPHA)
                var max = 0
                for (i in 0 until w * h) max = maxOf(max, bytes.get(i).toInt() and BYTE)
                val scale = if (max > 1) BYTE.toFloat() else 1f
                for (i in 0 until w * h) values[i] = ((bytes.get(i).toInt() and BYTE) / scale).coerceIn(0f, 1f)
            }
            return ModelMask(w, h, values)
        }

        private const val PROBE = 512
        private const val RGBA = 4
        private const val BYTE = 255
        private const val HALF = 0.5f
        private const val GREY: Byte = 0x80.toByte()
    }
}
