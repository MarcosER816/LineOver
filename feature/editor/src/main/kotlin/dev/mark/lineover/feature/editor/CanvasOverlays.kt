package dev.mark.lineover.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoSliderDefaults
import dev.mark.lineover.core.engine.CompareMode
import dev.mark.lineover.core.engine.EngineStats
import dev.mark.lineover.core.engine.Histogram
import dev.mark.lineover.core.model.LightParam
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

/** The histogram overlay (More → Histogram): R, G, B and luminance of the edited preview. */
@Composable
internal fun HistogramOverlay(
    state: EditorState,
    modifier: Modifier = Modifier,
) {
    val canvas = state.canvas
    // Only this composable reads the 30 Hz flow: the rest of the editor never recomposes for it.
    val flow = remember(canvas) { canvas?.histogram ?: MutableStateFlow(null) }
    val histogram by flow.collectAsState()
    Box(
        modifier
            .size(HISTOGRAM_WIDTH, HISTOGRAM_HEIGHT)
            .clip(LoShapes.Small)
            .background(LoColors.Surface2.copy(alpha = OVERLAY_ALPHA))
            .testTag(EditorTags.slot(EditorState.HISTOGRAM_TOOL))
            .semantics { contentDescription = "Histogram" },
    ) {
        val h = histogram
        if (h == null) {
            Text("Histogram", style = LoType.Caption, color = LoColors.TextSecondary, modifier = Modifier.align(Alignment.Center))
        } else {
            Canvas(Modifier.fillMaxSize().padding(LoSpacing.Xxs)) { drawHistogram(h) }
        }
    }
}

private fun DrawScope.drawHistogram(h: Histogram) {
    // Scale by the tallest inner bin: spikes of clipped pixels in bins 0 and 255 would
    // flatten the rest (they still show, cut at the top).
    var top = 1
    for (c in 0 until Histogram.CHANNELS) {
        for (i in 1 until Histogram.BINS - 1) top = maxOf(top, h[c, i])
    }
    val channels =
        listOf(
            Histogram.RED to LoColors.HistogramRed,
            Histogram.GREEN to LoColors.HistogramGreen,
            Histogram.BLUE to LoColors.HistogramBlue,
        )
    for ((channel, color) in channels) {
        drawPath(channelPath(h, channel, top, filled = true), color.copy(alpha = CHANNEL_ALPHA))
    }
    drawPath(channelPath(h, Histogram.LUMA, top, filled = false), LoColors.HistogramLuma, style = Stroke(width = 1.dp.toPx()))
}

private fun DrawScope.channelPath(
    h: Histogram,
    channel: Int,
    top: Int,
    filled: Boolean,
): Path {
    val path = Path()
    val dx = size.width / (Histogram.BINS - 1)

    fun y(i: Int) = size.height * (1f - (h[channel, i].toFloat() / top).coerceAtMost(1f))
    if (filled) path.moveTo(0f, size.height) else path.moveTo(0f, y(0))
    for (i in 0 until Histogram.BINS) path.lineTo(i * dx, y(i))
    if (filled) {
        path.lineTo(size.width, size.height)
        path.close()
    }
    return path
}

/** A small badge while the clipping warnings are on (More → Clipping warnings). */
@Composable
internal fun ClippingBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(LoShapes.Small)
            .background(LoColors.Surface2.copy(alpha = OVERLAY_ALPHA))
            .padding(horizontal = LoSpacing.Xs, vertical = LoSpacing.Xxs)
            .testTag(EditorTags.slot(EditorState.CLIPPING_TOOL)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xxs),
    ) {
        Box(Modifier.size(DOT).clip(CircleShape).background(LoColors.ClipHi))
        Box(Modifier.size(DOT).clip(CircleShape).background(LoColors.ClipLo))
        Text("Clipping", style = LoType.Caption, color = LoColors.TextPrimary)
    }
}

@Composable
private fun CanvasLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = LoType.Body,
        color = LoColors.TextPrimary,
        modifier =
            modifier
                .padding(LoSpacing.S)
                .clip(LoShapes.Small)
                .background(LoColors.Surface2.copy(alpha = OVERLAY_ALPHA))
                .padding(horizontal = LoSpacing.Xs, vertical = LoSpacing.Xxs),
    )
}

/**
 * Before / after: the "Before" label while the photo is held, the draggable divider of
 * split mode, the labels of side by side. The engine draws the images.
 */
@Composable
internal fun CompareOverlay(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    when {
        state.showingOriginal -> {
            Box(Modifier.fillMaxSize()) {
                CanvasLabel("Before", Modifier.align(Alignment.TopCenter).testTag(EditorTags.BEFORE_LABEL))
            }
        }

        state.compare == CompareMode.Split -> {
            SplitDivider(state.split, onIntent)
        }

        state.compare == CompareMode.SideBySide -> {
            Row(Modifier.fillMaxSize().testTag(EditorTags.slot(EditorState.COMPARE_TOOL))) {
                Box(Modifier.weight(1f).fillMaxHeight()) { CanvasLabel("Before", Modifier.align(Alignment.TopCenter)) }
                Box(Modifier.width(1.dp).fillMaxHeight().background(LoColors.Outline))
                Box(Modifier.weight(1f).fillMaxHeight()) { CanvasLabel("After", Modifier.align(Alignment.TopCenter)) }
            }
        }
    }
}

@Composable
private fun SplitDivider(
    split: Float,
    onIntent: (EditorIntent) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag(EditorTags.slot(EditorState.COMPARE_TOOL))) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)
        val current by rememberUpdatedState(split)
        var dragFraction by remember { mutableStateOf(split) }
        val drag =
            rememberDraggableState { delta ->
                dragFraction = (dragFraction + delta / widthPx).coerceIn(0f, 1f)
                onIntent(EditorIntent.SetSplit(dragFraction))
            }
        LaunchedEffect(split) { dragFraction = split }
        val x = (current * widthPx).roundToInt()
        CanvasLabel("Before", Modifier.align(Alignment.TopStart))
        CanvasLabel("After", Modifier.align(Alignment.TopEnd))
        Box(
            Modifier
                .offset { IntOffset(x - with(this) { HANDLE_TOUCH.roundToPx() } / 2, 0) }
                .width(HANDLE_TOUCH)
                .fillMaxHeight()
                .draggable(drag, Orientation.Horizontal)
                .testTag(EditorTags.SPLIT_HANDLE)
                .semantics { contentDescription = "Split position" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(LoColors.TextPrimary))
            Box(Modifier.size(HANDLE_KNOB).clip(CircleShape).background(LoColors.TextPrimary))
        }
    }
}

/** Swipe mode: which parameter the finger changes and its value, while swiping. */
@Composable
internal fun SwipeHud(
    state: EditorState,
    modifier: Modifier = Modifier,
) {
    if (!state.swiping) return
    val param = state.lightParam
    val value = LoSliderDefaults.formatValue(state.light[param], param.step, signed = true)
    val params = LightParam.entries
    Column(
        modifier
            .clip(LoShapes.Medium)
            .background(LoColors.Surface2.copy(alpha = OVERLAY_ALPHA))
            .padding(horizontal = LoSpacing.M, vertical = LoSpacing.Xs)
            .testTag(EditorTags.SWIPE_HUD),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val previous = params[(param.ordinal - 1).mod(params.size)]
        val next = params[(param.ordinal + 1).mod(params.size)]
        Text(previous.label, style = LoType.Caption, color = LoColors.TextSecondary)
        Text(
            "${param.label} $value${if (param == LightParam.Exposure) " EV" else ""}",
            style = LoType.Title,
            color = LoColors.TextPrimary,
        )
        Text(next.label, style = LoType.Caption, color = LoColors.TextSecondary)
    }
}

/**
 * Debug builds only (More → Render stats): the preview update latency the brief targets
 * (≤ 16 ms, setGraph → the new preview presented), frame timing and GPU memory. The same
 * numbers are logged under the LineOverEngine tag every 30 updates.
 */
@Composable
internal fun RenderStatsOverlay(
    state: EditorState,
    modifier: Modifier = Modifier,
) {
    val canvas = state.canvas
    var stats by remember { mutableStateOf<EngineStats?>(null) }
    LaunchedEffect(canvas) {
        while (true) {
            stats = canvas?.stats()
            delay(STATS_POLL_MILLIS)
        }
    }
    val s = stats
    val text =
        if (s == null) {
            "No engine"
        } else {
            "update ${ms(s.lastUpdateUs)} ms · P95 ${ms(s.p95UpdateUs)} ms (target 16) · n ${s.updates}\n" +
                "frame P95 ${ms(s.p95FrameIntervalUs)} ms · draw P95 ${ms(s.p95DrawUs)} ms\n" +
                "GPU ${s.gpuBytes shr MB} MB · ${if (s.halfFloat) "RGBA16F" else "SRGB8 fallback"} · " +
                "${if (s.displayP3) "P3" else "sRGB"} · histogram ${if (s.histogramSupported) "GPU" else "off"}"
        }
    Text(
        text,
        style = LoType.Micro,
        color = if (s != null && s.p95UpdateUs > TARGET_US) LoColors.Warning else LoColors.TextPrimary,
        modifier =
            modifier
                .clip(LoShapes.Small)
                .background(Color.Black.copy(alpha = OVERLAY_ALPHA))
                .padding(LoSpacing.Xs)
                .testTag(EditorTags.RENDER_STATS),
    )
}

private fun ms(us: Long): String = "%.1f".format(java.util.Locale.ROOT, us / MICROS_PER_MILLI)

private val HISTOGRAM_WIDTH = 176.dp
private val HISTOGRAM_HEIGHT = 88.dp
private val DOT = 8.dp
private val HANDLE_TOUCH = 48.dp
private val HANDLE_KNOB = 20.dp
private const val OVERLAY_ALPHA = 0.9f
private const val CHANNEL_ALPHA = 0.55f
private const val STATS_POLL_MILLIS = 500L
private const val MB = 20
private const val TARGET_US = 16_000L
private const val MICROS_PER_MILLI = 1000.0
