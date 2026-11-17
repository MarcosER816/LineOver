package dev.mark.lineover.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoDimens
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType

/**
 * Fills a reserved tool slot until its stage ships: the tool's name and "Coming in
 * Stage N". It is visible on purpose, so every slot of the frozen layout can be checked.
 */
@Composable
fun LoPlaceholderTool(
    name: String,
    stage: Int,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val secondary = if (enabled) LoColors.TextSecondary else LoColors.TextDisabled
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = LoDimens.TouchTargetMin + LoSpacing.Xs)
                .clip(LoShapes.Medium)
                .background(LoColors.Surface2)
                .border(1.dp, LoColors.Outline, LoShapes.Medium)
                .clearAndSetSemantics { contentDescription = "$name, coming in Stage $stage" }
                .padding(horizontal = LoSpacing.M, vertical = LoSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.S),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(LoDimens.IconSize), tint = secondary)
        Column {
            Text(name, style = LoType.Body, color = if (enabled) LoColors.TextPrimary else LoColors.TextDisabled)
            Text("Coming in Stage $stage", style = LoType.Caption, color = secondary)
        }
    }
}

@Preview(name = "LoPlaceholderTool", widthDp = 360)
@Composable
private fun LoPlaceholderToolPreview() {
    LineOverTheme { LoPlaceholderTool("Liquify", stage = 22, icon = LoIcons.WaterDrop) }
}
