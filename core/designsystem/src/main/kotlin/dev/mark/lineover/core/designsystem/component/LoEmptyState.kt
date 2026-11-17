package dev.mark.lineover.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoDimens
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType

/** Shown where a list or screen has nothing yet (no projects, no presets…). */
@Composable
fun LoEmptyState(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(LoSpacing.L),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(48.dp), tint = LoColors.TextSecondary)
        Text(title, style = LoType.Title, color = LoColors.TextPrimary, textAlign = TextAlign.Center)
        if (body != null) {
            Text(body, style = LoType.Body, color = LoColors.TextSecondary, textAlign = TextAlign.Center)
        }
        if (actionLabel != null) {
            Box(
                modifier =
                    Modifier
                        .heightIn(min = LoDimens.TouchTargetMin)
                        .clip(LoShapes.Small)
                        .clickable(enabled = actionEnabled, role = Role.Button, onClick = onAction)
                        .padding(horizontal = LoSpacing.M),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    actionLabel,
                    style = LoType.Body.copy(fontWeight = LoType.Title.fontWeight),
                    color = if (actionEnabled) LoColors.Accent else LoColors.TextDisabled,
                )
            }
        }
    }
}

@Preview(name = "LoEmptyState", widthDp = 360)
@Composable
private fun LoEmptyStatePreview() {
    LineOverTheme {
        LoEmptyState(LoIcons.Image, "No projects yet", body = "Open a photo to start editing.", actionLabel = "Open photo")
    }
}
