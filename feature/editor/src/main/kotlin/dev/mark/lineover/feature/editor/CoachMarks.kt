package dev.mark.lineover.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType

private class CoachMark(
    val icon: Int,
    val title: String,
    val body: String,
)

private fun coachMarks(wide: Boolean) =
    listOf(
        CoachMark(
            LoIcons.TouchApp,
            "Pinch to zoom",
            "Two fingers move and turn the photo; it snaps straight at 0, 90, 180 and 270°. Double-tap to fit or see 100 %.",
        ),
        CoachMark(
            LoIcons.Compare,
            "Hold to compare",
            "Hold the photo to see your original. Compare at the top switches split and side by side.",
        ),
        CoachMark(
            LoIcons.Tune,
            "Swipe the categories",
            if (wide) {
                "Every tool lives in a category on the left. Long-press a tool to add it to Favorites."
            } else {
                "Swipe the categories at the bottom to find every tool. Long-press a tool to add it to Favorites."
            },
        ),
    )

/** The first-open coach marks: three steps, dismissible forever (Skip or Got it). */
@Composable
internal fun CoachMarks(
    step: Int,
    wide: Boolean,
    onIntent: (EditorIntent) -> Unit,
) {
    val marks = coachMarks(wide)
    val mark = marks[step.coerceIn(0, marks.lastIndex)]
    val last = step >= marks.lastIndex
    Box(
        Modifier
            .fillMaxSize()
            .background(LoColors.Bg.copy(alpha = SCRIM_ALPHA))
            // The scrim swallows touches so nothing behind it is tapped by accident.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .testTag(EditorTags.COACH),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .padding(LoSpacing.L)
                .clip(LoShapes.Large)
                .background(LoColors.Surface2)
                .padding(LoSpacing.L)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(LoSpacing.S),
        ) {
            Icon(painterResource(mark.icon), contentDescription = null, tint = LoColors.Accent, modifier = Modifier.size(32.dp))
            Text(mark.title, style = LoType.Headline, color = LoColors.TextPrimary)
            Text(mark.body, style = LoType.Body, color = LoColors.TextSecondary)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${step + 1} of ${marks.size}", style = LoType.Caption, color = LoColors.TextSecondary)
                Spacer(Modifier.weight(1f))
                if (!last) {
                    TextButton(onClick = { onIntent(EditorIntent.DismissCoachMarks) }) {
                        Text("Skip", style = LoType.Body, color = LoColors.TextSecondary)
                    }
                }
                TextButton(onClick = { onIntent(EditorIntent.NextCoachMark) }) {
                    Text(if (last) "Got it" else "Next", style = LoType.Body, color = LoColors.Accent)
                }
            }
        }
    }
}

private const val SCRIM_ALPHA = 0.72f
