package dev.mark.lineover.feature.editor

import android.view.Surface
import dev.mark.lineover.core.engine.CompareMode
import dev.mark.lineover.core.engine.EngineStats
import dev.mark.lineover.core.engine.Histogram
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.feature.editor.canvas.EditorCanvas
import dev.mark.lineover.feature.editor.canvas.EditorCanvasFactory
import dev.mark.lineover.feature.editor.canvas.ImageSummary
import kotlinx.coroutines.flow.MutableStateFlow

/** The engine as seen by the editor, without native code: records what it is told. */
class FakeEditorCanvas(
    override val imageWidth: Int = 8160,
    override val imageHeight: Int = 6120,
) : EditorCanvas {
    override val summary = ImageSummary(imageWidth, imageHeight, 2048, 1536, "sRGB IEC61966-2.1", "8-bit sRGB", ultraHdr = false)
    override val loading = MutableStateFlow(false)
    val graphs = mutableListOf<EditGraph>()
    var transforms = 0
        private set
    var lastTransform = FloatArray(6)
        private set
    var closed = false
        private set
    override val histogram = MutableStateFlow<Histogram?>(null)
    var histogramEnabled = false
        private set
    var clipping = false
        private set
    var compare = CompareMode.Off to 0.5f
        private set
    var showingOriginal = false
        private set

    /** What Auto returns: (exposure EV, blacks), null for "no preview yet". */
    var auto: Pair<Float, Float>? = 1.25f to 12f

    override fun setHistogramEnabled(enabled: Boolean) {
        histogramEnabled = enabled
    }

    override fun setClipping(clipping: Boolean) {
        this.clipping = clipping
    }

    override fun setCompare(
        mode: CompareMode,
        split: Float,
    ) {
        compare = mode to split
    }

    override fun setShowOriginal(original: Boolean) {
        showingOriginal = original
    }

    override fun autoLight(): Pair<Float, Float>? = auto

    override fun stats(): EngineStats? = null

    override fun autoLevels(): FloatArray = floatArrayOf(0.04f, 0.92f, 0.02f, 0.96f, 0.06f, 0.9f)

    /** Thumbnails: a small bitmap whose colour shows how many ops the graph has. */
    var thumbnails = 0
        private set

    override fun thumbnail(graph: dev.mark.lineover.core.model.EditGraph): android.graphics.Bitmap {
        thumbnails++
        val ops = graph.layers.sumOf { it.ops.size }
        return android.graphics.Bitmap.createBitmap(8, 6, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb((ops * 40).coerceAtMost(255), 90, 160))
        }
    }

    /** What the eyedropper solves to, null for "black". */
    var whiteBalance: Pair<Float, Float>? = 4500f to 12f

    /** Band weights under the finger: mostly orange. */
    var weights: FloatArray? =
        FloatArray(8).also {
            it[1] = 0.75f
            it[2] = 0.25f
        }
    val samples = mutableListOf<Pair<Float, Float>>()

    override fun whiteBalanceAt(
        u: Float,
        v: Float,
    ): Pair<Float, Float>? {
        samples += u to v
        return whiteBalance
    }

    override fun hslWeightsAt(
        u: Float,
        v: Float,
    ): FloatArray? {
        samples += u to v
        return weights
    }

    override fun loupeAt(
        u: Float,
        v: Float,
    ): FloatArray = FloatArray(9 * 9 * 4) { if (it % 4 == 3) 1f else 0.18f }

    override fun setGraph(graph: EditGraph): String? {
        graphs += graph
        return null
    }

    override fun attach(surface: Surface) = Unit

    override fun resize(
        width: Int,
        height: Int,
    ) = Unit

    override fun detach() = Unit

    override fun setTransform(
        a: Float,
        b: Float,
        c: Float,
        d: Float,
        e: Float,
        f: Float,
    ) {
        transforms++
        lastTransform = floatArrayOf(a, b, c, d, e, f)
    }

    override fun setSurround(argb: Int) = Unit

    override fun close() {
        closed = true
    }
}

/** Opens [canvas] (or nothing, like an undecodable RAW before Stage 25). */
class FakeCanvasFactory(
    private val canvas: EditorCanvas? = FakeEditorCanvas(),
) : EditorCanvasFactory {
    override suspend fun open(project: dev.mark.lineover.core.model.Project): EditorCanvas? = canvas
}
