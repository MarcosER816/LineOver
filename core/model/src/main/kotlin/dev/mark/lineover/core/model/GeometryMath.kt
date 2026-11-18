package dev.mark.lineover.core.model

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** The composed geometry: the op's matrix (output normalised -> source pixels) and output size. */
class ComposedGeometry(
    val matrix: Mat3,
    val outputWidth: Int,
    val outputHeight: Int,
    /** The crop actually used (normalised frame), after [GeometryAdjustments.constrain]. */
    val crop: FloatArray,
    val frameWidth: Int,
    val frameHeight: Int,
)

/**
 * Composes the Crop and Optics values into one matrix (Stage 9, "resample once"):
 *
 * source px --(- centre)--> camera re-rotation K·R·K⁻¹ (Upright + Vertical / Horizontal)
 * --> aspect, scale, straighten + rotate, offset --> quarter turns, flips --> frame px
 * --> crop (or the perspective-crop homography) --> output.
 *
 * The op stores the inverse (output -> source); lens distortion is applied after it by
 * the engine, so this works in undistorted source coordinates.
 */
@Suppress("MagicNumber") // matrix elements and quad corner (x, y) positions
object GeometryMath {
    /** Focal length (pixels) of a 26 mm-equivalent lens on a 4:3 sensor, as a fraction of the long edge. */
    const val DEFAULT_FOCAL = 0.75

    /** Vertical / Horizontal slider 100 = this many degrees of camera pitch / yaw. */
    const val PERSPECTIVE_DEGREES = 20.0

    private const val MIN_SIZE = 1

    fun radians(degrees: Double): Double = degrees * PI / 180.0

    fun degrees(radians: Double): Double = radians * 180.0 / PI

    fun frameSize(
        g: GeometryAdjustments,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Pair<Int, Int> = if (g.quarterTurns % 2 != 0) sourceHeight to sourceWidth else sourceWidth to sourceHeight

    /** K·R·K⁻¹ about the centre for camera pitch / yaw / roll (degrees): R = Ry(yaw)·Rx(pitch)·Rz(roll). */
    fun cameraRotation(
        pitch: Double,
        yaw: Double,
        roll: Double,
        focal: Double,
    ): Mat3 {
        val r = rotationY(radians(yaw)) * rotationX(radians(pitch)) * rotationZ(radians(roll))
        return Mat3.scale(focal, focal) * r * Mat3.scale(1.0 / focal, 1.0 / focal)
    }

    internal fun rotationX(a: Double) = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(a), -sin(a), 0.0, sin(a), cos(a)))

    internal fun rotationY(a: Double) = Mat3(doubleArrayOf(cos(a), 0.0, sin(a), 0.0, 1.0, 0.0, -sin(a), 0.0, cos(a)))

    internal fun rotationZ(a: Double) = Mat3(doubleArrayOf(cos(a), -sin(a), 0.0, sin(a), cos(a), 0.0, 0.0, 0.0, 1.0))

    /** Source pixels (undistorted) -> frame pixels. */
    fun sourceToFrame(
        g: GeometryAdjustments,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Mat3 {
        val (frameW, frameH) = frameSize(g, sourceWidth, sourceHeight)
        val focal = DEFAULT_FOCAL * max(sourceWidth, sourceHeight)
        val camera =
            cameraRotation(
                pitch = g.uprightPitch + g.vertical / 100.0 * PERSPECTIVE_DEGREES,
                yaw = g.uprightYaw + g.horizontal / 100.0 * PERSPECTIVE_DEGREES,
                roll = g.uprightRoll.toDouble(),
                focal = focal,
            )
        val stretch = 2.0.pow(g.aspectAmount / 200.0)
        val s = g.scale / 100.0
        val plane =
            Mat3.translate(g.offsetX / 100.0 * 0.5 * sourceWidth, g.offsetY / 100.0 * 0.5 * sourceHeight) *
                Mat3.rotate(radians((g.straighten + g.rotate).toDouble())) *
                Mat3.scale(s * stretch, s / stretch)
        val turns = Mat3.rotate(PI / 2 * (((g.quarterTurns % 4) + 4) % 4))
        val flip = Mat3.scale(if (g.flipHorizontal) -1.0 else 1.0, if (g.flipVertical) -1.0 else 1.0)
        return Mat3.translate(frameW / 2.0, frameH / 2.0) * flip * turns * plane * camera *
            Mat3.translate(-sourceWidth / 2.0, -sourceHeight / 2.0)
    }

    /** The geometry op's matrix and output size. */
    fun compose(
        g: GeometryAdjustments,
        sourceWidth: Int,
        sourceHeight: Int,
    ): ComposedGeometry {
        val toFrame = sourceToFrame(g, sourceWidth, sourceHeight)
        val fromFrame = toFrame.inverse() ?: Mat3.IDENTITY
        val (frameW, frameH) = frameSize(g, sourceWidth, sourceHeight)
        if (g.hasPerspectiveCrop) {
            val c = g.perspectiveCorners
            val quad = DoubleArray(Mat3.SIZE - 1) { i -> c[i] * (if (i % 2 == 0) frameW else frameH).toDouble() }
            val unit = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
            val toQuad = Homography.fromQuads(unit, quad)
            if (toQuad != null) {
                val top = hypot(quad[2] - quad[0], quad[3] - quad[1])
                val bottom = hypot(quad[4] - quad[6], quad[5] - quad[7])
                val left = hypot(quad[6] - quad[0], quad[7] - quad[1])
                val right = hypot(quad[4] - quad[2], quad[5] - quad[3])
                val w = max(MIN_SIZE, ((top + bottom) / 2).roundToInt())
                val h = max(MIN_SIZE, ((left + right) / 2).roundToInt())
                return ComposedGeometry((fromFrame * toQuad).normalised(), w, h, floatArrayOf(0f, 0f, 1f, 1f), frameW, frameH)
            }
        }
        var crop = floatArrayOf(g.cropLeft, g.cropTop, g.cropRight, g.cropBottom)
        if (g.constrain) crop = CropMath.constrainCrop(g, sourceWidth, sourceHeight, crop)
        val w = max(MIN_SIZE, ((crop[2] - crop[0]) * frameW).roundToInt())
        val h = max(MIN_SIZE, ((crop[3] - crop[1]) * frameH).roundToInt())
        val outputToFrame =
            Mat3(
                doubleArrayOf(
                    (crop[2] - crop[0]).toDouble() * frameW,
                    0.0,
                    crop[0].toDouble() * frameW,
                    0.0,
                    (crop[3] - crop[1]).toDouble() * frameH,
                    crop[1].toDouble() * frameH,
                    0.0,
                    0.0,
                    1.0,
                ),
            )
        return ComposedGeometry((fromFrame * outputToFrame).normalised(), w, h, crop, frameW, frameH)
    }

    /** The angle of segment (x1, y1) -> (x2, y2) in degrees, folded into -90..90 (0 = horizontal). */
    fun lineAngle(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
    ): Double {
        var a = degrees(atan2(y2 - y1, x2 - x1))
        while (a > 90) a -= 180
        while (a <= -90) a += 180
        return a
    }
}
