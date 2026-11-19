package dev.mark.lineover.feature.editor.canvas

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
import dev.mark.lineover.core.engine.ColorMath
import dev.mark.lineover.core.engine.CompareMode
import dev.mark.lineover.core.engine.EngineSession
import dev.mark.lineover.core.engine.EngineStats
import dev.mark.lineover.core.engine.Histogram
import dev.mark.lineover.core.engine.NativeOp
import dev.mark.lineover.core.model.AutoResult
import dev.mark.lineover.core.model.CubeLut
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.LineSegment
import dev.mark.lineover.core.model.PhotoExif
import dev.mark.lineover.core.model.Project
import kotlinx.coroutines.flow.StateFlow

/** What the Info panel shows about the open original. */
data class ImageSummary(
    val width: Int,
    val height: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val colorSpace: String?,
    /** How pixels reach the engine: "8-bit sRGB", "8-bit Display P3" or "16-bit float". */
    val encoding: String,
    /** The original carries an Ultra HDR gain map (kept for HDR display and export). */
    val ultraHdr: Boolean,
    /** Camera and lens from the EXIF (lens profiles, Stage 9). */
    val exif: PhotoExif = PhotoExif(),
)

/** The image engine as the editor sees it: draws the canvas, takes edits, reports loading. */
interface EditorCanvas :
    CanvasRenderer,
    AutoCloseable {
    val summary: ImageSummary

    /** True while sharp tiles for the current view are still loading. */
    val loading: StateFlow<Boolean>

    /** Null when accepted, otherwise why not. */
    fun setGraph(graph: EditGraph): String?

    /** The edited preview's histogram while enabled (null until the first one). */
    val histogram: StateFlow<Histogram?>

    fun setHistogramEnabled(enabled: Boolean)

    fun setClipping(clipping: Boolean)

    /** [split]: where the original ends in split mode, as a fraction of the view width. */
    fun setCompare(
        mode: CompareMode,
        split: Float,
    )

    /** Auto (Light): (exposure EV, blacks) from the preview's percentiles, null if unknown. */
    fun autoLight(): Pair<Float, Float>?

    /** Engine counters for the debug overlay (null without an engine). */
    fun stats(): EngineStats?

    /** Levels → Auto: per-channel encoded percentiles of the preview (6 values) or null. */
    fun autoLevels(): FloatArray?

    /** [graph] rendered small (<= 512 px, sRGB) for thumbnails; blocks, call off the main thread. */
    fun thumbnail(graph: EditGraph): android.graphics.Bitmap?

    /** [graph] from the <= 256 px proxy (preset, profile and LUT strips). Off the main thread. */
    fun smallThumbnail(graph: EditGraph): android.graphics.Bitmap? = thumbnail(graph)

    /** Auto (Stage 11): the engine's proposal from the preview, null before it. Off the main thread. */
    fun autoSettings(): AutoResult? = null

    /** Registers a 3D LUT so graphs can name [id]; false when refused. */
    fun registerLut(
        id: String,
        lut: CubeLut,
    ): Boolean = false

    /**
     * Eyedropper: the white balance (K, tint) that makes the 5 x 5 patch at image point
     * ([u], [v]) neutral, sampled before white balance. Blocks: call off the main thread.
     */
    fun whiteBalanceAt(
        u: Float,
        v: Float,
    ): Pair<Float, Float>?

    /** Targeted adjustment: the 8 HSL band weights of the colour at (u, v) as the mixer sees it. Off the main thread. */
    fun hslWeightsAt(
        u: Float,
        v: Float,
    ): FloatArray?

    /** The 9 x 9 original pixels around (u, v), RGBA working space, top row first (eyedropper loupe). Off the main thread. */
    fun loupeAt(
        u: Float,
        v: Float,
    ): FloatArray?

    /** Upright: line segments of the preview (normalised source coordinates). Off the main thread. */
    fun lineSegments(): List<LineSegment>? = null

    /** Auto straighten: the dominant near-horizontal line's tilt in degrees (clockwise positive). Off the main thread. */
    fun horizon(): Float? = null

    /** Chromatic aberration Auto: (red, blue) radial scale - 1. Off the main thread. */
    fun chromaticAberration(): Pair<Float, Float>? = null
}

/** Opens the engine for a project (a fake in JVM tests, where there is no native engine). */
fun interface EditorCanvasFactory {
    /** Null when the original cannot be shown yet (TIFF before Stage 12, RAW before Stage 25). */
    suspend fun open(project: Project): EditorCanvas?
}

/** The real engine: native render thread, tiles, GPU ops. */
class EngineCanvasFactory(
    private val context: Context,
) : EditorCanvasFactory {
    override suspend fun open(project: Project): EditorCanvas? =
        try {
            EngineSession.open(context.applicationContext, Uri.parse(project.sourceUri))?.let(::EngineCanvas)
        } catch (e: UnsatisfiedLinkError) {
            // No native engine for this ABI (e.g. the JVM test environment): no canvas.
            Log.e("LineOverEngine", "native engine unavailable", e)
            null
        }
}

@Suppress("TooManyFunctions") // one method per EditorCanvas call
private class EngineCanvas(
    private val session: EngineSession,
) : EditorCanvas {
    override val imageWidth: Int get() = session.info.width
    override val imageHeight: Int get() = session.info.height
    override val loading: StateFlow<Boolean> get() = session.loading
    override val summary: ImageSummary =
        ImageSummary(
            width = session.info.width,
            height = session.info.height,
            previewWidth = session.preview.bitmap.width,
            previewHeight = session.preview.bitmap.height,
            colorSpace = session.info.colorSpaceName,
            encoding =
                when (session.info.format.id) {
                    0 -> "8-bit sRGB"
                    1 -> "8-bit Display P3"
                    else -> "16-bit float, linear"
                },
            ultraHdr = session.preview.gainmap != null,
            exif = session.info.exif,
        )

    override fun attach(surface: Surface) {
        session.attachSurface(surface)
    }

    override fun resize(
        width: Int,
        height: Int,
    ) = session.setViewport(width, height)

    override fun detach() = session.detachSurface()

    override fun setTransform(
        a: Float,
        b: Float,
        c: Float,
        d: Float,
        e: Float,
        f: Float,
    ) = session.setTransform(a, b, c, d, e, f)

    override fun setSurround(argb: Int) =
        session.setSurround(
            ((argb shr RED) and BYTE) / MAX,
            ((argb shr GREEN) and BYTE) / MAX,
            (argb and BYTE) / MAX,
        )

    override fun setGraph(graph: EditGraph): String? = session.setGraph(graph)

    override val histogram: StateFlow<Histogram?> get() = session.histogram

    override fun setHistogramEnabled(enabled: Boolean) = session.setHistogramEnabled(enabled)

    override fun setClipping(clipping: Boolean) = session.setClipping(clipping)

    override fun setCompare(
        mode: CompareMode,
        split: Float,
    ) = session.setCompare(mode, split)

    override fun setShowOriginal(original: Boolean) = session.setShowOriginal(original)

    override fun autoLight(): Pair<Float, Float>? = session.autoLight()

    override fun stats(): EngineStats? = session.stats()

    override fun autoLevels(): FloatArray? = session.autoLevels()

    override fun thumbnail(graph: EditGraph): android.graphics.Bitmap? = session.thumbnail(graph)

    override fun smallThumbnail(graph: EditGraph): android.graphics.Bitmap? = session.thumbnail(graph, SMALL_THUMBNAIL)

    override fun autoSettings(): AutoResult? = session.autoSettings()

    override fun registerLut(
        id: String,
        lut: CubeLut,
    ): Boolean = session.registerLut(id, lut)

    override fun whiteBalanceAt(
        u: Float,
        v: Float,
    ): Pair<Float, Float>? = session.sample(u, v, NativeOp.WhiteBalance)?.let(ColorMath::whiteBalanceForNeutral)

    override fun hslWeightsAt(
        u: Float,
        v: Float,
    ): FloatArray? = session.sample(u, v, NativeOp.HslMixer)?.let(ColorMath::hslWeights)

    override fun loupeAt(
        u: Float,
        v: Float,
    ): FloatArray? = session.sampleGrid(u, v, LOUPE_PIXELS)

    override fun lineSegments(): List<LineSegment>? = session.lineSegments()

    override fun horizon(): Float? = session.horizon()

    override fun chromaticAberration(): Pair<Float, Float>? = session.chromaticAberration()

    override fun close() = session.close()

    private companion object {
        const val RED = 16
        const val GREEN = 8
        const val BYTE = 0xFF
        const val MAX = 255f
        const val LOUPE_PIXELS = 9
        const val SMALL_THUMBNAIL = 256
    }
}
