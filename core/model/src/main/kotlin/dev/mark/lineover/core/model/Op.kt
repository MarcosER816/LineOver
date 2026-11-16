package dev.mark.lineover.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One non-destructive edit step. Ops are immutable values; a layer's look is the
 * ordered list of its ops applied to the layer source by the engine.
 *
 * Every op has a stable [SerialName]. Never rename or reuse one: project files
 * (.loproj) written by older versions must keep loading.
 */
@Serializable
sealed interface Op {
    /** Leaves the image unchanged. Used to validate the pipeline end to end. */
    @Serializable
    @SerialName("identity")
    data object Identity : Op
}
