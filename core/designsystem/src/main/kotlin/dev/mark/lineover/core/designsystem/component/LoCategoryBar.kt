package dev.mark.lineover.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoDimens
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LocalReducedMotion

/** One entry of a [LoCategoryBar]. [id] is stable (e.g. "light"); [label] is shown. */
@Immutable
data class LoCategory(
    val id: String,
    val label: String,
    @param:DrawableRes val icon: Int,
)

/**
 * The horizontally scrolling category bar (64 dp). Flings snap to a category, and the
 * selected category is scrolled into view when it changes. The design system does not
 * know the categories; the editor passes them in their fixed order.
 */
@Composable
fun LoCategoryBar(
    categories: List<LoCategory>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val state = rememberLazyListState()
    val reducedMotion = LocalReducedMotion.current
    LaunchedEffect(selectedId) {
        val index = categories.indexOfFirst { it.id == selectedId }
        if (index < 0) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo
        val fullyVisible =
            visible.any {
                it.index == index && it.offset >= 0 && it.offset + it.size <= state.layoutInfo.viewportEndOffset
            }
        if (!fullyVisible) {
            if (reducedMotion) state.scrollToItem(index) else state.animateScrollToItem(index)
        }
    }
    LazyRow(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = LoDimens.CategoryBarHeight)
                .background(LoColors.Surface1)
                .selectableGroup(),
        contentPadding = PaddingValues(horizontal = LoSpacing.Xs),
    ) {
        items(categories, key = { it.id }) { category ->
            LoToolButton(
                icon = category.icon,
                label = category.label,
                selected = category.id == selectedId,
                onClick = { onSelect(category.id) },
                enabled = enabled,
                minHeight = LoDimens.CategoryBarHeight,
            )
        }
    }
}

@Preview(name = "LoCategoryBar", widthDp = 360)
@Composable
private fun LoCategoryBarPreview() {
    LineOverTheme {
        LoCategoryBar(
            categories =
                listOf(
                    LoCategory("favorites", "Favorites", LoIcons.Star),
                    LoCategory("presets", "Presets", LoIcons.Style),
                    LoCategory("auto", "Auto", LoIcons.AutoAwesome),
                    LoCategory("light", "Light", LoIcons.LightMode),
                    LoCategory("color", "Color", LoIcons.Palette),
                    LoCategory("effects", "Effects", LoIcons.BlurOn),
                ),
            selectedId = "light",
            onSelect = {},
        )
    }
}
