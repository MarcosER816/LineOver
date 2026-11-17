package dev.mark.lineover.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType

/** A settings row with a switch; the whole row toggles. */
@Composable
fun LoSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(horizontal = LoSpacing.M, vertical = LoSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = LoSpacing.M)) {
            Text(
                label,
                style = LoType.Title.copy(fontWeight = LoType.Body.fontWeight),
                color = if (enabled) LoColors.TextPrimary else LoColors.TextDisabled,
            )
            if (description != null) {
                Text(description, style = LoType.Caption, color = if (enabled) LoColors.TextSecondary else LoColors.TextDisabled)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = LoColors.OnAccent,
                    checkedTrackColor = LoColors.Accent,
                    checkedBorderColor = LoColors.Accent,
                    uncheckedThumbColor = LoColors.TextSecondary,
                    uncheckedTrackColor = LoColors.Surface3,
                    uncheckedBorderColor = LoColors.Outline,
                    disabledCheckedThumbColor = LoColors.Surface3,
                    disabledCheckedTrackColor = LoColors.TextDisabled,
                    disabledCheckedBorderColor = LoColors.TextDisabled,
                    disabledUncheckedThumbColor = LoColors.TextDisabled,
                    disabledUncheckedTrackColor = LoColors.Surface2,
                    disabledUncheckedBorderColor = LoColors.Outline,
                ),
        )
    }
}

@Preview(name = "LoSwitchRow", widthDp = 360)
@Composable
private fun LoSwitchRowPreview() {
    LineOverTheme {
        Column(Modifier.background(LoColors.Surface1)) {
            LoSwitchRow("Show clipping", checked = false, onCheckedChange = {})
            LoSwitchRow("Grid", checked = true, onCheckedChange = {}, description = "Rule of thirds over the canvas")
        }
    }
}
