// Rect (left, top, right, bottom) and corner positions are array indices throughout.
@file:Suppress("MagicNumber")

package dev.mark.lineover.feature.editor

import dev.mark.lineover.core.model.ComposedGeometry
import dev.mark.lineover.core.model.CropAspect
import dev.mark.lineover.core.model.CropMath
import dev.mark.lineover.core.model.CropOverlay
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.GeometryAdjustments
import dev.mark.lineover.core.model.GeometryMath
import dev.mark.lineover.core.model.GeometryParam
import dev.mark.lineover.core.model.GuideLine
import dev.mark.lineover.core.model.OpParams
import dev.mark.lineover.core.model.UprightMode
import dev.mark.lineover.core.model.editOps
import dev.mark.lineover.feature.editor.canvas.CropFrame
import dev.mark.lineover.feature.editor.canvas.CropMode

// Crop and Optics in the editor (Stage 9), as pure functions of the state.

internal const val CROP_TOOL = "crop.crop"
internal const val ASPECT_TOOL = "crop.aspect_ratios"
internal const val STRAIGHTEN_TOOL = "crop.straighten"
internal const val ROTATE_TOOL = "crop.rotate"
internal const val FLIP_TOOL = "crop.flip"
internal const val PERSPECTIVE_CROP_TOOL = "crop.perspective_crop"
internal const val UPRIGHT_TOOL = "optics.upright"
internal const val DISTORTION_TOOL = "optics.lens_distortion"
internal const val CHROMATIC_TOOL = "optics.chromatic_aberration"
internal const val LENS_VIGNETTING_TOOL = "optics.lens_vignetting"

internal val CROP_TOOLS = setOf(CROP_TOOL, ASPECT_TOOL, STRAIGHTEN_TOOL, ROTATE_TOOL, FLIP_TOOL, PERSPECTIVE_CROP_TOOL)
internal val OPTICS_TOOLS = setOf(UPRIGHT_TOOL, DISTORTION_TOOL, CHROMATIC_TOOL, LENS_VIGNETTING_TOOL)

/** The tools that show the whole, uncropped frame with the crop drawn over it. */
private val FRAME_TOOLS = setOf(CROP_TOOL, ASPECT_TOOL, STRAIGHTEN_TOOL, ROTATE_TOOL, FLIP_TOOL, PERSPECTIVE_CROP_TOOL)

/** One Crop / Optics change. */
sealed interface GeometryAction {
    data class Set(
        val param: GeometryParam,
        val value: Float,
    ) : GeometryAction

    data class Aspect(
        val aspect: CropAspect,
    ) : GeometryAction

    /** Portrait <-> landscape of the locked ratio. */
    data object SwapOrientation : GeometryAction

    data class Custom(
        val width: Float,
        val height: Float,
    ) : GeometryAction

    data class Overlay(
        val overlay: CropOverlay,
    ) : GeometryAction

    data class Constrain(
        val on: Boolean,
    ) : GeometryAction

    data class Rotate(
        val clockwise: Boolean,
    ) : GeometryAction

    data class Flip(
        val horizontal: Boolean,
    ) : GeometryAction

    /** The crop rect from the canvas handles ([done] on lift). */
    data class Crop(
        val rect: FloatArray,
        val done: Boolean,
    ) : GeometryAction {
        override fun equals(other: Any?) = other is Crop && rect.contentEquals(other.rect) && done == other.done

        override fun hashCode() = rect.contentHashCode() * 31 + done.hashCode()
    }

    data class Dragging(
        val dragging: Boolean,
    ) : GeometryAction

    data object ResetCrop : GeometryAction

    data class PerspectiveCrop(
        val on: Boolean,
    ) : GeometryAction

    data class Corners(
        val corners: FloatArray,
        val done: Boolean,
    ) : GeometryAction {
        override fun equals(other: Any?) = other is Corners && corners.contentEquals(other.corners) && done == other.done

        override fun hashCode() = corners.contentHashCode() * 31 + done.hashCode()
    }

    data class Upright(
        val mode: UprightMode,
    ) : GeometryAction

    data class AddGuide(
        val line: GuideLine,
    ) : GeometryAction

    data object ClearGuides : GeometryAction

    data object AutoStraighten : GeometryAction

    data object AutoChromatic : GeometryAction

    data object Reset : GeometryAction

    /** Lens profile on (matched from the EXIF, or [lens] by name) or off. */
    data class UseProfile(
        val on: Boolean,
        val lens: String? = null,
    ) : GeometryAction

    data class ProfileChromatic(
        val on: Boolean,
    ) : GeometryAction

    /** Downloads the lens profile database (the user tapped Download). */
    data object DownloadProfiles : GeometryAction

    /** Finds the lenses the database has for this photo (the Lens profile section opened). */
    data object LoadLensCandidates : GeometryAction
}

/** The photo's size the geometry is composed for (oriented original pixels). */
internal val EditorState.sourceSize: Pair<Int, Int>?
    get() =
        image?.let { it.width to it.height }
            ?: graph.layers
                .firstOrNull()
                ?.ops
                ?.filterIsInstance<OpParams.Geometry>()
                ?.firstOrNull()
                ?.let { it.sourceWidth to it.sourceHeight }

/** The geometry op of the current Crop / Optics values (null when they change nothing). */
internal val EditorState.geometryOp: OpParams.Geometry?
    get() = sourceSize?.let { (w, h) -> geometry.toOp(w, h) }

/** [next] as the Crop / Optics values, the background ops rebuilt with it first. */
internal fun EditorState.withGeometry(next: GeometryAdjustments): EditorState {
    val updated = copy(geometry = next)
    return updated.copy(graph = graph.withBackgroundOps(editOps(light, color, grade, updated.geometryOp)))
}

/** The composed geometry of the current values (null without a photo). */
internal val EditorState.composed: ComposedGeometry?
    get() = sourceSize?.let { (w, h) -> GeometryMath.compose(geometry, w, h) }

/** What one finger edits on the canvas for the open tool, or null. */
internal val EditorState.cropMode: CropMode?
    get() =
        when (selectedToolId) {
            UPRIGHT_TOOL -> if (geometry.upright == UprightMode.Guided) CropMode.Guides else null
            PERSPECTIVE_CROP_TOOL -> if (geometry.hasPerspectiveCrop) CropMode.Corners else CropMode.Crop
            in FRAME_TOOLS -> CropMode.Crop
            else -> null
        }

/**
 * The graph the canvas draws: while cropping, the whole frame (no crop, no constrain) so
 * the crop can grow again; while drawing Upright guides, the photo without geometry.
 */
internal val EditorState.canvasGraph: EditGraph
    get() {
        val shown = shownGraph
        val mode = cropMode ?: return shown
        if (preview != null) return shown
        val (w, h) = sourceSize ?: return shown
        val op =
            when (mode) {
                CropMode.Guides -> null
                else -> frameOnly(geometry).toOp(w, h)
            }
        return shown.withBackgroundOps(editOps(light, color, grade, op))
    }

private fun frameOnly(g: GeometryAdjustments) =
    g.copy(cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f, perspectiveCorners = emptyList(), constrain = false)

/** The canvas image size: the drawn graph's output (the edit, or the frame while cropping). */
internal val EditorState.canvasSize: Pair<Int, Int>?
    get() {
        val op =
            canvasGraph.layers
                .firstOrNull()
                ?.ops
                ?.filterIsInstance<OpParams.Geometry>()
                ?.firstOrNull()
        return op?.let { it.outputWidth to it.outputHeight } ?: image?.let { it.width to it.height }
    }

/** The crop the canvas handles show (normalised frame), with the ratio they keep. */
internal val EditorState.cropFrame: CropFrame?
    get() {
        val mode = cropMode ?: return null
        val c = composed ?: return CropFrame(mode)
        val rect = c.crop
        return CropFrame(
            mode = mode,
            rect = rect,
            ratio = orientedRatio(rect, c.frameWidth, c.frameHeight),
            corners = if (geometry.hasPerspectiveCrop) geometry.perspectiveCorners.toFloatArray() else rectCorners(rect),
        )
    }

private fun rectCorners(r: FloatArray) = floatArrayOf(r[0], r[1], r[2], r[1], r[2], r[3], r[0], r[3])

/** The locked ratio as width / height in the orientation of [rect]. */
private fun EditorState.orientedRatio(
    rect: FloatArray,
    frameWidth: Int,
    frameHeight: Int,
): Float? {
    val ratio = CropMath.ratioOf(geometry, frameWidth, frameHeight) ?: return null
    val portrait = (rect[2] - rect[0]) * frameWidth < (rect[3] - rect[1]) * frameHeight
    return if (portrait) 1f / ratio else ratio
}

/** Applies a synchronous [GeometryAction] (the Auto ones and non-Guided Upright run in the ViewModel). */
@Suppress("CyclomaticComplexMethod") // one branch per action
internal fun EditorState.reduceGeometry(action: GeometryAction): EditorState {
    val g = geometry
    return when (action) {
        is GeometryAction.Set -> {
            withGeometry(g.with(action.param, action.value))
        }

        is GeometryAction.Aspect -> {
            withAspect(action.aspect)
        }

        is GeometryAction.SwapOrientation -> {
            withSwappedOrientation()
        }

        is GeometryAction.Custom -> {
            withGeometry(
                g.copy(customWidth = action.width, customHeight = action.height),
            ).withAspect(CropAspect.Custom)
        }

        is GeometryAction.Overlay -> {
            copy(cropOverlay = action.overlay)
        }

        is GeometryAction.Constrain -> {
            withGeometry(g.copy(constrain = action.on))
        }

        is GeometryAction.Rotate -> {
            withGeometry(rotated(g, action.clockwise))
        }

        is GeometryAction.Flip -> {
            withGeometry(flipped(g, action.horizontal))
        }

        is GeometryAction.Crop -> {
            withGeometry(g.withCrop(action.rect))
        }

        is GeometryAction.Dragging -> {
            copy(cropDragging = action.dragging)
        }

        is GeometryAction.ResetCrop -> {
            withCropReset()
        }

        is GeometryAction.PerspectiveCrop -> {
            withPerspectiveCrop(action.on)
        }

        is GeometryAction.Corners -> {
            withGeometry(g.copy(perspectiveCorners = action.corners.toList()))
        }

        is GeometryAction.AddGuide -> {
            withGuide(action.line)
        }

        is GeometryAction.ClearGuides -> {
            withGeometry(g.copy(guides = emptyList(), uprightPitch = 0f, uprightYaw = 0f, uprightRoll = 0f))
        }

        is GeometryAction.Reset -> {
            withGeometry(GeometryAdjustments(constrain = g.constrain))
        }

        // Asynchronous (EditorGeometryActions): Upright, Auto, lens profiles.
        else -> {
            this
        }
    }
}

private fun GeometryAdjustments.withCrop(r: FloatArray) = copy(cropLeft = r[0], cropTop = r[1], cropRight = r[2], cropBottom = r[3])

/**
 * A picked ratio: a portrait label (4:5) is portrait; the others (3:2, 16:9, Original,
 * Custom) take the frame's orientation. Afterwards the crop's orientation is kept.
 */
private fun EditorState.withAspect(aspect: CropAspect): EditorState {
    val next = geometry.copy(aspect = aspect)
    val c = copy(geometry = next).composed ?: return withGeometry(next)
    val landscape = CropMath.ratioOf(next, c.frameWidth, c.frameHeight) ?: return withGeometry(next)
    val portraitFrame = c.frameWidth < c.frameHeight
    val labelPortrait = (aspect.ratio ?: 1f) < 1f || (aspect == CropAspect.Custom && next.customWidth < next.customHeight)
    val ratio = if (labelPortrait || portraitFrame) 1f / landscape else landscape
    return withGeometry(next.withCrop(CropMath.fitAspect(c.crop, ratio, c.frameWidth, c.frameHeight)))
}

private fun EditorState.withSwappedOrientation(): EditorState {
    val c = composed ?: return this
    val ratio = orientedRatio(c.crop, c.frameWidth, c.frameHeight) ?: return this
    // The same centre, the other orientation, as large as fits.
    val cx = (c.crop[0] + c.crop[2]) / 2
    val cy = (c.crop[1] + c.crop[3]) / 2
    val full = floatArrayOf(0f, 0f, 1f, 1f)
    val swapped = CropMath.fitAspect(full, 1f / ratio, c.frameWidth, c.frameHeight)
    val hw = (swapped[2] - swapped[0]) / 2
    val hh = (swapped[3] - swapped[1]) / 2
    val l = (cx - hw).coerceIn(0f, 1f - 2 * hw)
    val t = (cy - hh).coerceIn(0f, 1f - 2 * hh)
    return withGeometry(geometry.withCrop(floatArrayOf(l, t, l + 2 * hw, t + 2 * hh)))
}

private fun EditorState.withCropReset(): EditorState {
    val g = geometry.copy(cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f, perspectiveCorners = emptyList())
    return copy(geometry = g).withAspect(g.aspect)
}

private fun EditorState.withPerspectiveCrop(on: Boolean): EditorState {
    if (!on) return withGeometry(geometry.copy(perspectiveCorners = emptyList()))
    val c = composed ?: return this
    return withGeometry(geometry.copy(perspectiveCorners = rectCorners(c.crop).toList()))
}

/** Guided Upright: up to four lines (the oldest goes), solved at once. */
private fun EditorState.withGuide(line: GuideLine): EditorState {
    val guides = (geometry.guides + line).takeLast(MAX_GUIDES)
    val (w, h) = sourceSize ?: return withGeometry(geometry.copy(guides = guides))
    val r =
        dev.mark.lineover.core.model.Upright
            .solveGuided(guides, w, h)
    return withGeometry(geometry.copy(guides = guides, uprightPitch = r.pitch, uprightYaw = r.yaw, uprightRoll = r.roll))
}

private const val MAX_GUIDES = 4

/** A quarter turn: the crop and the flips turn with the photo. */
private fun rotated(
    g: GeometryAdjustments,
    clockwise: Boolean,
): GeometryAdjustments {
    val turns = ((g.quarterTurns + if (clockwise) 1 else -1) % 4 + 4) % 4
    val r = floatArrayOf(g.cropLeft, g.cropTop, g.cropRight, g.cropBottom)
    val crop = if (clockwise) floatArrayOf(1 - r[3], r[0], 1 - r[1], r[2]) else floatArrayOf(r[1], 1 - r[2], r[3], 1 - r[0])
    return g.copy(quarterTurns = turns, perspectiveCorners = emptyList()).withCrop(crop)
}

private fun flipped(
    g: GeometryAdjustments,
    horizontal: Boolean,
): GeometryAdjustments =
    if (horizontal) {
        g.copy(flipHorizontal = !g.flipHorizontal, cropLeft = 1 - g.cropRight, cropRight = 1 - g.cropLeft, perspectiveCorners = emptyList())
    } else {
        g.copy(flipVertical = !g.flipVertical, cropTop = 1 - g.cropBottom, cropBottom = 1 - g.cropTop, perspectiveCorners = emptyList())
    }
