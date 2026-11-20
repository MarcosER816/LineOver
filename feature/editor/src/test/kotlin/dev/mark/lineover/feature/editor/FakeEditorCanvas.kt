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
    exif: dev.mark.lineover.core.model.PhotoExif =
        dev.mark.lineover.core.model
            .PhotoExif(),
) : EditorCanvas {
    override val summary =
        ImageSummary(imageWidth, imageHeight, 2048, 1536, "sRGB IEC61966-2.1", "8-bit sRGB", ultraHdr = false, exif = exif)
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

    /** Auto (Stage 11): what the engine would propose (null = no preview yet). */
    var autoResult: dev.mark.lineover.core.model.AutoResult? =
        dev.mark.lineover.core.model
            .AutoResult(5600f, 4f, 0.6f, 15f, 20f, -10f, 18f)

    override fun autoSettings() = autoResult

    /** 3D LUTs registered with this canvas, by id. */
    val registeredLayers = mutableListOf<String>()

    val maskViews = mutableListOf<Pair<String?, Int>>()

    val selections = mutableListOf<String>()
    var selectionValue: Float? = null
    val maskRasters = mutableMapOf<String, dev.mark.lineover.core.io.masks.MaskRaster>()

    override fun setSelection(json: String): String? {
        synchronized(selections) { selections += json }
        return null
    }

    override fun selectionAt(
        u: Float,
        v: Float,
    ): Float? = selectionValue

    override fun selectionRaster(): dev.mark.lineover.core.engine.SelectionRaster? =
        if (selections.lastOrNull().isNullOrEmpty()) {
            null
        } else {
            dev.mark.lineover.core.engine.SelectionRaster(
                4,
                3,
                8160,
                6120,
                FloatArray(12) {
                    it /
                        11f
                },
            )
        }

    override fun registerMaskRaster(
        name: String,
        raster: dev.mark.lineover.core.io.masks.MaskRaster,
    ) {
        maskRasters[name] = raster
    }

    // Stage 16: a small grey image for the models, guide planes, and a live-wire that runs
    // straight from the anchor through a midpoint.
    var imageRequests = 0
    var anchor: Pair<Float, Float>? = null

    // Stage 17: a model mask "upsampled" to an 8 x 6 grid over the document (nearest), its guide, and a fixed photo colour.
    val labAt = floatArrayOf(55f, 4f, -12f)

    override fun photoLabAt(
        x: Float,
        y: Float,
    ): FloatArray = labAt.copyOf()

    override fun upsampleMask(
        values: FloatArray,
        width: Int,
        height: Int,
    ): dev.mark.lineover.core.engine.UpsampledMask {
        val w = 8
        val h = 6
        val up = FloatArray(w * h) { values[(it / w * height / h) * width + (it % w) * width / w] }
        return dev.mark.lineover.core.engine.UpsampledMask(
            dev.mark.lineover.core.engine
                .SelectionRaster(w, h, imageWidth, imageHeight, up),
            dev.mark.lineover.core.engine
                .SelectionRaster(w, 3 * h, w, 3 * h, FloatArray(3 * w * h) { 0.5f }),
        )
    }

    override fun selectionImage(maxEdge: Int): dev.mark.lineover.core.engine.SelectionImage {
        imageRequests++
        return dev.mark.lineover.core.engine
            .SelectionImage(4, 3, ByteArray(4 * 3 * 4) { 0x80.toByte() })
    }

    override fun selectionGuide(): dev.mark.lineover.core.engine.SelectionRaster? =
        if (selections.lastOrNull().isNullOrEmpty()) {
            null
        } else {
            dev.mark.lineover.core.engine.SelectionRaster(
                4,
                9,
                4,
                9,
                FloatArray(36) { 0.5f },
            )
        }

    override fun liveWireAnchor(
        x: Float,
        y: Float,
        reach: Float,
    ): Boolean {
        anchor = x to y
        return true
    }

    override fun liveWirePath(
        x: Float,
        y: Float,
    ): FloatArray {
        val (ax, ay) = anchor ?: return FloatArray(0)
        return floatArrayOf(ax, ay, (ax + x) / 2, (ay + y) / 2 + 10f, x, y)
    }

    override fun setMaskView(
        layerId: String?,
        view: Int,
    ) {
        synchronized(maskViews) { maskViews += layerId to view }
    }

    override fun registerLayerImage(
        id: String,
        file: java.io.File,
    ): Boolean {
        synchronized(registeredLayers) { registeredLayers += id }
        return file.isFile
    }

    val registeredLuts = mutableMapOf<String, dev.mark.lineover.core.model.CubeLut>()

    override fun registerLut(
        id: String,
        lut: dev.mark.lineover.core.model.CubeLut,
    ): Boolean {
        registeredLuts[id] = lut
        return true
    }

    /** Upright input: the preview's line segments (null = no preview yet). */
    var segments: List<dev.mark.lineover.core.model.LineSegment>? = emptyList()

    /** Auto straighten: the horizon's clockwise tilt in degrees. */
    var horizonTilt: Float? = 2.5f

    var chromatic: Pair<Float, Float>? = 0.004f to -0.002f

    override fun lineSegments() = segments

    override fun horizon() = horizonTilt

    override fun chromaticAberration() = chromatic

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
