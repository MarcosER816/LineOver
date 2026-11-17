package dev.mark.lineover.feature.settings

import androidx.annotation.DrawableRes
import dev.mark.lineover.core.designsystem.LoIcons

/**
 * The rows of Settings, in display order. [stage] is the stage that implements the row;
 * null means it works now. The rows are reserved slots (rule R9): later stages fill them.
 */
enum class SettingsSection(
    val id: String,
    val title: String,
    val summary: String,
    @param:DrawableRes val icon: Int,
    val stage: Int?,
) {
    SurroundColor("surround", "Canvas surround", "Dark or mid grey around the photo", LoIcons.Palette, null),
    PreviewQuality("preview", "Preview quality", "Resolution of the live preview", LoIcons.HighQuality, 4),
    ExportDefaults("export", "Export defaults", "Format, quality, size and metadata", LoIcons.IosShare, 12),
    ColorManagement("color", "Colour management", "Working space and display profile", LoIcons.Lens, 4),
    AiModels("ai_models", "AI models", "Downloaded models: size, licence, delete", LoIcons.AutoFixHigh, null),
    GesturesHelp("gestures", "Gestures", "How to pan, zoom and use the sliders", LoIcons.TouchApp, 28),
    Haptics("haptics", "Haptics", "Follows the system touch-feedback setting", LoIcons.Vibration, 30),
    ReducedMotion("reduced_motion", "Reduced motion", "Follows the system animation setting", LoIcons.Animation, 30),
    About("about", "About and licences", "Version and open-source licences", LoIcons.Info, null),
    Debug("debug", "Debug", "Component gallery (debug builds only)", LoIcons.BugReport, null),
    ;

    companion object {
        fun fromId(id: String): SettingsSection? = entries.firstOrNull { it.id == id }
    }
}
