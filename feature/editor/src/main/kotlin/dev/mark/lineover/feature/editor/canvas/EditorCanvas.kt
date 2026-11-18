package dev.mark.lineover.feature.editor.canvas

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
import dev.mark.lineover.core.engine.EngineSession
import dev.mark.lineover.core.model.EditGraph
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

    override fun close() = session.close()

    private companion object {
        const val RED = 16
        const val GREEN = 8
        const val BYTE = 0xFF
        const val MAX = 255f
    }
}
