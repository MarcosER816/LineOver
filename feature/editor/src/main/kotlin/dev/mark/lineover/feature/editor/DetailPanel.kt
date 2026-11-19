package dev.mark.lineover.feature.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoChip
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.designsystem.component.LoSlider
import dev.mark.lineover.core.designsystem.component.LoSliderFill
import dev.mark.lineover.core.model.DetailParam
import dev.mark.lineover.core.model.DetailTools
import dev.mark.lineover.core.ui.tools.ToolRegistry

/** The tools of Stage 10 (Detail and Effects). */
internal val DETAIL_TOOLS = DetailTools.ALL

/**
 * The panel of one Detail / Effects tool: its sliders (a drag is one History step). Sharpen's
 * Masking slider shows the mask in grey while a second finger is held on it; the detail tools
 * (Sharpen, Noise reduction, Texture) offer a 1:1 toggle to judge them at 100 %.
 */
@Composable
internal fun DetailPanel(
    toolId: String,
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    onClose: () -> Unit,
) {
    val title = requireNotNull(ToolRegistry[toolId]).name
    // The mask preview never outlives the panel.
    DisposableEffect(toolId) { onDispose { onIntent(EditorIntent.SharpenMaskPreview(false)) } }
    Column(Modifier.fillMaxWidth().testTag(EditorTags.DETAIL_PANEL)) {
        Row(Modifier.fillMaxWidth().padding(start = LoSpacing.M), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = LoType.Title, color = LoColors.TextPrimary, modifier = Modifier.weight(1f))
            if (toolId in DetailTools.DETAIL) {
                LoChip(
                    "1:1",
                    selected = false,
                    onClick = { onIntent(EditorIntent.ToggleOneToOne) },
                    modifier = Modifier.testTag(EditorTags.ONE_TO_ONE),
                    leadingIcon = LoIcons.CenterFocusStrong,
                )
            }
            LoIconButton(LoIcons.Close, "Close $title", onClick = onClose)
        }
        Column(Modifier.testTag(EditorTags.slot(toolId))) {
            for (param in DetailParam.forTool(toolId)) {
                DetailSlider(param, state.detail[param], onIntent)
            }
        }
    }
}

@Composable
private fun DetailSlider(
    param: DetailParam,
    value: Float,
    onIntent: (EditorIntent) -> Unit,
) {
    val masking = param == DetailParam.SharpenMasking
    LoSlider(
        value,
        { onIntent(EditorIntent.SetDetail(param, it)) },
        param.label,
        valueRange = param.min..param.max,
        defaultValue = param.default,
        step = param.step,
        fill = if (param.signed) LoSliderFill.FromDefault else LoSliderFill.FromStart,
        onValueChangeFinished = { onIntent(EditorIntent.EndEdit) },
        onSecondaryPointerChange = { down -> if (masking) onIntent(EditorIntent.SharpenMaskPreview(down)) },
    )
}
