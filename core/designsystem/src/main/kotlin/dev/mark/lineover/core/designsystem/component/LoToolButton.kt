package dev.mark.lineover.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoDimens
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType

object LoToolButtonDefaults {
    val MinWidth = 64.dp
    val MaxWidth = 96.dp
    internal val IndicatorWidth = 20.dp
}

/**
 * A tool in the tool strip or the category bar: 24 dp icon above an 11 sp label.
 * Selected = accent tint plus a 2 dp accent indicator under the label.
 */
@Composable
fun LoToolButton(
    @DrawableRes icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = LoDimens.ToolStripHeight,
) {
    val tint =
        when {
            !enabled -> LoColors.TextDisabled
            selected -> LoColors.Accent
            else -> LoColors.TextSecondary
        }
    // The width limit grows with the system font size, so large text is not cut off.
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Column(
        modifier =
            modifier
                .widthIn(min = LoToolButtonDefaults.MinWidth, max = LoToolButtonDefaults.MaxWidth * fontScale)
                .heightIn(min = minHeight)
                .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
                .padding(horizontal = LoSpacing.Xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(LoDimens.IconSize), tint = tint)
        Spacer(Modifier.height(LoSpacing.Xxs))
        Text(
            text = label,
            style = LoType.Micro,
            color =
                if (selected && enabled) {
                    LoColors.Accent
                } else if (enabled) {
                    LoColors.TextPrimary
                } else {
                    LoColors.TextDisabled
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(LoSpacing.Xxs))
        Box(
            Modifier
                .width(LoToolButtonDefaults.IndicatorWidth)
                .height(LoDimens.SelectionIndicatorHeight)
                .background(if (selected && enabled) LoColors.Accent else Color.Transparent, LoShapes.ExtraSmall),
        )
    }
}

@Preview(name = "LoToolButton")
@Composable
private fun LoToolButtonPreview() {
    LineOverTheme {
        Row(Modifier.background(LoColors.Surface1)) {
            LoToolButton(LoIcons.LightMode, "Exposure", selected = false, onClick = {})
            LoToolButton(LoIcons.Tune, "Contrast", selected = true, onClick = {})
            LoToolButton(LoIcons.Gradient, "Curves", selected = false, onClick = {}, enabled = false)
        }
    }
}
