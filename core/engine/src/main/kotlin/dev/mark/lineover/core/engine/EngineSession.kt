package dev.mark.lineover.core.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Gainmap
import android.net.Uri
import android.os.Debug
import android.util.Log
import android.view.Surface
import dev.mark.lineover.core.io.project.PreviewImage
import dev.mark.lineover.core.io.source.SourceInfo
import dev.mark.lineover.core.io.source.SourceReader
import dev.mark.lineover.core.io.source.TileDecoder
import dev.mark.lineover.core.model.AutoResult
import dev.mark.lineover.core.model.CubeLut
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.EditGraphJson
import dev.mark.lineover.core.model.LayerMath
import dev.mark.lineover.core.model.LineSegment
import dev.mark.lineover.core.model.OpParams
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Native op type ids (lo::OpType) the sampling API refers to. */
enum class NativeOp(
    val id: Int,
) {
    WhiteBalance(8),
    HslMixer(11),
}

/** How the canvas compares the edit with the original (native order). */
enum class CompareMode { Off, Split, SideBySide }

/** Memory the engine may use for one image (rule: under 1.2 GB native + GPU for 50 MP). */
data class EngineBudgets(
    val cpuTileBytes: Long = DEFAULT_CPU,
    val gpuTileBytes: Long = DEFAULT_GPU,
    val decoderThreads: Int = DEFAULT_THREADS,
) {
    companion object {
        const val DEFAULT_CPU = 192L shl 20
        const val DEFAULT_GPU = 192L shl 20
        const val DEFAULT_THREADS = 2
    }
}

/**
 * One open original in the engine: the native engine (render thread, tile store, GPU
 * tiles), the preview proxy and the tile decoder threads. The low-resolution preview is
 * shown as soon as the session opens; sharp tiles stream in for what is on screen when
 * zoomed in. Every call is main-safe except [open] (suspends on IO) and [close] (joins
 * threads; call off the main thread when possible).
 */
@Suppress("TooManyFunctions") // the editor's one facade of the native engine
class EngineSession private constructor(
    private val native: NativeEngine,
    private val reader: SourceReader,
    private val cacheDir: File,
    val info: SourceInfo,
    val preview: PreviewImage,
    decoderThreads: Int,
) : AutoCloseable {
    @Volatile private var closed = false
    private val loaders = (0 until decoderThreads).map { index -> Thread({ loadTiles() }, "LineOverTiles-$index") }

    /** True while sharp tiles of the current view are still loading. */
    val loading: StateFlow<Boolean> get() = native.loading

    /** The edited preview's histogram while [setHistogramEnabled] (at most 30 Hz). */
    val histogram: StateFlow<Histogram?> get() = native.histogram

    init {
        loaders.forEach { it.start() }
    }

    fun attachSurface(surface: Surface): String? = native.attachSurface(surface)

    fun detachSurface() {
        if (!closed) native.detachSurface()
    }

    fun setViewport(
        width: Int,
        height: Int,
    ) {
        if (!closed) native.setViewport(width, height)
    }

    /** view = (a·x + c·y + e, b·x + d·y + f) for original-image pixel (x, y). */
    @Suppress("LongParameterList")
    fun setTransform(
        a: Float,
        b: Float,
        c: Float,
        d: Float,
        e: Float,
        f: Float,
    ) {
        if (!closed) native.setTransform(a, b, c, d, e, f)
    }

    /** Surround colour as sRGB-encoded 0..1 components. */
    fun setSurround(
        r: Float,
        g: Float,
        b: Float,
    ) {
        if (!closed) native.setSurround(r, g, b)
    }

    @Volatile private var layered = false

    /**
     * Hands the graph to the render thread (it re-renders the preview and visible tiles). A
     * graph with layers (Stage 13) also renders its composite for the canvas, here on the
     * calling (background) thread.
     */
    fun setGraph(graph: EditGraph): String? {
        if (closed) return "closed"
        val error = native.setGraph(EditGraphJson.encode(graph))
        val hasLayers = LayerMath.hasLayers(graph)
        if (error == null && (hasLayers || layered)) native.renderComposite()
        layered = hasLayers
        return error
    }

    /**
     * Stage 13: decodes the layer image [file] and registers it as [id] for every engine of the
     * process: preview size (long edge <= 2048) for the canvas, or export size ([full], long
     * edge <= 4096: a float layer image of a 50 MP photo would need 800 MB) for exports. The
     * graph maps any size through the layer's `sourceSize`. False when it cannot be read.
     * Blocking; not on the main thread.
     */
    fun registerLayerImage(
        id: String,
        file: File,
        full: Boolean,
    ): Boolean {
        val info = reader.info(Uri.fromFile(file)) ?: return false
        val edge = if (full) LAYER_EXPORT_EDGE else LAYER_PREVIEW_EDGE
        val image = reader.decodePreview(info, edge) ?: return false
        return try {
            native.registerLayerImage(id, image.bitmap, info.format.id, full)
        } finally {
            image.bitmap.recycle()
        }
    }

    /** Stage 14: shows a layer's mask on the canvas (re-renders the composite here: not on the main thread). */
    fun setMaskView(
        layerId: String,
        view: Int,
    ) {
        if (closed) return
        native.setMaskView(layerId, view)
        if (layered) native.renderComposite()
    }

    /** Drops the export layer images once an export is done (the canvas keeps its own). */
    fun releaseFullLayerImages() = native.releaseFullLayerImages()

    fun stats(): EngineStats = native.stats()

    /** Auto (Light): the percentile stretch of the preview as (exposure EV, blacks). */
    fun autoLight(): Pair<Float, Float>? = if (closed) null else native.autoLight()?.let { it[0] to it[1] }

    /**
     * Working-space RGB averaged over the 5 x 5 preview pixels at image point ([u], [v])
     * (0..1, oriented image), run through the ops before the first [before] op (the
     * eyedropper samples before White balance, targeted adjustment before the HSL mixer).
     */
    fun sample(
        u: Float,
        v: Float,
        before: NativeOp,
    ): FloatArray? = if (closed) null else native.sample(u, v, before.id)

    /** The n x n original preview pixels around (u, v) for the eyedropper loupe. */
    fun sampleGrid(
        u: Float,
        v: Float,
        n: Int,
    ): FloatArray? = if (closed) null else native.sampleGrid(u, v, n)

    /** Auto (Stage 11) from the preview's analysis copy; null before the preview. */
    fun autoSettings(): AutoResult? = if (closed) null else native.autoSettings()?.let { AutoResult.of(it) }

    /** Registers [lut] under [id] (process-wide) so graphs can name it; false when refused. */
    fun registerLut(
        id: String,
        lut: CubeLut,
    ): Boolean = !closed && native.registerLut(id, lut.size, lut.domain, lut.table)

    /** Levels → Auto: the preview's per-channel encoded percentiles (low R, high R, low G, high G, low B, high B). */
    fun autoLevels(): FloatArray? = if (closed) null else native.autoLevels()

    /**
     * [graph] rendered small (long edge <= 512 px, sRGB) on the CPU reference path, for
     * thumbnails. Any thread but the main one; null before the preview is ready.
     */
    fun thumbnail(
        graph: EditGraph,
        maxEdge: Int = 512,
    ): Bitmap? {
        if (closed) return null
        val bytes = native.renderThumbnail(EditGraphJson.encode(graph), maxEdge) ?: return null
        val header = ByteBuffer.wrap(bytes, 0, THUMB_HEADER).order(ByteOrder.LITTLE_ENDIAN)
        val width = header.int
        val height = header.int
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(bytes, THUMB_HEADER, width * height * CHANNELS))
        }
    }

    /** The edited image's size: the geometry's output (crop, rotation), else the original's. */
    fun outputSize(): Pair<Int, Int> {
        if (closed) return info.width to info.height
        val size = native.outputSize()
        return if (size[0] > 0 && size[1] > 0) size[0] to size[1] else info.width to info.height
    }

    /** Line segments of the preview for Upright (normalised coordinates); null before the preview. */
    @Suppress("MagicNumber") // 5 floats per segment
    fun lineSegments(): List<LineSegment>? {
        if (closed) return null
        val values = native.lineSegments() ?: return null
        return (0 until values.size / SEGMENT_FLOATS).map { i ->
            val o = i * SEGMENT_FLOATS
            LineSegment(values[o], values[o + 1], values[o + 2], values[o + 3], values[o + 4])
        }
    }

    /** Auto straighten: the tilt (degrees, clockwise positive) of the dominant horizon-like line, or null. */
    fun horizon(): Float? = if (closed) null else native.horizon()

    /** Chromatic aberration Auto: (red, blue) radial scale - 1, or null. */
    fun chromaticAberration(): Pair<Float, Float>? = if (closed) null else native.chromaticAberration()?.let { it[0] to it[1] }

    /**
     * The preview's Ultra HDR gain map with [graph]'s geometry applied (same matrix and lens,
     * at the gain map's resolution), so it stays aligned with the edited pixels; the gain map
     * itself when the graph has no geometry.
     */
    fun gainmapFor(graph: EditGraph): Gainmap? {
        val gainmap = preview.gainmap ?: return null
        val geometry =
            graph.layers
                .firstOrNull()
                ?.ops
                ?.filterIsInstance<OpParams.Geometry>()
                ?.firstOrNull() ?: return gainmap
        return GeometryWarp.warpGainmap(gainmap, EditGraphJson.encode(graph), geometry, info.width, info.height)
    }

    /** Hold to compare: the original while true. */
    fun setShowOriginal(original: Boolean) {
        if (!closed) native.setShowOriginal(original)
    }

    fun setCompare(
        mode: CompareMode,
        split: Float,
    ) {
        if (!closed) native.setCompare(mode.ordinal, split)
    }

    fun setClipping(clipping: Boolean) {
        if (!closed) native.setClipping(clipping)
    }

    fun setHistogramEnabled(enabled: Boolean) {
        if (!closed) native.setHistogramEnabled(enabled)
    }

    /** One line for logcat: native heap + engine tile memory + GPU textures. */
    fun memoryReport(): String {
        val s = stats()
        val nativeHeap = Debug.getNativeHeapAllocatedSize()
        return "native heap ${mb(nativeHeap)} MB (CPU tiles ${mb(s.cpuTileBytes)} MB in ${s.cpuTiles}, " +
            "spilled ${s.spilledTiles} = ${mb(s.spillBytes)} MB on disk), GPU textures ${mb(s.gpuBytes)} MB in ${s.gpuTiles}, " +
            "native + GPU ${mb(nativeHeap + s.gpuBytes)} MB"
    }

    /** native heap + GPU textures, the number the 1.2 GB limit applies to. */
    fun nativePlusGpuBytes(): Long = Debug.getNativeHeapAllocatedSize() + stats().gpuBytes

    /**
     * Full-resolution render (export only, Stage 12 writes the files): every 512 px tile of
     * the output (see [outputSize]) is rendered on the CPU reference path (ops on its source
     * footprint, then one bicubic resample through the geometry) and handed to [sink] in
     * working-space RGBA. Cancellable between tiles.
     */
    suspend fun renderFullResolution(
        sink: suspend (tx: Int, ty: Int, width: Int, height: Int, pixels: FloatArray) -> Unit,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) = withContext(dispatcher) {
        val decoder = reader.openTiles(info) ?: error("no region decoder for ${info.uri}")
        decoder.use {
            // Tiles of the OUTPUT (the geometry's crop / rotation; the original without one):
            // each reads the level-0 source tiles under its footprint, decoded on demand.
            val (width, height) = outputSize()
            val tilesX = (width + TileDecoder.TILE - 1) / TileDecoder.TILE
            val tilesY = (height + TileDecoder.TILE - 1) / TileDecoder.TILE
            val buffer = FloatArray(TileDecoder.TILE * TileDecoder.TILE * CHANNELS)
            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) {
                    currentCoroutineContext().ensureActive()
                    val sources = native.outputTileSources(tx, ty)
                    for (i in 0 until sources.size / 2) decodeInto(decoder, 0, sources[2 * i], sources[2 * i + 1])
                    val size = native.renderOutputTile(tx, ty, buffer) ?: error("tile $tx,$ty could not be rendered")
                    sink(tx, ty, size[0], size[1], buffer)
                }
            }
        }
    }

    /**
     * Export (Stage 12): renders the whole edit at full resolution into a new [ExportImage]
     * in [dir], reporting the share of tiles done (0..1) to [progress]. Set the graph first.
     */
    suspend fun renderForExport(
        dir: File,
        progress: (Float) -> Unit = {},
    ): ExportImage {
        val (width, height) = outputSize()
        val image = ExportImage.create(dir, width, height)
        var rendered = false
        try {
            val tilesX = (width + TileDecoder.TILE - 1) / TileDecoder.TILE
            val tilesY = (height + TileDecoder.TILE - 1) / TileDecoder.TILE
            var done = 0
            renderFullResolution({ tx, ty, w, h, pixels ->
                image.putTile(tx * TileDecoder.TILE, ty * TileDecoder.TILE, w, h, pixels)
                progress(++done / (tilesX * tilesY).toFloat())
            })
            rendered = true
            return image
        } finally {
            if (!rendered) image.close() // cancelled or failed: free the mapped file now
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        loaders.forEach { it.join(JOIN_MILLIS) }
        native.close()
        cacheDir.deleteRecursively()
    }

    private fun loadTiles() {
        val decoder =
            try {
                reader.openTiles(info)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "no memory for a region decoder", e)
                null
            }
        val request = IntArray(REQUEST_SIZE)
        try {
            while (!closed) {
                val got = native.takeRequest(POLL_MILLIS, request)
                when {
                    !got || closed -> Unit
                    decoder == null -> native.failTile(request[0], request[1], request[2])
                    else -> decodeInto(decoder, request[0], request[1], request[2])
                }
            }
        } finally {
            decoder?.close()
        }
    }

    private fun decodeInto(
        decoder: TileDecoder,
        level: Int,
        tx: Int,
        ty: Int,
    ) {
        val region =
            try {
                decoder.decode(level, tx, ty)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "tile $level/$tx/$ty: out of memory", e)
                null
            }
        if (region == null) {
            native.failTile(level, tx, ty)
            return
        }
        try {
            native.submitRegion(
                level,
                tx,
                ty,
                region.bitmap,
                info.format.id,
                region.regionX,
                region.regionY,
                region.rawLevelWidth,
                region.rawLevelHeight,
                info.orientation,
            )
        } finally {
            region.bitmap.recycle()
        }
    }

    companion object {
        private const val TAG = "LineOverEngine"
        private const val POLL_MILLIS = 200
        private const val JOIN_MILLIS = 2000L
        private const val CHANNELS = 4
        private const val SEGMENT_FLOATS = 5

        /**
         * Opens [uri]: reads its header, decodes the preview proxy (≤ 2048 px) and starts the
         * engine. Null when the original cannot be decoded yet (e.g. RAW before Stage 25).
         */
        suspend fun open(
            context: Context,
            uri: Uri,
            budgets: EngineBudgets = EngineBudgets(),
            io: CoroutineDispatcher = Dispatchers.IO,
        ): EngineSession? =
            withContext(io) {
                val reader = SourceReader(context.contentResolver)
                val info = reader.info(uri) ?: return@withContext null
                val preview = reader.decodePreview(info) ?: return@withContext null
                val cacheDir = File(context.cacheDir, "engine/${UUID.randomUUID()}").apply { mkdirs() }
                val native = NativeEngine(cacheDir.path, budgets.cpuTileBytes, budgets.gpuTileBytes)
                native.openImage(info.width, info.height)
                // The preview bitmap is converted to the working space natively and then
                // only its small Java object remains (pixels live in native memory).
                native.setPreview(preview.bitmap, info.format.id)
                Log.i(
                    TAG,
                    "opened ${info.width}x${info.height} ${info.mimeType} ${info.colorSpaceName} as ${info.format}, " +
                        "orientation ${info.orientation}, gain map ${preview.gainmap != null}",
                )
                EngineSession(native, reader, cacheDir, info, preview, budgets.decoderThreads)
            }

        private const val MB_SHIFT = 20
        private const val LAYER_PREVIEW_EDGE = 2048
        private const val LAYER_EXPORT_EDGE = 4096
        private const val REQUEST_SIZE = 3
        private const val THUMB_HEADER = 8

        private fun mb(bytes: Long) = bytes shr MB_SHIFT
    }
}
