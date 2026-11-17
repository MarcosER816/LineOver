package dev.mark.lineover.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoDimens
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.ui.tools.ToolSpec
import dev.mark.lineover.core.ui.tools.label

/**
 * Full-screen tool search over the whole ToolRegistry, with synonyms ("erase object" →
 * AI remove). Picking a result opens that tool's slot.
 */
@Composable
internal fun ToolSearchScreen(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    Column(modifier.fillMaxSize().background(LoColors.Bg).testTag(EditorTags.slot("workflow.tool_search"))) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = LoDimens.TopBarHeight).background(LoColors.Surface1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoIconButton(LoIcons.ArrowBack, "Close tool search", onClick = { onIntent(EditorIntent.CloseSearch) })
            OutlinedTextField(
                value = state.query,
                onValueChange = { onIntent(EditorIntent.Query(it)) },
                placeholder = { Text("Search tools", style = LoType.Body) },
                singleLine = true,
                textStyle = LoType.Body,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedTextColor = LoColors.TextPrimary,
                        unfocusedTextColor = LoColors.TextPrimary,
                        focusedBorderColor = LoColors.Accent,
                        unfocusedBorderColor = LoColors.Outline,
                        cursorColor = LoColors.Accent,
                        focusedPlaceholderColor = LoColors.TextSecondary,
                        unfocusedPlaceholderColor = LoColors.TextSecondary,
                    ),
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(end = LoSpacing.Xs)
                        .focusRequester(focus)
                        .testTag(EditorTags.SEARCH_FIELD),
            )
        }
        val results = state.searchResults
        when {
            state.query.isBlank() -> {
                Text(
                    "Search by name or by what you want to do: \"heal\", \"erase object\", \"curves\", \"b&w\".",
                    style = LoType.Body,
                    color = LoColors.TextSecondary,
                    modifier = Modifier.padding(LoSpacing.M),
                )
            }

            results.isEmpty() -> {
                Text(
                    "No tool matches \"${state.query}\"",
                    style = LoType.Body,
                    color = LoColors.TextSecondary,
                    modifier = Modifier.padding(LoSpacing.M),
                )
            }

            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.id }) { tool -> SearchResult(tool) { onIntent(EditorIntent.SelectTool(tool.id)) } }
                }
            }
        }
    }
}

@Composable
private fun SearchResult(
    tool: ToolSpec,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = LoDimens.TouchTargetMin + LoSpacing.Xs)
            .clickable(onClick = onClick)
            .padding(horizontal = LoSpacing.M)
            .testTag("result:${tool.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.M),
    ) {
        Icon(painterResource(tool.icon), contentDescription = null, tint = LoColors.TextSecondary)
        Column(Modifier.weight(1f)) {
            Text(tool.name, style = LoType.Body, color = LoColors.TextPrimary)
            val where = tool.category?.label ?: tool.place.catalogLabel?.replaceFirstChar { it.uppercase() } ?: ""
            Text(
                if (tool.done) where else "$where · Stage ${tool.stage}",
                style = LoType.Caption,
                color = LoColors.TextSecondary,
            )
        }
    }
}
