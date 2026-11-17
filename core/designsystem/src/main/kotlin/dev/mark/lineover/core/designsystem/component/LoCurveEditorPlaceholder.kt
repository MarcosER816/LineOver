package dev.mark.lineover.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType

/**
 * Reserves the curve editor's place: a square with the 4x4 grid and the identity diagonal.
 * The real editor arrives in Stage 7; this one is not interactive.
 */
@Composable
fun LoCurveEditorPlaceholder(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    stage: Int = CURVES_STAGE,
) {
    val text = "Curves · Coming in Stage $stage"
    Box(
        modifier =
            modifier
                .widthIn(max = 280.dp)
                .aspectRatio(1f)
                .clip(LoShapes.Small)
                .background(LoColors.Surface2)
                .border(1.dp, LoColors.Outline, LoShapes.Small)
                .clearAndSetSemantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(LoSpacing.S)) {
            val stroke = 1.dp.toPx()
            for (i in 1 until GRID) {
                val x = size.width * i / GRID
                val y = size.height * i / GRID
                drawLine(LoColors.Outline, Offset(x, 0f), Offset(x, size.height), stroke)
                drawLine(LoColors.Outline, Offset(0f, y), Offset(size.width, y), stroke)
            }
            drawLine(
                if (enabled) LoColors.TextSecondary else LoColors.TextDisabled,
                Offset(0f, size.height),
                Offset(size.width, 0f),
                2.dp.toPx(),
            )
        }
        Text(
            text = text,
            style = LoType.Caption,
            color = if (enabled) LoColors.TextSecondary else LoColors.TextDisabled,
            textAlign = TextAlign.Center,
            modifier = Modifier.background(LoColors.Surface2, LoShapes.ExtraSmall).padding(LoSpacing.Xxs),
        )
    }
}

private const val GRID = 4
private const val CURVES_STAGE = 7

@Preview(name = "LoCurveEditorPlaceholder", widthDp = 240, heightDp = 240)
@Composable
private fun LoCurveEditorPlaceholderPreview() {
    LineOverTheme { LoCurveEditorPlaceholder() }
}
