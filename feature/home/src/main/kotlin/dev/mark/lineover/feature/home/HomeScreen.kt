package dev.mark.lineover.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.LocalReducedMotion
import dev.mark.lineover.core.designsystem.MotionTokens
import dev.mark.lineover.core.designsystem.component.LoEmptyState
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.designsystem.component.LoSegmented
import dev.mark.lineover.core.designsystem.component.LoToast
import dev.mark.lineover.core.designsystem.component.LoToastHost
import dev.mark.lineover.core.designsystem.component.LoToastState
import dev.mark.lineover.core.model.Project
import dev.mark.lineover.core.ui.adaptive.LoWidthClass
import dev.mark.lineover.core.ui.adaptive.rememberLoWindowInfo
import dev.mark.lineover.core.ui.image.ProjectThumbnail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Home: the project grid with the primary actions in the bottom 40 % of the screen
 * (tab switch, Open file, the large Import button) for one-handed use.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    onIntent: (HomeIntent) -> Unit,
    onImport: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    toast: LoToastState = remember { LoToastState() },
) {
    val window = rememberLoWindowInfo()
    Box(modifier.fillMaxSize().background(LoColors.Bg)) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = LoSpacing.M, end = LoSpacing.Xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("LineOver", style = LoType.Headline, color = LoColors.TextPrimary, modifier = Modifier.weight(1f))
                LoIconButton(LoIcons.Settings, "Settings", onClick = onOpenSettings)
            }
            when {
                !state.loaded -> {
                    Box(Modifier.weight(1f))
                }

                state.visible.isEmpty() -> {
                    EmptyHome(state.tab, Modifier.weight(1f))
                }

                else -> {
                    ProjectGrid(
                        projects = state.visible,
                        minCellWidth = if (window.widthClass == LoWidthClass.Compact) 150.dp else 190.dp,
                        onIntent = onIntent,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusToasts(state, onIntent, toast)
            BottomActions(state.tab, onIntent, onImport, onOpenFile)
            // Opaque strip behind the gesture / 3-button navigation bar (edge-to-edge).
            Box(Modifier.fillMaxWidth().background(LoColors.Bg).windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
    state.renaming?.let { RenameDialog(it, onIntent) }
}

@Composable
private fun EmptyHome(
    tab: HomeTab,
    modifier: Modifier,
) {
    Box(modifier.fillMaxWidth().padding(bottom = BOTTOM_BAR_SPACE), contentAlignment = Alignment.Center) {
        LoEmptyState(
            icon = LoIcons.AddPhotoAlternate,
            title = if (tab == HomeTab.Recent) "Nothing here yet" else "No projects yet",
            body =
                "Tap Import to pick photos from your gallery, or the folder button to open a RAW, TIFF " +
                    "or .loproj file. Your originals are never changed.",
        )
    }
}

@Composable
private fun ProjectGrid(
    projects: List<Project>,
    minCellWidth: Dp,
    onIntent: (HomeIntent) -> Unit,
    modifier: Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minCellWidth),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = LoSpacing.M, end = LoSpacing.M, top = LoSpacing.Xs, bottom = BOTTOM_BAR_SPACE),
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.S),
        verticalArrangement = Arrangement.spacedBy(LoSpacing.M),
    ) {
        items(projects, key = { it.id }) { project -> ProjectCard(project, onIntent) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: Project,
    onIntent: (HomeIntent) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .clip(LoShapes.Medium)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = "Open",
                    onLongClickLabel = "Project actions",
                    onLongClick = { menuOpen = true },
                    onClick = { onIntent(HomeIntent.Open(project.id)) },
                ).padding(bottom = LoSpacing.Xs),
        ) {
            ProjectThumbnail(project.thumbPath, Modifier.fillMaxWidth().aspectRatio(1f).clip(LoShapes.Medium))
            Text(
                project.name,
                style = LoType.Body,
                color = LoColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = LoSpacing.Xs, start = LoSpacing.Xxs, end = LoSpacing.Xxs),
            )
            Text(
                DATE_FORMAT.format(Instant.ofEpochMilli(project.modified)),
                style = LoType.Caption,
                color = LoColors.TextSecondary,
                modifier = Modifier.padding(horizontal = LoSpacing.Xxs),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = LoColors.Surface2) {
            MenuItem("Rename", LoIcons.Edit) {
                menuOpen = false
                onIntent(HomeIntent.StartRename(project))
            }
            MenuItem("Duplicate", LoIcons.ContentCopy) {
                menuOpen = false
                onIntent(HomeIntent.Duplicate(project.id))
            }
            MenuItem("Delete", LoIcons.Delete) {
                menuOpen = false
                onIntent(HomeIntent.Delete(project.id))
            }
        }
    }
}

@Composable
private fun MenuItem(
    label: String,
    icon: Int,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label, style = LoType.Body, color = LoColors.TextPrimary) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, tint = LoColors.TextSecondary) },
        onClick = onClick,
    )
}

@Composable
private fun StatusToasts(
    state: HomeState,
    onIntent: (HomeIntent) -> Unit,
    toast: LoToastState,
) {
    val reducedMotion = LocalReducedMotion.current
    val deleted = state.pendingDelete
    var lastDeleted by remember { mutableStateOf<Project?>(null) }
    if (deleted != null) lastDeleted = deleted
    AnimatedVisibility(
        visible = deleted != null,
        enter = fadeIn(MotionTokens.enter(reducedMotion)) + slideInVertically(MotionTokens.enter(reducedMotion)) { it / 2 },
        exit = fadeOut(MotionTokens.exit(reducedMotion)) + slideOutVertically(MotionTokens.exit(reducedMotion)) { it / 2 },
    ) {
        LoToast(
            "Deleted “${lastDeleted?.name.orEmpty()}”",
            actionLabel = "Undo",
            onAction = { onIntent(HomeIntent.UndoDelete) },
            modifier = Modifier.padding(horizontal = LoSpacing.M, vertical = LoSpacing.Xs),
        )
    }
    if (state.importing) LoToast("Importing…", Modifier.padding(horizontal = LoSpacing.M, vertical = LoSpacing.Xs))
    LoToastHost(toast, Modifier.padding(horizontal = LoSpacing.M, vertical = LoSpacing.Xs))
}

@Composable
private fun BottomActions(
    tab: HomeTab,
    onIntent: (HomeIntent) -> Unit,
    onImport: () -> Unit,
    onOpenFile: () -> Unit,
) {
    // Opaque, so grid cards scrolling underneath never show through the actions.
    Row(
        Modifier
            .fillMaxWidth()
            .background(LoColors.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = LoSpacing.M, vertical = LoSpacing.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
    ) {
        Box(Modifier.weight(1f)) {
            LoSegmented(
                options = listOf("Recent", "Projects"),
                selectedIndex = tab.ordinal,
                onSelect = { onIntent(HomeIntent.SelectTab(HomeTab.entries[it])) },
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
        LoIconButton(
            LoIcons.FolderOpen,
            "Open file",
            onClick = onOpenFile,
            modifier = Modifier.background(LoColors.Surface2, LoShapes.Large),
        )
        ExtendedFloatingActionButton(
            onClick = onImport,
            containerColor = LoColors.Accent,
            contentColor = LoColors.OnAccent,
            shape = LoShapes.Large,
            icon = { Icon(painterResource(LoIcons.AddPhotoAlternate), contentDescription = null) },
            text = { Text("Import", style = LoType.Title) },
        )
    }
}

@Composable
private fun RenameDialog(
    project: Project,
    onIntent: (HomeIntent) -> Unit,
) {
    var name by rememberSaveable(project.id) { mutableStateOf(project.name) }
    AlertDialog(
        onDismissRequest = { onIntent(HomeIntent.CancelRename) },
        containerColor = LoColors.Surface2,
        title = { Text("Rename project", style = LoType.Headline, color = LoColors.TextPrimary) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = LoType.Body,
                label = { Text("Name") },
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedTextColor = LoColors.TextPrimary,
                        unfocusedTextColor = LoColors.TextPrimary,
                        focusedBorderColor = LoColors.Accent,
                        unfocusedBorderColor = LoColors.Outline,
                        focusedLabelColor = LoColors.Accent,
                        unfocusedLabelColor = LoColors.TextSecondary,
                        cursorColor = LoColors.Accent,
                    ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onIntent(HomeIntent.ConfirmRename(project.id, name)) }, enabled = name.isNotBlank()) {
                Text("Rename", color = if (name.isNotBlank()) LoColors.Accent else LoColors.TextDisabled)
            }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(HomeIntent.CancelRename) }) { Text("Cancel", color = LoColors.TextSecondary) }
        },
    )
}

/** Room for the bottom actions, so the last grid row can scroll above them. */
private val BOTTOM_BAR_SPACE = 104.dp

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
