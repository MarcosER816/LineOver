package dev.mark.lineover.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoDimens
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.LocalReducedMotion
import dev.mark.lineover.core.designsystem.MotionTokens
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Resting heights of a [LoPanel]: 160 dp and 280 dp (never more than 40 % of the window). */
enum class LoPanelDetent { Collapsed, Expanded }

/** Hoistable state of a [LoPanel]. */
@Stable
class LoPanelState(
    initial: LoPanelDetent,
) {
    var detent by mutableStateOf(initial)
}

@Composable
fun rememberLoPanelState(initial: LoPanelDetent = LoPanelDetent.Collapsed): LoPanelState {
    var saved by rememberSaveable { mutableStateOf(initial) }
    val state = remember { LoPanelState(saved) }
    LaunchedEffect(state.detent) { saved = state.detent }
    return state
}

/** Panel geometry, free of Compose so it can be unit-tested. */
object LoPanelMath {
    /** Fling speed (px/s) above which the fling direction wins over the nearest detent. */
    const val FLING_THRESHOLD_PX_PER_S = 1000f

    /** Collapsed and expanded heights in px for a window [windowHeightPx] tall. */
    fun heights(
        windowHeightPx: Float,
        collapsedPx: Float,
        expandedPx: Float,
    ): Pair<Float, Float> {
        val cap = windowHeightPx * LoDimens.PANEL_MAX_HEIGHT_FRACTION
        val expanded = minOf(expandedPx, cap)
        return minOf(collapsedPx, expanded) to expanded
    }

    /** Detent to settle at after a drag that ended at [heightPx] with [velocityPx] (positive = downwards). */
    fun settle(
        heightPx: Float,
        velocityPx: Float,
        collapsedPx: Float,
        expandedPx: Float,
    ): LoPanelDetent =
        when {
            velocityPx > FLING_THRESHOLD_PX_PER_S -> LoPanelDetent.Collapsed
            velocityPx < -FLING_THRESHOLD_PX_PER_S -> LoPanelDetent.Expanded
            abs(heightPx - collapsedPx) <= abs(heightPx - expandedPx) -> LoPanelDetent.Collapsed
            else -> LoPanelDetent.Expanded
        }
}

/**
 * The tool panel: a bottom sheet with two detents (160 / 280 dp, capped at 40 % of the
 * window height, and at [maxHeight] when given — the editor passes 40 % of the canvas).
 * Drag or tap the handle to switch; it settles with the panel spring (a snap under
 * reduced motion). While the finger is down the height follows it 1:1.
 */
@Composable
fun LoPanel(
    state: LoPanelState,
    modifier: Modifier = Modifier,
    title: String? = null,
    maxHeight: Dp = Dp.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val windowHeightPx =
        LocalWindowInfo.current.containerSize.height
            .toFloat()
    val (collapsedPx, expandedPx) =
        with(density) {
            LoPanelMath.heights(
                windowHeightPx = if (windowHeightPx > 0f) windowHeightPx else Float.MAX_VALUE,
                collapsedPx = LoDimens.PanelMinHeight.toPx(),
                expandedPx =
                    if (maxHeight.isSpecified) {
                        minOf(LoDimens.PanelMaxHeight, maxHeight).toPx()
                    } else {
                        LoDimens.PanelMaxHeight.toPx()
                    },
            )
        }

    fun target(detent: LoPanelDetent) = if (detent == LoPanelDetent.Collapsed) collapsedPx else expandedPx
    val reducedMotion = LocalReducedMotion.current
    val height = remember { Animatable(target(state.detent)) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.detent, collapsedPx, expandedPx) {
        height.animateTo(target(state.detent), MotionTokens.panel(reducedMotion))
    }
    val dragState =
        rememberDraggableState { delta ->
            scope.launch { height.snapTo((height.value - delta).coerceIn(collapsedPx, expandedPx)) }
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .height(with(density) { height.value.toDp() })
                .clip(LoShapes.PanelTop)
                .background(LoColors.Surface1),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(LoDimens.TouchTargetMin - LoSpacing.Xs)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            val settled = LoPanelMath.settle(height.value, velocity, collapsedPx, expandedPx)
                            state.detent = settled
                            height.animateTo(target(settled), MotionTokens.panel(reducedMotion))
                        },
                    ).clickable(onClickLabel = null) {
                        state.detent =
                            if (state.detent == LoPanelDetent.Collapsed) LoPanelDetent.Expanded else LoPanelDetent.Collapsed
                    }.semantics {
                        contentDescription = "Panel size"
                        stateDescription = if (state.detent == LoPanelDetent.Collapsed) "Collapsed" else "Expanded"
                        onClick(label = if (state.detent == LoPanelDetent.Collapsed) "Expand" else "Collapse") {
                            state.detent =
                                if (state.detent == LoPanelDetent.Collapsed) LoPanelDetent.Expanded else LoPanelDetent.Collapsed
                            true
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 32.dp, height = 4.dp).background(LoColors.Outline, LoShapes.ExtraSmall))
        }
        if (title != null) {
            Text(
                text = title,
                style = LoType.Title,
                color = LoColors.TextPrimary,
                modifier = Modifier.padding(start = LoSpacing.M, end = LoSpacing.M, bottom = LoSpacing.Xs),
            )
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), content = content)
    }
}

@Preview(name = "LoPanel", widthDp = 360, heightDp = 320)
@Composable
private fun LoPanelPreview() {
    LineOverTheme {
        Box(Modifier.fillMaxSize().background(LoColors.CanvasSurroundDark), contentAlignment = Alignment.BottomCenter) {
            LoPanel(rememberLoPanelState(), title = "Light") {
                LoSlider(value = 0.35f, onValueChange = {}, label = "Exposure", valueRange = -5f..5f, step = 0.01f)
                LoSlider(value = -20f, onValueChange = {}, label = "Highlights")
            }
        }
    }
}
