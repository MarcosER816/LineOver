package dev.mark.lineover.feature.editor

import android.view.Surface
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
