package dev.mark.lineover.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The parameters of one non-destructive edit step. Values are immutable; a layer's look
 * is the ordered list of its ops applied to the layer source by the engine (the native
 * engine reads the same JSON, see core/engine-native `graph.h`).
 *
 * Every op has a stable [SerialName]. Never rename or reuse one: project files
 * (.loproj) written by older versions must keep loading.
 */
@Serializable
sealed interface OpParams {
    /** True when the op leaves every pixel unchanged. */
    val isIdentity: Boolean

    /** Leaves the image unchanged. Used to validate the pipeline end to end. */
    @Serializable
    @SerialName("identity")
    data object Identity : OpParams {
        override val isIdentity: Boolean get() = true
    }

    /**
     * Multiplies RGB by 2^[stops] in the linear working space. A test op proving that
     * params flow from the UI through the graph to both engine paths; the Exposure tool
     * itself arrives in Stage 5.
     */
    @Serializable
    @SerialName("exposure_test")
    data class ExposureTest(
        val stops: Float,
    ) : OpParams {
        override val isIdentity: Boolean get() = stops == 0f
    }
}
