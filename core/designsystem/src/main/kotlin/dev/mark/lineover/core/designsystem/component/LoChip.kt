package dev.mark.lineover.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType

/**
 * A selectable chip (aspect ratios, presets, filters). It is drawn 32 dp tall but its
 * touch target is at least 48 dp.
 */
@Composable
fun LoChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes leadingIcon: Int? = null,
) {
    val content =
        when {
            !enabled -> LoColors.TextDisabled
            selected -> LoColors.Accent
            else -> LoColors.TextPrimary
        }
    Row(
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .heightIn(min = 32.dp)
                .clip(LoShapes.Small)
                .background(if (selected) LoColors.Surface3 else LoColors.Surface2)
                .border(1.dp, if (selected && enabled) LoColors.Accent else LoColors.Outline, LoShapes.Small)
                .selectable(selected = selected, enabled = enabled, role = Role.Checkbox, onClick = onClick)
                .padding(horizontal = LoSpacing.S, vertical = LoSpacing.Xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
    ) {
        if (leadingIcon != null) {
            Icon(painterResource(leadingIcon), contentDescription = null, modifier = Modifier.size(18.dp), tint = content)
        }
        Text(label, style = LoType.Caption, color = content)
    }
}

@Preview(name = "LoChip")
@Composable
private fun LoChipPreview() {
    LineOverTheme {
        Row(Modifier.background(LoColors.Surface1), horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
            LoChip("Original", selected = false, onClick = {})
            LoChip("4:5", selected = true, onClick = {}, leadingIcon = LoIcons.Crop)
            LoChip("16:9", selected = false, onClick = {}, enabled = false)
        }
    }
}
