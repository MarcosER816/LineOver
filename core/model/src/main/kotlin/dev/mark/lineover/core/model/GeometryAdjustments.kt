package dev.mark.lineover.core.model

import kotlinx.serialization.Serializable

/**
 * Crop aspect ratios (Crop panel order). [ratio] is width / height as the label reads (4:5
 * is portrait); null = free, the photo's own, or custom.
 */
@Serializable
enum class CropAspect(
    val label: String,
    val ratio: Float?,
) {
    Free("Free", null),
    Original("Original", null),
    Square("1:1", 1f),
    FourFive("4:5", 4f / 5f),
    ThreeTwo("3:2", 3f / 2f),
    SixteenNine("16:9", 16f / 9f),
    Custom("Custom", null),
}

/** Crop overlays: drawn only while a crop handle is dragged. */
enum class CropOverlay(
    val label: String,
) {
    Thirds("Rule of thirds"),
    Golden("Golden ratio"),
    Grid("Grid"),
}

/** Upright modes (Optics → Upright). */
@Serializable
enum class UprightMode(
    val label: String,
) {
    Off("Off"),
    Auto("Auto"),
    Level("Level"),
    Vertical("Vertical"),
    Full("Full"),
    Guided("Guided"),
}

/** A line the user drew for Guided Upright, in normalised source coordinates (0..1). */
@Serializable
data class GuideLine(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
)

/** A detected line segment (normalised source coordinates) and its width in analysis pixels. */
data class LineSegment(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val width: Float = 1f,
)

/** One slider of the Crop / Optics panels. */
enum class GeometryParam(
    val toolId: String,
    val label: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val default: Float = 0f,
) {
    Straighten("crop.straighten", "Straighten", -45f, 45f, 0.1f),
    Vertical("optics.transform.vertical", "Vertical", -100f, 100f, 1f),
    Horizontal("optics.transform.horizontal", "Horizontal", -100f, 100f, 1f),
    Rotate("optics.transform.rotate", "Rotate", -10f, 10f, 0.1f),
    Aspect("optics.transform.aspect", "Aspect", -100f, 100f, 1f),
    Scale("optics.transform.scale", "Scale", 50f, 150f, 1f, 100f),
    OffsetX("optics.transform.offset_x", "X offset", -100f, 100f, 1f),
    OffsetY("optics.transform.offset_y", "Y offset", -100f, 100f, 1f),
    Distortion("optics.distortion", "Distortion", -100f, 100f, 1f),
    DistortionEdge("optics.distortion.edge", "Edge distortion", -100f, 100f, 1f),
    ProfileDistortion("optics.profile.distortion", "Profile distortion", 0f, 200f, 1f, 100f),
    ProfileVignetting("optics.profile.vignetting", "Profile vignetting", 0f, 200f, 1f, 100f),
    ChromaticRed("optics.ca.red", "Red / cyan fringe", -100f, 100f, 1f),
    ChromaticBlue("optics.ca.blue", "Blue / yellow fringe", -100f, 100f, 1f),
    Vignetting("optics.vignetting", "Vignetting", -100f, 100f, 1f),
    VignettingMidpoint("optics.vignetting.midpoint", "Midpoint", 0f, 100f, 1f, 50f),
    ;

    fun coerce(value: Float): Float = value.coerceIn(min, max)
}

/**
 * Everything the Crop and Optics panels set. Pure user intent: [GeometryMath] composes it
 * into the one matrix of the geometry op (the pixels are resampled once). The crop rect is
 * normalised in the *frame* (the transformed, rotated image before cropping); with
 * [constrain] it is shrunk at composition time so no empty wedges show.
 */
@Serializable
data class GeometryAdjustments(
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val aspect: CropAspect = CropAspect.Free,
    val customWidth: Float = 1f,
    val customHeight: Float = 1f,
    val straighten: Float = 0f,
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val upright: UprightMode = UprightMode.Off,
    val uprightPitch: Float = 0f,
    val uprightYaw: Float = 0f,
    val uprightRoll: Float = 0f,
    val vertical: Float = 0f,
    val horizontal: Float = 0f,
    val rotate: Float = 0f,
    val aspectAmount: Float = 0f,
    val scale: Float = 100f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val constrain: Boolean = true,
    val distortion: Float = 0f,
    val distortionEdge: Float = 0f,
    val chromaticRed: Float = 0f,
    val chromaticBlue: Float = 0f,
    val vignetting: Float = 0f,
    val vignettingMidpoint: Float = 50f,
    /** A lens profile (Lensfun) applied with these amounts (percent; chromatic on / off). */
    val profile: LensProfile? = null,
    val profileDistortion: Float = 100f,
    val profileVignetting: Float = 100f,
    val profileChromatic: Boolean = true,
    /** Perspective crop corners (TL, TR, BR, BL as x, y pairs, normalised frame); empty = off. */
    val perspectiveCorners: List<Float> = emptyList(),
    val guides: List<GuideLine> = emptyList(),
) {
    @Suppress("CyclomaticComplexMethod") // one branch per slider
    operator fun get(param: GeometryParam): Float =
        when (param) {
            GeometryParam.Straighten -> straighten
            GeometryParam.Vertical -> vertical
            GeometryParam.Horizontal -> horizontal
            GeometryParam.Rotate -> rotate
            GeometryParam.Aspect -> aspectAmount
            GeometryParam.Scale -> scale
            GeometryParam.OffsetX -> offsetX
            GeometryParam.OffsetY -> offsetY
            GeometryParam.Distortion -> distortion
            GeometryParam.DistortionEdge -> distortionEdge
            GeometryParam.ProfileDistortion -> profileDistortion
            GeometryParam.ProfileVignetting -> profileVignetting
            GeometryParam.ChromaticRed -> chromaticRed
            GeometryParam.ChromaticBlue -> chromaticBlue
            GeometryParam.Vignetting -> vignetting
            GeometryParam.VignettingMidpoint -> vignettingMidpoint
        }

    @Suppress("CyclomaticComplexMethod") // one branch per slider
    fun with(
        param: GeometryParam,
        value: Float,
    ): GeometryAdjustments {
        val v = param.coerce(value)
        return when (param) {
            GeometryParam.Straighten -> copy(straighten = v)
            GeometryParam.Vertical -> copy(vertical = v)
            GeometryParam.Horizontal -> copy(horizontal = v)
            GeometryParam.Rotate -> copy(rotate = v)
            GeometryParam.Aspect -> copy(aspectAmount = v)
            GeometryParam.Scale -> copy(scale = v)
            GeometryParam.OffsetX -> copy(offsetX = v)
            GeometryParam.OffsetY -> copy(offsetY = v)
            GeometryParam.Distortion -> copy(distortion = v)
            GeometryParam.DistortionEdge -> copy(distortionEdge = v)
            GeometryParam.ProfileDistortion -> copy(profileDistortion = v)
            GeometryParam.ProfileVignetting -> copy(profileVignetting = v)
            GeometryParam.ChromaticRed -> copy(chromaticRed = v)
            GeometryParam.ChromaticBlue -> copy(chromaticBlue = v)
            GeometryParam.Vignetting -> copy(vignetting = v)
            GeometryParam.VignettingMidpoint -> copy(vignettingMidpoint = v)
        }
    }

    val hasCrop: Boolean get() = cropLeft != 0f || cropTop != 0f || cropRight != 1f || cropBottom != 1f
    val hasPerspectiveCrop: Boolean get() = perspectiveCorners.size == CORNER_VALUES

    /** Brown-Conrady k1 / k2: + Distortion straightens barrel (lines bowing outwards). */
    val k1: Double get() = -distortion / 100.0 * MAX_K
    val k2: Double get() = -distortionEdge / 100.0 * MAX_K

    /** Red / blue radial scale - 1: slider 100 = 1 % of the radius. */
    val caRed: Double get() = chromaticRed / 100.0 * MAX_CA
    val caBlue: Double get() = chromaticBlue / 100.0 * MAX_CA

    /** The profile's distortion p1..p4 at [profileDistortion] percent; empty without one. */
    val lensDistortion: List<Double>
        get() =
            profile
                ?.distortion
                ?.takeIf { it.isNotEmpty() && profileDistortion != 0f }
                ?.map { it * profileDistortion / 100.0 }
                .orEmpty()

    /** The profile's red / blue TCA (tv, tc, tb) when [profileChromatic]; empty otherwise. */
    val lensTcaRed: List<Double> get() = profile?.tcaRed?.takeIf { profileChromatic }.orEmpty()
    val lensTcaBlue: List<Double> get() = profile?.tcaBlue?.takeIf { profileChromatic }.orEmpty()

    /** s(r) of the whole lens model at r² = [r2] (green), as geometry.cpp radialScale. */
    @Suppress("MagicNumber") // p1..p4
    fun radialScale(r2: Double): Double {
        var s = 1 + k1 * r2 + k2 * r2 * r2
        val p = lensDistortion
        if (p.any { it != 0.0 }) {
            val r = kotlin.math.sqrt(r2)
            s += r * (p[0] + r * (p[1] + r * (p[2] + r * p[3])))
        }
        return s
    }

    /** The profile's vignetting ("pa" k1..k3) at [profileVignetting] percent; empty without one. */
    val vignetteProfile: List<Double>
        get() =
            profile
                ?.vignette
                ?.takeIf { it.isNotEmpty() && profileVignetting != 0f }
                ?.map { it * profileVignetting / 100.0 }
                .orEmpty()

    val isIdentity: Boolean get() = this == GeometryAdjustments().copy(constrain = constrain, aspect = aspect)

    /** The geometry op for a source of [sourceWidth] x [sourceHeight], or null when identity. */
    fun toOp(
        sourceWidth: Int,
        sourceHeight: Int,
    ): OpParams.Geometry? {
        if (isIdentity || sourceWidth <= 0 || sourceHeight <= 0) return null
        val composed = GeometryMath.compose(this, sourceWidth, sourceHeight)
        return OpParams.Geometry(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            outputWidth = composed.outputWidth,
            outputHeight = composed.outputHeight,
            k1 = k1,
            k2 = k2,
            caRed = caRed,
            caBlue = caBlue,
            vignette = vignetting,
            vignetteMid = vignettingMidpoint,
            settings = this,
            lensDistortion = lensDistortion,
            lensTcaRed = lensTcaRed,
            lensTcaBlue = lensTcaBlue,
            vignetteProfile = vignetteProfile,
            matrix = composed.matrix.toList(),
        )
    }

    companion object {
        const val CORNER_VALUES = 8
        const val MAX_K = 0.3
        const val MAX_CA = 0.01

        /** The Crop / Optics values of a layer's ops (the geometry op's settings). */
        fun fromOps(ops: List<OpParams>): GeometryAdjustments =
            ops.filterIsInstance<OpParams.Geometry>().firstOrNull()?.settings ?: GeometryAdjustments()
    }
}
