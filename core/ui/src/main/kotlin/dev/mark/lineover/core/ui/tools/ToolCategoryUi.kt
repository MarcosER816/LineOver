package dev.mark.lineover.core.ui.tools

import androidx.annotation.DrawableRes
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.component.LoCategory
import dev.mark.lineover.core.model.ToolCategory

/** The label shown in the category bar and used in tool slots (`cat/<label>/nn`). */
val ToolCategory.label: String
    get() = if (this == ToolCategory.Ai) "AI" else name

/** The category bar icon. */
@get:DrawableRes
val ToolCategory.icon: Int
    get() =
        when (this) {
            ToolCategory.Favorites -> LoIcons.Star
            ToolCategory.Presets -> LoIcons.Style
            ToolCategory.Auto -> LoIcons.AutoAwesome
            ToolCategory.Light -> LoIcons.LightMode
            ToolCategory.Color -> LoIcons.Palette
            ToolCategory.Effects -> LoIcons.BlurOn
            ToolCategory.Detail -> LoIcons.Grain
            ToolCategory.Optics -> LoIcons.Lens
            ToolCategory.Crop -> LoIcons.Crop
            ToolCategory.Masks -> LoIcons.Gradient
            ToolCategory.Select -> LoIcons.SelectAll
            ToolCategory.Retouch -> LoIcons.Healing
            ToolCategory.Paint -> LoIcons.Brush
            ToolCategory.Filters -> LoIcons.Filter
            ToolCategory.Liquify -> LoIcons.WaterDrop
            ToolCategory.Text -> LoIcons.TextFields
            ToolCategory.Shapes -> LoIcons.Shapes
            ToolCategory.Ai -> LoIcons.AutoFixHigh
        }

/** All categories as category-bar entries, in the fixed order. */
val categoryBarEntries: List<LoCategory> by lazy { ToolCategory.entries.map { LoCategory(it.id, it.label, it.icon) } }

/** The category with [id], or null. */
fun toolCategoryOf(id: String?): ToolCategory? = ToolCategory.entries.firstOrNull { it.id == id }
