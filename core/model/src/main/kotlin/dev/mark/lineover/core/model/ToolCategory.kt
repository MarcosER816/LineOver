package dev.mark.lineover.core.model

/**
 * Editor tool categories in their fixed display order (docs/TOOL_CATALOG.md).
 * The order of the entries is the order of the category bar and must not change.
 */
enum class ToolCategory(
    val id: String,
) {
    Favorites("favorites"),
    Presets("presets"),
    Auto("auto"),
    Light("light"),
    Color("color"),
    Effects("effects"),
    Detail("detail"),
    Optics("optics"),
    Crop("crop"),
    Masks("masks"),
    Select("select"),
    Retouch("retouch"),
    Paint("paint"),
    Filters("filters"),
    Liquify("liquify"),
    Text("text"),
    Shapes("shapes"),
    Ai("ai"),
}
