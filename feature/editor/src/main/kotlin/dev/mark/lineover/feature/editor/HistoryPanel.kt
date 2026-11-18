package dev.mark.lineover.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoDimens
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoChip
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.designsystem.component.LoSegmented
import dev.mark.lineover.core.model.Snapshot
import dev.mark.lineover.core.model.Version
import java.text.DateFormat
import java.util.Date

/** History: the step list, snapshots and versions (Stage 8). */
@Composable
internal fun HistorySheet(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    onClose: () -> Unit,
) {
    PanelHeader("History", state.saveBlocked?.let { "Not saved: $it" }, onClose)
    Column(Modifier.padding(horizontal = LoSpacing.M), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        LoSegmented(
            options = HistoryTab.entries.map { it.label },
            selectedIndex = state.historyTab.ordinal,
            onSelect = { onIntent(EditorIntent.SelectHistoryTab(HistoryTab.entries[it])) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.preview != null) PreviewBar(state, onIntent)
        Column(Modifier.testTag(EditorTags.slot(state.historyTab.toolId)), verticalArrangement = Arrangement.spacedBy(LoSpacing.Xxs)) {
            when (state.historyTab) {
                HistoryTab.History -> StepList(state, onIntent)
                HistoryTab.Snapshots -> SnapshotList(state, onIntent)
                HistoryTab.Versions -> VersionGrid(state, onIntent)
            }
        }
    }
    state.nameDialog?.let { NameDialogView(it, onIntent) }
}

/** Shown while a row is previewed: the canvas shows that state until Restore or Cancel. */
@Composable
private fun PreviewBar(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    val what =
        when (val p = state.preview) {
            is HistoryPreview.Step -> {
                state.history.entries
                    .getOrNull(p.index)
                    ?.label
            }

            is HistoryPreview.SnapshotState -> {
                state.document.snapshots
                    .firstOrNull { it.id == p.id }
                    ?.name
            }

            null -> {
                null
            }
        }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(
                LoShapes.Medium,
            ).background(LoColors.Surface2)
            .padding(start = LoSpacing.S)
            .testTag(EditorTags.HISTORY_PREVIEW),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Previewing \"${what.orEmpty()}\"",
            style = LoType.Body,
            color = LoColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { onIntent(EditorIntent.Preview(null)) },
        ) { Text("Cancel", style = LoType.Body, color = LoColors.TextSecondary) }
        TextButton(onClick = { onIntent(EditorIntent.RestorePreview) }) { Text("Restore", style = LoType.Body, color = LoColors.Accent) }
    }
}

/** Every step, newest first; tapping one previews it. Steps after the current one are redoable. */
@Composable
private fun StepList(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    val history = state.history
    val shown = (history.entries.lastIndex downTo maxOf(0, history.entries.size - MAX_ROWS)).toList()
    val previewing = (state.preview as? HistoryPreview.Step)?.index
    for (i in shown) {
        val entry = history.entries[i]
        val current = i == history.index
        val redo = i > history.index
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = LoDimens.TouchTargetMin)
                .clip(LoShapes.Small)
                .background(if (previewing == i) LoColors.Surface3 else LoColors.Surface1)
                .clickable(role = Role.Button) { onIntent(EditorIntent.Preview(if (current) null else HistoryPreview.Step(i))) }
                .semantics { selected = current }
                .padding(horizontal = LoSpacing.S)
                .testTag(EditorTags.historyRow(i)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entry.label,
                style = LoType.Body,
                color =
                    when {
                        current -> LoColors.Accent
                        redo -> LoColors.TextDisabled
                        else -> LoColors.TextPrimary
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (current) Text("Now", style = LoType.Caption, color = LoColors.Accent)
        }
    }
    val older = history.entries.size - shown.size
    if (older > 0) Text("$older older steps (Undo reaches them)", style = LoType.Caption, color = LoColors.TextSecondary)
    Text(
        "${history.entries.size - 1} steps · the last ${dev.mark.lineover.core.model.EditHistory.LIMIT} are kept · two-finger tap undoes",
        style = LoType.Caption,
        color = LoColors.TextSecondary,
    )
}

@Composable
private fun SnapshotList(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    LoChip(
        "Save snapshot",
        selected = false,
        onClick = { onIntent(EditorIntent.OpenNameDialog(NameDialog.NewSnapshot(state.nextSnapshotName()))) },
        leadingIcon = LoIcons.Bookmark,
    )
    if (state.document.snapshots.isEmpty()) {
        Text("Save the current look under a name to come back to it later.", style = LoType.Body, color = LoColors.TextSecondary)
    }
    val previewing = (state.preview as? HistoryPreview.SnapshotState)?.id
    state.document.snapshots
        .asReversed()
        .forEach { snapshot -> SnapshotRow(snapshot, snapshot.id == previewing, onIntent) }
}

@Composable
private fun SnapshotRow(
    snapshot: Snapshot,
    previewing: Boolean,
    onIntent: (EditorIntent) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = LoDimens.TouchTargetMin)
            .clip(LoShapes.Small)
            .background(if (previewing) LoColors.Surface3 else LoColors.Surface1)
            .clickable(role = Role.Button) { onIntent(EditorIntent.Preview(HistoryPreview.SnapshotState(snapshot.id))) }
            .padding(start = LoSpacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(snapshot.name, style = LoType.Body, color = LoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(date(snapshot.created), style = LoType.Caption, color = LoColors.TextSecondary)
        }
        LoIconButton(LoIcons.Delete, "Delete snapshot ${snapshot.name}", onClick = { onIntent(EditorIntent.DeleteSnapshot(snapshot.id)) })
    }
}

/** Versions side by side: a thumbnail each; tap one to edit it. */
@Composable
private fun VersionGrid(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
) {
    LoChip(
        "New version",
        selected = false,
        onClick = { onIntent(EditorIntent.OpenNameDialog(NameDialog.NewVersion(state.nextVersionName()))) },
        leadingIcon = LoIcons.Add,
    )
    val versions = state.document.versions
    versions.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(LoSpacing.S)) {
            pair.forEach { version ->
                VersionCard(version, version.id == state.document.activeVersion, state, onIntent, Modifier.weight(1f))
            }
            if (pair.size == 1) Box(Modifier.weight(1f))
        }
    }
    if (versions.size == 1) {
        Text("Make a version to try another look; each keeps its own history.", style = LoType.Body, color = LoColors.TextSecondary)
    }
}

@Composable
private fun VersionCard(
    version: Version,
    active: Boolean,
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .clip(LoShapes.Medium)
            .border(if (active) 2.dp else 1.dp, if (active) LoColors.Accent else LoColors.Outline, LoShapes.Medium)
            .clickable(role = Role.Button) { onIntent(EditorIntent.SwitchVersion(version.id)) }
            .semantics { selected = active }
            .testTag(EditorTags.version(version.id)),
    ) {
        val thumb = state.versionThumbnails[version.id]
        Box(Modifier.fillMaxWidth().aspectRatio(THUMB_ASPECT).background(LoColors.Surface2), contentAlignment = Alignment.Center) {
            if (thumb != null) {
                Image(
                    thumb.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(THUMB_ASPECT),
                )
            } else {
                Icon(painterResource(LoIcons.Image), contentDescription = null, tint = LoColors.TextDisabled)
            }
        }
        // Name and state on their own lines (cards are narrow in the 320 dp side panel), actions below.
        Column(
            Modifier.fillMaxWidth().padding(horizontal = LoSpacing.Xs, vertical = LoSpacing.Xxs).semantics(mergeDescendants = true) {
                contentDescription = "${version.name}${if (active) ", editing" else ""}"
            },
        ) {
            Text(version.name, style = LoType.Body, color = LoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (active) "Editing" else "${version.history.entries.size - 1} steps",
                style = LoType.Caption,
                color = if (active) LoColors.Accent else LoColors.TextSecondary,
                maxLines = 1,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LoIconButton(LoIcons.Edit, "Rename ${version.name}", onClick = {
                onIntent(EditorIntent.OpenNameDialog(NameDialog.RenameVersion(version.id, version.name)))
            })
            if (state.document.versions.size > 1) {
                LoIconButton(LoIcons.Delete, "Delete ${version.name}", onClick = { onIntent(EditorIntent.DeleteVersion(version.id)) })
            }
        }
    }
}

@Composable
private fun NameDialogView(
    dialog: NameDialog,
    onIntent: (EditorIntent) -> Unit,
) {
    var text by rememberSaveable(dialog) { mutableStateOf(dialog.initial) }
    val title =
        when (dialog) {
            is NameDialog.NewSnapshot -> "Save snapshot"
            is NameDialog.NewVersion -> "New version"
            is NameDialog.RenameVersion -> "Rename version"
        }
    AlertDialog(
        onDismissRequest = { onIntent(EditorIntent.OpenNameDialog(null)) },
        containerColor = LoColors.Surface2,
        title = { Text(title, style = LoType.Headline, color = LoColors.TextPrimary) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(EditorViewModel.MAX_NAME) },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.testTag(EditorTags.NAME_FIELD),
            )
        },
        confirmButton = {
            TextButton(onClick = { onIntent(EditorIntent.ConfirmName(text)) }) { Text("Save", color = LoColors.Accent) }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(EditorIntent.OpenNameDialog(null)) }) { Text("Cancel", color = LoColors.TextSecondary) }
        },
    )
}

private fun date(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private const val MAX_ROWS = 100
private const val THUMB_ASPECT = 4f / 3f
