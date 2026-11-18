package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.model.GeometryAdjustments
import dev.mark.lineover.core.model.GeometryParam
import dev.mark.lineover.core.model.Upright
import dev.mark.lineover.core.model.UprightMode
import dev.mark.lineover.feature.editor.canvas.CropGestureListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Crop and Optics intents of [EditorViewModel] (Stage 9): synchronous changes go through
 * [reduceGeometry]; the analyses (Upright segments, the horizon, chromatic aberration) run
 * on the render dispatcher, then apply as one named History step.
 */
internal class EditorGeometryActions(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val state: () -> EditorState,
    private val reduce: (label: String?, block: EditorState.() -> EditorState) -> Unit,
    private val endEdit: () -> Unit,
    private val message: (String) -> Unit,
) {
    fun onAction(action: GeometryAction) {
        when (action) {
            is GeometryAction.Upright -> upright(action.mode)
            is GeometryAction.AutoStraighten -> autoStraighten()
            is GeometryAction.AutoChromatic -> autoChromatic()
            is GeometryAction.Crop -> crop(action)
            is GeometryAction.Corners -> corners(action)
            is GeometryAction.Set, is GeometryAction.Dragging, is GeometryAction.Overlay -> reduce(null) { reduceGeometry(action) }
            else -> reduce(labelOf(action)) { reduceGeometry(action) }
        }
    }

    /** Crop gestures from the canvas, as actions. */
    val gestures: CropGestureListener =
        object : CropGestureListener {
            override fun onCropRect(
                rect: FloatArray,
                done: Boolean,
            ) = onAction(GeometryAction.Crop(rect, done))

            override fun onCropDragging(dragging: Boolean) = onAction(GeometryAction.Dragging(dragging))

            override fun onCropReset() = onAction(GeometryAction.ResetCrop)

            override fun onCorners(
                corners: FloatArray,
                done: Boolean,
            ) = onAction(GeometryAction.Corners(corners, done))

            override fun onGuideLine(line: FloatArray) =
                onAction(
                    GeometryAction.AddGuide(
                        dev.mark.lineover.core.model
                            .GuideLine(line[0], line[1], line[2], line[3]),
                    ),
                )
        }

    private fun crop(action: GeometryAction.Crop) {
        reduce(null) { reduceGeometry(action) }
        if (action.done) endEdit()
    }

    private fun corners(action: GeometryAction.Corners) {
        reduce(null) { reduceGeometry(action) }
        if (action.done) endEdit()
    }

    private fun labelOf(action: GeometryAction): String? =
        when (action) {
            is GeometryAction.Rotate -> if (action.clockwise) "Rotate right" else "Rotate left"
            is GeometryAction.Flip -> if (action.horizontal) "Flip horizontal" else "Flip vertical"
            is GeometryAction.Aspect -> "Aspect ${action.aspect.label}"
            is GeometryAction.SwapOrientation -> "Crop orientation"
            is GeometryAction.ResetCrop -> "Reset crop"
            is GeometryAction.Reset -> "Reset geometry"
            is GeometryAction.PerspectiveCrop -> if (action.on) "Perspective crop" else "Perspective crop off"
            is GeometryAction.AddGuide -> "Guided Upright"
            is GeometryAction.ClearGuides -> "Clear guides"
            else -> null
        }

    /** Off and Guided apply at once; the others analyse the preview's line segments first. */
    private fun upright(mode: UprightMode) {
        val s = state()
        val size = s.sourceSize
        when {
            mode == UprightMode.Off -> {
                reduce("Upright off") { withGeometry(geometry.withUpright(mode, 0f, 0f, 0f)) }
            }

            mode == UprightMode.Guided && size != null -> {
                val r = Upright.solveGuided(s.geometry.guides, size.first, size.second)
                reduce("Upright Guided") { withGeometry(geometry.withUpright(mode, r.pitch, r.yaw, r.roll)) }
            }

            s.canvas == null || size == null -> {
                message("Upright needs the photo to finish loading")
            }

            else -> {
                analyse(mode, requireNotNull(s.canvas), size)
            }
        }
    }

    private fun analyse(
        mode: UprightMode,
        canvas: dev.mark.lineover.feature.editor.canvas.EditorCanvas,
        size: Pair<Int, Int>,
    ) {
        scope.launch {
            val segments = withContext(dispatcher) { canvas.lineSegments() }
            if (segments == null) {
                message("Upright needs the photo to finish loading")
                return@launch
            }
            val r = withContext(dispatcher) { Upright.solve(mode, segments, size.first, size.second) }
            if (r.fellBack) {
                message(
                    if (r.roll == 0f) {
                        "Upright: no strong lines found"
                    } else {
                        "Upright ${mode.label}: out of range (±${Upright.LIMIT.toInt()}°), levelled instead"
                    },
                )
            }
            reduce("Upright ${mode.label}") { withGeometry(geometry.withUpright(mode, r.pitch, r.yaw, r.roll)) }
        }
    }

    private fun GeometryAdjustments.withUpright(
        mode: UprightMode,
        pitch: Float,
        yaw: Float,
        roll: Float,
    ) = copy(upright = mode, uprightPitch = pitch, uprightYaw = yaw, uprightRoll = roll)

    private fun autoStraighten() {
        val canvas = state().canvas ?: return message("Auto needs the photo to finish loading")
        scope.launch {
            val tilt = withContext(dispatcher) { canvas.horizon() }
            if (tilt == null) {
                message("No horizon found: drag the ruler")
                return@launch
            }
            // The line leans clockwise by `tilt`: turning the photo back levels it.
            reduce("Auto straighten") { withGeometry(geometry.with(GeometryParam.Straighten, -tilt)) }
        }
    }

    private fun autoChromatic() {
        val canvas = state().canvas ?: return message("Auto needs the photo to finish loading")
        scope.launch {
            val ca = withContext(dispatcher) { canvas.chromaticAberration() }
            if (ca == null) {
                message("Not enough edges to measure fringes")
                return@launch
            }
            val scale = SLIDER_MAX / GeometryAdjustments.MAX_CA.toFloat()
            reduce("Chromatic aberration: auto") {
                withGeometry(
                    geometry
                        .with(GeometryParam.ChromaticRed, ca.first * scale)
                        .with(GeometryParam.ChromaticBlue, ca.second * scale),
                )
            }
        }
    }

    private companion object {
        const val SLIDER_MAX = 100f
    }
}
