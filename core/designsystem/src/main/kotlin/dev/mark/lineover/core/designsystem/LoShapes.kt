package dev.mark.lineover.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Corner radius scale: 4 / 8 / 12 / 16 dp. */
object LoShapes {
    val ExtraSmall = RoundedCornerShape(4.dp)
    val Small = RoundedCornerShape(8.dp)
    val Medium = RoundedCornerShape(12.dp)
    val Large = RoundedCornerShape(16.dp)

    /** Bottom panels: only the top corners are rounded. */
    val PanelTop = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
}

internal val LoMaterialShapes =
    Shapes(
        extraSmall = LoShapes.ExtraSmall,
        small = LoShapes.Small,
        medium = LoShapes.Medium,
        large = LoShapes.Large,
        extraLarge = LoShapes.Large,
    )
