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
internal class NativeEngine(
    cacheDir: String,
    cpuBudget: Long,
    gpuBudget: Long,
) : AutoCloseable {
    private val loadingFlow = MutableStateFlow(false)

    /** True while sharp tiles in view are still loading (called from the render thread). */
    val loading: StateFlow<Boolean> = loadingFlow.asStateFlow()

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
        )
    }

    /** CPU full-resolution render of a decoded tile into [out]; its (width, height) or null. */
    fun renderTileCpu(
        level: Int,
        tx: Int,
        ty: Int,
        out: FloatArray,
    ): IntArray? = nativeRenderTileCpu(h(), level, tx, ty, out)

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
        const val STATS = 14
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
)
