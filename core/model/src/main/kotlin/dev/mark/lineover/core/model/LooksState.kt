package dev.mark.lineover.core.model

import kotlinx.serialization.Serializable

/** An imported 3D LUT: [id] is the SHA-256 of its .cube file. */
@Serializable
data class LutEntry(
    val id: String,
    val name: String,
    val size: Int,
)

/** Copied settings (Copy / paste settings): the ops of the checked groups. */
@Serializable
data class CopiedSettings(
    val groups: List<SettingsGroup>,
    val graph: EditGraph,
) {
    val ops: List<OpParams>
        get() =
            graph.layers
                .firstOrNull()
                ?.ops
                .orEmpty()
}

/** What the looks library keeps besides the files (Stage 11). */
@Serializable
data class LooksState(
    val luts: List<LutEntry> = emptyList(),
    val favoritePresets: List<String> = emptyList(),
    val copied: CopiedSettings? = null,
)
