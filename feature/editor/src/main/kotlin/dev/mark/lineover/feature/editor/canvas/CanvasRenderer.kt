package dev.mark.lineover.feature.editor.canvas

import android.view.Surface

/**
 * What draws the photo into the canvas surface: the native engine in the app
 * (EngineCanvas), a recorder in JVM tests. Every call comes from the UI thread and must
 * return quickly (the work happens on the engine's render thread).
 */
interface CanvasRenderer {
    /** Size of the oriented original in pixels (what the gestures transform). */
    val imageWidth: Int
    val imageHeight: Int

    fun attach(surface: Surface)

    fun resize(
        width: Int,
        height: Int,
    )

    fun detach()

    /** view = (a·x + c·y + e, b·x + d·y + f) for original pixel (x, y). Called per gesture event. */
    @Suppress("LongParameterList")
    fun setTransform(
        a: Float,
        b: Float,
        c: Float,
        d: Float,
        e: Float,
        f: Float,
    )

    /** Surround colour (ARGB, sRGB). */
    fun setSurround(argb: Int)
}
