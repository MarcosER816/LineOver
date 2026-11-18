package dev.mark.lineover.core.engine

import android.content.Context
import android.net.Uri
import android.os.Debug
import android.util.Log
import android.view.Surface
import dev.mark.lineover.core.io.project.PreviewImage
import dev.mark.lineover.core.io.source.SourceInfo
import dev.mark.lineover.core.io.source.SourceReader
import dev.mark.lineover.core.io.source.TileDecoder
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.EditGraphJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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

    /** Hands the graph to the render thread (it re-renders the preview and visible tiles). */
    fun setGraph(graph: EditGraph): String? = if (closed) "closed" else native.setGraph(EditGraphJson.encode(graph))

    fun stats(): EngineStats = native.stats()

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
     * Full-resolution render (export only, Stage 12 writes the files): every level-0 tile
     * is decoded, run through the ops on the CPU reference path and handed to [sink] in
     * working-space RGBA. Cancellable between tiles.
     */
    suspend fun renderFullResolution(
        sink: suspend (tx: Int, ty: Int, width: Int, height: Int, pixels: FloatArray) -> Unit,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) = withContext(dispatcher) {
        val decoder = reader.openTiles(info) ?: error("no region decoder for ${info.uri}")
        decoder.use {
            val tilesX = (info.width + TileDecoder.TILE - 1) / TileDecoder.TILE
            val tilesY = (info.height + TileDecoder.TILE - 1) / TileDecoder.TILE
            val buffer = FloatArray(TileDecoder.TILE * TileDecoder.TILE * CHANNELS)
            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) {
                    currentCoroutineContext().ensureActive()
                    decodeInto(decoder, 0, tx, ty)
                    val size = native.renderTileCpu(0, tx, ty, buffer) ?: error("tile $tx,$ty could not be rendered")
                    sink(tx, ty, size[0], size[1], buffer)
                }
            }
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
        private const val REQUEST_SIZE = 3

        private fun mb(bytes: Long) = bytes shr MB_SHIFT
    }
}
