package dev.mark.lineover.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoDimens
import dev.mark.lineover.core.designsystem.LoIcons

/**
 * A 48 dp icon button. [contentDescription] is required: icon-only controls must be
 * announced by TalkBack. A [selected] button (a toggle such as before/after) shows the
 * accent tint on a raised surface.
 */
@Composable
fun LoIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    Box(
        modifier =
            modifier
                .size(LoDimens.TouchTargetMin)
                .clip(CircleShape)
                .background(if (selected) LoColors.Surface3 else Color.Transparent)
                .selectable(selected = selected, enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(LoDimens.IconSize),
            tint =
                when {
                    !enabled -> LoColors.TextDisabled
                    selected -> LoColors.Accent
                    else -> LoColors.TextPrimary
                },
        )
    }
}

@Preview(name = "LoIconButton")
@Composable
private fun LoIconButtonPreview() {
    LineOverTheme {
        Row(Modifier.background(LoColors.Surface1)) {
            LoIconButton(LoIcons.Undo, "Undo", onClick = {})
            LoIconButton(LoIcons.Visibility, "Before and after", onClick = {}, selected = true)
            LoIconButton(LoIcons.Redo, "Redo", onClick = {}, enabled = false)
        }
    }
}
