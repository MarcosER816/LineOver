package dev.mark.lineover.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoDimens
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType

/** A segmented control (e.g. Curves: RGB / R / G / B). Each segment is at least 48 dp tall. */
@Composable
fun LoSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    descriptions: List<String>? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(LoShapes.Medium)
                .background(LoColors.Surface2)
                .padding(LoSpacing.Xxs)
                .selectableGroup(),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = LoDimens.TouchTargetMin - LoSpacing.Xs)
                        .clip(LoShapes.Small)
                        .background(if (selected) LoColors.Surface3 else Color.Transparent)
                        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { onSelect(index) }
                        .padding(horizontal = LoSpacing.Xs, vertical = LoSpacing.Xxs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    modifier = descriptions?.getOrNull(index)?.let { d -> Modifier.semantics { contentDescription = d } } ?: Modifier,
                    style = LoType.Body,
                    textAlign = TextAlign.Center,
                    color =
                        when {
                            !enabled -> LoColors.TextDisabled
                            selected -> LoColors.Accent
                            else -> LoColors.TextSecondary
                        },
                )
            }
        }
    }
}

@Preview(name = "LoSegmented", widthDp = 360)
@Composable
private fun LoSegmentedPreview() {
    LineOverTheme {
        LoSegmented(options = listOf("RGB", "R", "G", "B"), selectedIndex = 1, onSelect = {})
    }
}
