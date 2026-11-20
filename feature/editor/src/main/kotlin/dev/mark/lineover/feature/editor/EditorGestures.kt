package dev.mark.lineover.feature.editor

import dev.mark.lineover.feature.editor.canvas.CanvasGestureListener

/** What CanvasView reports, as [EditorViewModel] intents (one finger, holds, swipes, picks). */
internal class EditorGestures(
    private val state: () -> EditorState,
    private val onIntent: (EditorIntent) -> Unit,
) : CanvasGestureListener {
    override fun onHoldChanged(holding: Boolean) = onIntent(EditorIntent.HoldOriginal(holding))

    override fun onSwipeSelect(steps: Int) = onIntent(EditorIntent.SwipeSelect(steps))

    override fun onSwipeAdjust(fraction: Float) = onIntent(EditorIntent.SwipeAdjust(fraction))

    override fun onSwipeActive(active: Boolean) = onIntent(EditorIntent.SwipeActive(active))

    override fun onEyedropperMove(
        viewX: Float,
        viewY: Float,
        u: Float,
        v: Float,
    ) = onIntent(EditorIntent.EyedropperMove(viewX, viewY, u, v))

    override fun onEyedropperEnd(
        u: Float?,
        v: Float?,
    ) = onIntent(EditorIntent.EyedropperEnd(u, v))

    override fun onTargetedStart(
        u: Float,
        v: Float,
    ) = onIntent(EditorIntent.TargetedStart(u, v))

    override fun onTargetedDrag(fraction: Float) = onIntent(EditorIntent.TargetedDrag(fraction))

    override fun onTargetedEnd() = onIntent(EditorIntent.TargetedEnd)

    override fun onMultiFingerTap(fingers: Int) = onIntent(EditorIntent.MultiFingerTap(fingers))

    // One finger paints a targeted mask (Stage 14) or makes a selection (Stage 15).
    private val selecting get() = state().selectTool != null

    override fun onPaintStart(
        u: Float,
        v: Float,
    ) = onIntent(
        if (selecting) EditorIntent.Select(SelectAction.Start(u, v)) else EditorIntent.Layers(LayerAction.PaintStart(u, v)),
    )

    override fun onPaintMove(
        u: Float,
        v: Float,
    ) = onIntent(if (selecting) EditorIntent.Select(SelectAction.Move(u, v)) else EditorIntent.Layers(LayerAction.PaintMove(u, v)))

    override fun onPaintEnd() =
        onIntent(if (selecting) EditorIntent.Select(SelectAction.End) else EditorIntent.Layers(LayerAction.PaintEnd))

    override fun onPaintLoupe(
        viewX: Float,
        viewY: Float,
        u: Float,
        v: Float,
    ) {
        if (selecting) onIntent(EditorIntent.Select(SelectAction.Loupe(viewX, viewY, u, v)))
    }
}
