package dev.mark.lineover.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Type-safe routes. They are saved with the back stack, so they must stay serializable. */
@Serializable
data object HomeRoute : NavKey

@Serializable
data class EditorRoute(
    val projectId: Long,
) : NavKey

/** The export sheet, shown over the editor. */
@Serializable
data class ExportRoute(
    val projectId: Long,
) : NavKey

@Serializable
data object SettingsRoute : NavKey

/** One settings section on its own screen (compact and medium windows). */
@Serializable
data class SettingsSectionRoute(
    val sectionId: String,
) : NavKey

/** Debug builds only. */
@Serializable
data object GalleryRoute : NavKey
