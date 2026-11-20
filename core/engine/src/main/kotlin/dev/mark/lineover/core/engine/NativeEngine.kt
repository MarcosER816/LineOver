package dev.mark.lineover.core.engine

import android.graphics.Bitmap
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The JNI face of one native engine (`lo_engine*`, C API in core/engine-native
 * android/lo_api.h). Thread-safe like the C API; closing joins the render thread.
 */
@Suppress("TooManyFunctions") // one Kotlin method and one external per C API call
internal class NativeEngine(
    cacheDir: String,
    cpuBudget: Long,
    gpuBudget: Long,
) : AutoCloseable {
    private val loadingFlow = MutableStateFlow(false)

    /** True while sharp tiles in view are still loading (called from the render thread). */
    val loading: StateFlow<Boolean> = loadingFlow.asStateFlow()

    private val histogramFlow = MutableStateFlow<Histogram?>(null)

    /** The latest histogram of the edited preview, while enabled (at most 30 Hz). */
    val histogram: StateFlow<Histogram?> = histogramFlow.asStateFlow()

    @Volatile private var handle: Long

    init {
        System.loadLibrary("lineover_engine")
        handle = nativeCreate(cacheDir, cpuBudget, gpuBudget)
    }

    private fun h(): Long = handle.also { check(it != 0L) { "engine closed" } }

    fun openImage(
        width: Int,
        height: Int,
    ) = nativeOpenImage(h(), width, height)

    fun setPreview(
        bitmap: Bitmap,
        format: Int,
    ): Boolean = nativeSetPreview(h(), bitmap, format)

    @Suppress("LongParameterList")
    fun submitRegion(
        level: Int,
        tx: Int,
        ty: Int,
        bitmap: Bitmap,
        format: Int,
        regionX: Int,
        regionY: Int,
        rawLevelWidth: Int,
        rawLevelHeight: Int,
        orientation: Int,
    ): Boolean = nativeSubmitRegion(h(), level, tx, ty, bitmap, format, regionX, regionY, rawLevelWidth, rawLevelHeight, orientation)

    fun failTile(
        level: Int,
        tx: Int,
        ty: Int,
    ) = nativeFailTile(h(), level, tx, ty)

    /** Blocks up to [timeoutMs]; fills [out] with (level, tx, ty). */
    fun takeRequest(
        timeoutMs: Int,
        out: IntArray,
    ): Boolean {
        val handle = handle
        return handle != 0L && nativeTakeRequest(handle, timeoutMs, out)
    }

    /** Null on success, otherwise the parse error. */
    fun setGraph(json: String): String? = nativeSetGraph(h(), json)

    fun attachSurface(surface: Surface): String? = nativeAttachSurface(h(), surface)

    fun detachSurface() = nativeDetachSurface(h())

    fun setViewport(
        width: Int,
        height: Int,
    ) = nativeSetViewport(h(), width, height)

    @Suppress("LongParameterList")
    fun setTransform(
        a: Float,
        b: Float,
        c: Float,
        d: Float,
        e: Float,
        f: Float,
    ) = nativeSetTransform(h(), a, b, c, d, e, f)

    fun setSurround(
        r: Float,
        g: Float,
        b: Float,
    ) = nativeSetSurround(h(), r, g, b)

    fun stats(): EngineStats {
        val v = LongArray(STATS)
        nativeStats(h(), v)
        @Suppress("MagicNumber") // positions of the native stats array
        return EngineStats(
            cpuTileBytes = v[0],
            cpuTiles = v[1].toInt(),
            spilledTiles = v[2].toInt(),
            spillBytes = v[3],
            gpuBytes = v[4],
            gpuTiles = v[5].toInt(),
            pendingTiles = v[6].toInt(),
            frames = v[7],
            tilesDecoded = v[8],
            halfFloat = v[9] != 0L,
            displayP3 = v[10] != 0L,
            level = v[11].toInt(),
            p95FrameIntervalUs = v[12],
            p95DrawUs = v[13],
            p95UpdateUs = v[14],
            lastUpdateUs = v[15],
            updates = v[16],
            histogramSupported = v[17] != 0L,
        )
    }

    /** Average working RGB of the 5 x 5 preview pixels at (u, v), through the ops before [beforeOpType]. */
    fun sample(
        u: Float,
        v: Float,
        beforeOpType: Int,
    ): FloatArray? = nativeSample(h(), u, v, beforeOpType)

    /** The n x n source preview pixels around (u, v), RGBA working space, top row first. */
    fun sampleGrid(
        u: Float,
        v: Float,
        n: Int,
    ): FloatArray? = nativeSampleGrid(h(), u, v, n)

    /** Levels Auto: per-channel encoded 0.1 / 99.9 % percentiles (low R, high R, …) or null. */
    fun autoLevels(): FloatArray? = nativeAutoLevels(h())

    /**
     * [graph] on a small copy of the preview (<= 256 px when [maxEdge] <= 256, else <= 512 px):
     * [width, height] (8 bytes) then sRGB RGBA8.
     */
    fun renderThumbnail(
        graph: String,
        maxEdge: Int = 512,
    ): ByteArray? = nativeRenderThumbnail(h(), graph, maxEdge)

    /** Auto (Stage 11): temperature, tint, exposure, contrast, whites, blacks, vibrance; null before a preview. */
    fun autoSettings(): FloatArray? = nativeAutoSettings(h())

    /**
     * Registers a 3D LUT for every engine of the process (lut.h): [rgb] holds [size]^3 RGB
     * triples, red fastest; [domain] min r, g, b then max r, g, b. False when refused.
     */
    fun registerLut(
        id: String,
        size: Int,
        domain: FloatArray,
        rgb: FloatArray,
    ): Boolean = nativeRegisterLut(id, size, domain, rgb)

    fun unregisterLut(id: String) = nativeUnregisterLut(id)

    /** Stage 13: a layer image (process-wide) from a decoded bitmap in the engine [format]; [full] = the export set. */
    fun registerLayerImage(
        id: String,
        bitmap: Bitmap,
        format: Int,
        full: Boolean,
    ): Boolean = nativeRegisterLayerImage(id, bitmap, format, full)

    fun unregisterLayerImage(id: String) = nativeUnregisterLayerImage(id)

    fun releaseFullLayerImages() = nativeReleaseFullLayerImages()

    /** Renders a layered graph's composite for the canvas, or clears it (CPU, the calling thread). */
    fun renderComposite() = nativeRenderComposite(h())

    /** Stage 15: the selection recipe (JSON; empty = none), evaluated here; an error or null. */
    fun setSelection(json: String): String? = nativeSetSelection(h(), json)

    fun setAntsPhase(phase: Float) = nativeSetAntsPhase(h(), phase)

    fun selectionAt(
        u: Float,
        v: Float,
    ): Float = nativeSelectionAt(h(), u, v)

    /** [width, height, docWidth, docHeight, values...] or null without a selection. */
    fun selectionRaster(): FloatArray? = nativeSelectionRaster(h())

    fun registerMaskRaster(
        name: String,
        values: FloatArray,
        width: Int,
        height: Int,
    ) = nativeRegisterMaskRaster(name, values, width, height)

    fun unregisterMaskRaster(name: String) = nativeUnregisterMaskRaster(name)

    /** Stage 14: the composite shows layer [layerId]'s mask (0 none, 1 grey, 2 red overlay) from the next render. */
    fun setMaskView(
        layerId: String,
        view: Int,
    ) = nativeSetMaskView(h(), layerId, view)

    /** Auto (percentile stretch): (exposure EV, blacks) or null before a preview. */
    fun autoLight(): FloatArray? = nativeAutoLight(h())

    fun setShowOriginal(original: Boolean) = nativeSetShowOriginal(h(), original)

    /** [mode]: 0 off, 1 split (original left of [split] × width), 2 side by side. */
    fun setCompare(
        mode: Int,
        split: Float,
    ) = nativeSetCompare(h(), mode, split)

    fun setClipping(clipping: Boolean) = nativeSetClipping(h(), clipping)

    fun setHistogramEnabled(enabled: Boolean) = nativeSetHistogramEnabled(h(), enabled)

    /** Test hook: the histogram compute pass over [input] into [out] (1024 counts). */
    fun gpuHistogramSelfTest(
        input: FloatArray,
        width: Int,
        height: Int,
        out: IntArray,
    ): String? = nativeGpuHistogramSelfTest(h(), input, width, height, out)

    /** CPU full-resolution render of a decoded tile into [out]; its (width, height) or null. */
    fun renderTileCpu(
        level: Int,
        tx: Int,
        ty: Int,
        out: FloatArray,
    ): IntArray? = nativeRenderTileCpu(h(), level, tx, ty, out)

    /** The edited image's size: the geometry's output, else the image (width, height). */
    fun outputSize(): IntArray = nativeOutputSize(h())

    /** The level-0 source tiles output tile ([tx], [ty]) still needs: (tx, ty) pairs. */
    fun outputTileSources(
        tx: Int,
        ty: Int,
    ): IntArray = nativeOutputTileSources(h(), tx, ty)

    /** Output tile ([tx], [ty]) on the CPU (ops, one resample) into [out]; (width, height) or null. */
    fun renderOutputTile(
        tx: Int,
        ty: Int,
        out: FloatArray,
    ): IntArray? = nativeRenderOutputTile(h(), tx, ty, out)

    /** Line segments of the preview, 5 floats each (x1, y1, x2, y2 normalised, width); null before a preview. */
    fun lineSegments(): FloatArray? = nativeLineSegments(h())

    /** Tilt of the dominant near-horizontal line in degrees (clockwise positive), or null. */
    fun horizon(): Float? = nativeHorizon(h())?.get(0)

    /** (caRed, caBlue) undoing lateral chromatic aberration, or null. */
    fun chromaticAberration(): FloatArray? = nativeChromaticAberration(h())

    /** Test hook: [graph] on the GPU over [input], sRGB display transform into [out] (RGBA8). */
    fun gpuSelfTest(
        input: FloatArray,
        width: Int,
        height: Int,
        graph: String,
        out: ByteArray,
    ): String? = nativeGpuSelfTest(h(), input, width, height, graph, out)

    override fun close() {
        val h = handle
        handle = 0
        if (h != 0L) nativeDestroy(h)
    }

    /** Called by the native render thread. */
    @Suppress("unused")
    private fun onLoadingChanged(loading: Boolean) {
        loadingFlow.value = loading
    }

    /** Called by the native render thread with a fresh array of 1024 counts. */
    @Suppress("unused")
    private fun onHistogram(bins: IntArray) {
        histogramFlow.value = Histogram(bins)
    }

    private external fun nativeAutoLight(engine: Long): FloatArray?

    private external fun nativeAutoLevels(engine: Long): FloatArray?

    private external fun nativeRenderThumbnail(
        engine: Long,
        graph: String,
        maxEdge: Int,
    ): ByteArray?

    private external fun nativeAutoSettings(engine: Long): FloatArray?

    private external fun nativeRegisterLut(
        id: String,
        size: Int,
        domain: FloatArray,
        rgb: FloatArray,
    ): Boolean

    private external fun nativeUnregisterLut(id: String)

    private external fun nativeRegisterLayerImage(
        id: String,
        bitmap: Bitmap,
        format: Int,
        full: Boolean,
    ): Boolean

    private external fun nativeUnregisterLayerImage(id: String)

    private external fun nativeReleaseFullLayerImages()

    private external fun nativeRenderComposite(engine: Long)

    private external fun nativeSetSelection(
        engine: Long,
        json: String,
    ): String?

    private external fun nativeSetAntsPhase(
        engine: Long,
        phase: Float,
    )

    private external fun nativeSelectionAt(
        engine: Long,
        u: Float,
        v: Float,
    ): Float

    private external fun nativeSelectionRaster(engine: Long): FloatArray?

    private external fun nativeRegisterMaskRaster(
        name: String,
        values: FloatArray,
        width: Int,
        height: Int,
    )

    private external fun nativeUnregisterMaskRaster(name: String)

    private external fun nativeSetMaskView(
        engine: Long,
        layerId: String,
        view: Int,
    )

    private external fun nativeSample(
        engine: Long,
        u: Float,
        v: Float,
        before: Int,
    ): FloatArray?

    private external fun nativeSampleGrid(
        engine: Long,
        u: Float,
        v: Float,
        n: Int,
    ): FloatArray?

    private external fun nativeSetShowOriginal(
        engine: Long,
        original: Boolean,
    )

    private external fun nativeSetCompare(
        engine: Long,
        mode: Int,
        split: Float,
    )

    private external fun nativeSetClipping(
        engine: Long,
        clipping: Boolean,
    )

    private external fun nativeSetHistogramEnabled(
        engine: Long,
        enabled: Boolean,
    )

    private external fun nativeGpuHistogramSelfTest(
        engine: Long,
        input: FloatArray,
        width: Int,
        height: Int,
        out: IntArray,
    ): String?

    private external fun nativeCreate(
        cacheDir: String,
        cpuBudget: Long,
        gpuBudget: Long,
    ): Long

    private external fun nativeDestroy(engine: Long)

    private external fun nativeOpenImage(
        engine: Long,
        width: Int,
        height: Int,
    )

    private external fun nativeSetPreview(
        engine: Long,
        bitmap: Bitmap,
        format: Int,
    ): Boolean

    @Suppress("LongParameterList")
    private external fun nativeSubmitRegion(
        engine: Long,
        level: Int,
        tx: Int,
        ty: Int,
        bitmap: Bitmap,
        format: Int,
        regionX: Int,
        regionY: Int,
        rawLevelWidth: Int,
        rawLevelHeight: Int,
        orientation: Int,
    ): Boolean

    private external fun nativeFailTile(
        engine: Long,
        level: Int,
        tx: Int,
        ty: Int,
    )

    private external fun nativeTakeRequest(
        engine: Long,
        timeoutMs: Int,
        out: IntArray,
    ): Boolean

    private external fun nativeSetGraph(
        engine: Long,
        json: String,
    ): String?

    private external fun nativeAttachSurface(
        engine: Long,
        surface: Surface,
    ): String?

    private external fun nativeDetachSurface(engine: Long)

    private external fun nativeSetViewport(
        engine: Long,
        width: Int,
        height: Int,
    )

    @Suppress("LongParameterList")
    private external fun nativeSetTransform(
        engine: Long,
        a: Float,
        b: Float,
        c: Float,
        d: Float,
        e: Float,
        f: Float,
    )

    private external fun nativeSetSurround(
        engine: Long,
        r: Float,
        g: Float,
        b: Float,
    )

    private external fun nativeStats(
        engine: Long,
        out: LongArray,
    )

    private external fun nativeRenderTileCpu(
        engine: Long,
        level: Int,
        tx: Int,
        ty: Int,
        out: FloatArray,
    ): IntArray?

    private external fun nativeOutputSize(engine: Long): IntArray

    private external fun nativeOutputTileSources(
        engine: Long,
        tx: Int,
        ty: Int,
    ): IntArray

    private external fun nativeRenderOutputTile(
        engine: Long,
        tx: Int,
        ty: Int,
        out: FloatArray,
    ): IntArray?

    private external fun nativeLineSegments(engine: Long): FloatArray?

    private external fun nativeHorizon(engine: Long): FloatArray?

    private external fun nativeChromaticAberration(engine: Long): FloatArray?

    @Suppress("LongParameterList")
    private external fun nativeGpuSelfTest(
        engine: Long,
        input: FloatArray,
        width: Int,
        height: Int,
        graph: String,
        out: ByteArray,
    ): String?

    private companion object {
        const val STATS = 18
    }
}

/** A snapshot of the engine's memory and work (for logging, tests and the debug overlay). */
data class EngineStats(
    val cpuTileBytes: Long,
    val cpuTiles: Int,
    val spilledTiles: Int,
    val spillBytes: Long,
    val gpuBytes: Long,
    val gpuTiles: Int,
    val pendingTiles: Int,
    val frames: Long,
    val tilesDecoded: Long,
    val halfFloat: Boolean,
    val displayP3: Boolean,
    val level: Int,
    val p95FrameIntervalUs: Long,
    val p95DrawUs: Long,
    /** Preview update latency, setGraph -> the new preview presented (P95 / last), µs. */
    val p95UpdateUs: Long = 0,
    val lastUpdateUs: Long = 0,
    val updates: Long = 0,
    val histogramSupported: Boolean = false,
)
