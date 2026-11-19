package dev.mark.lineover.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoDimens
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.LocalReducedMotion
import dev.mark.lineover.core.designsystem.component.LoEmptyState
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.designsystem.component.LoPlaceholderTool
import dev.mark.lineover.core.designsystem.component.LoSegmented
import dev.mark.lineover.core.io.lens.LensProfileState
import dev.mark.lineover.core.io.lens.LensProfileStore
import dev.mark.lineover.core.io.lens.LensfunManifest
import dev.mark.lineover.core.io.prefs.AppPreferences
import dev.mark.lineover.core.io.prefs.CanvasSurround
import dev.mark.lineover.core.io.prefs.TapGestures
import dev.mark.lineover.core.ui.adaptive.rememberLoWindowInfo
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun SettingsRoute(
    versionName: String,
    preferences: AppPreferences,
    onBack: () -> Unit,
    onOpenSection: (SettingsSection) -> Unit,
    onOpenGallery: (() -> Unit)?,
    lensProfiles: LensProfileStore? = null,
) {
    val viewModel =
        viewModel {
            SettingsViewModel(
                versionName,
                showDebug = onOpenGallery != null,
                preferences = preferences,
                lensProfiles = lensProfiles,
            )
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onBack = onBack,
        onOpenSection = onOpenSection,
        onOpenGallery = onOpenGallery,
        twoPane = rememberLoWindowInfo().showsTwoPanes,
    )
}

/**
 * Settings. On compact and medium windows a tap opens the section on its own screen
 * ([onOpenSection]); on expanded windows the list and the selected section sit side by side.
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
    onBack: () -> Unit,
    onOpenSection: (SettingsSection) -> Unit,
    onOpenGallery: (() -> Unit)?,
    twoPane: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(LoColors.Bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        TitleBar("Settings", onBack)
        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                SectionList(
                    state,
                    Modifier.width(LoDimens.SidePanelWidth + LoSpacing.Xl).fillMaxHeight(),
                ) { onIntent(SettingsIntent.Select(it)) }
                Box(Modifier.width(1.dp).fillMaxHeight().background(LoColors.Outline))
                SectionDetail(state.selected, state, onIntent, onOpenGallery, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            SectionList(state, Modifier.fillMaxSize()) { section ->
                onIntent(SettingsIntent.Select(section))
                onOpenSection(section)
            }
        }
    }
}

/** One section on its own screen (compact and medium windows). */
@Composable
fun SettingsSectionScreen(
    section: SettingsSection,
    versionName: String,
    preferences: AppPreferences,
    onBack: () -> Unit,
    onOpenGallery: (() -> Unit)?,
    modifier: Modifier = Modifier,
    lensProfiles: LensProfileStore? = null,
) {
    val surround by preferences.surround.collectAsStateWithLifecycle()
    val lensState = lensProfiles?.state?.collectAsStateWithLifecycle()?.value ?: LensProfileState.Missing
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().background(LoColors.Bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        TitleBar(section.title, onBack)
        SectionDetail(
            section,
            SettingsState(versionName = versionName, surround = surround, lensProfiles = lensState),
            onIntent = {
                when (it) {
                    is SettingsIntent.SetSurround -> preferences.setSurround(it.surround)
                    is SettingsIntent.DownloadLensProfiles -> lensProfiles?.let { store -> scope.launch { store.download() } }
                    is SettingsIntent.DeleteLensProfiles -> lensProfiles?.let { store -> scope.launch { store.delete() } }
                    else -> Unit
                }
            },
            onOpenGallery,
            Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun TitleBar(
    title: String,
    onBack: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        LoIconButton(LoIcons.ArrowBack, "Back", onClick = onBack)
        Text(title, style = LoType.Headline, color = LoColors.TextPrimary)
    }
}

@Composable
private fun SectionList(
    state: SettingsState,
    modifier: Modifier,
    onSelect: (SettingsSection) -> Unit,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = LoSpacing.Xs)) {
        state.sections.forEach { section -> SectionRow(section, selected = section == state.selected, onClick = { onSelect(section) }) }
    }
}

@Composable
private fun SectionRow(
    section: SettingsSection,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(if (selected) LoColors.Surface1 else Color.Transparent)
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .testTag("section:${section.id}")
            .padding(horizontal = LoSpacing.M, vertical = LoSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.M),
    ) {
        Icon(
            painterResource(section.icon),
            contentDescription = null,
            tint = if (selected) LoColors.Accent else LoColors.TextSecondary,
            modifier = Modifier.size(LoDimens.IconSize),
        )
        Column(Modifier.weight(1f)) {
            Text(section.title, style = LoType.Title.copy(fontWeight = LoType.Body.fontWeight), color = LoColors.TextPrimary)
            Text(section.summary, style = LoType.Caption, color = LoColors.TextSecondary)
        }
        section.stage?.let { Text("Stage $it", style = LoType.Micro, color = LoColors.TextSecondary) }
    }
}

@Composable
private fun SectionDetail(
    section: SettingsSection,
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
    onOpenGallery: (() -> Unit)?,
    modifier: Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(LoSpacing.M),
        verticalArrangement = Arrangement.spacedBy(LoSpacing.S),
    ) {
        when (section) {
            SettingsSection.AiModels -> {
                LensProfilesRow(state.lensProfiles, state.lensProfilesBytes, onIntent)
                AiModelsDetail(state.models, onIntent)
            }

            SettingsSection.About -> {
                AboutDetail(state.versionName)
            }

            SettingsSection.Debug -> {
                DebugDetail(onOpenGallery)
            }

            SettingsSection.SurroundColor -> {
                Text(section.summary, style = LoType.Body, color = LoColors.TextSecondary)
                LoSegmented(
                    options = listOf("Dark", "Mid grey"),
                    selectedIndex = if (state.surround == CanvasSurround.MidGrey) 1 else 0,
                    onSelect = { onIntent(SettingsIntent.SetSurround(if (it == 1) CanvasSurround.MidGrey else CanvasSurround.Dark)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Mid grey helps judge brightness and contrast; dark keeps attention on the photo.",
                    style = LoType.Caption,
                    color = LoColors.TextSecondary,
                )
            }

            SettingsSection.GesturesHelp -> {
                GesturesDetail(state.tapGestures, onIntent)
            }

            SettingsSection.ReducedMotion -> {
                val on = LocalReducedMotion.current
                Text(
                    "System animations are ${if (on) "off: LineOver snaps instead of animating" else "on: LineOver animates normally"}.",
                    style = LoType.Body,
                    color = LoColors.TextPrimary,
                )
                ReservedNote(section)
            }

            else -> {
                ReservedNote(section)
            }
        }
    }
}

/** Settings → Gestures: what the canvas understands, and the undo / redo taps (Stage 8). */
@Composable
private fun GesturesDetail(
    taps: TapGestures,
    onIntent: (SettingsIntent) -> Unit,
) {
    Text("Undo and redo taps", style = LoType.Title, color = LoColors.TextPrimary)
    LoSegmented(
        options = listOf("2 undo · 3 redo", "2 redo · 3 undo", "Off"),
        selectedIndex = taps.ordinal,
        onSelect = { onIntent(SettingsIntent.SetTapGestures(TapGestures.entries[it])) },
        modifier = Modifier.fillMaxWidth().testTag("tap-gestures"),
        descriptions = TapGestures.entries.map { it.title },
    )
    Text(
        "Tap the photo with two or three fingers at once and lift them quickly. Fingers that move or stay down " +
            "longer than a quarter of a second zoom or pan instead.",
        style = LoType.Caption,
        color = LoColors.TextSecondary,
    )
    Text(
        "On HyperOS, swiping down with three fingers takes a screenshot and a three-finger tap can be taken by the " +
            "system. If redo does not respond, turn off the three-finger gesture in Settings → Additional settings → " +
            "Gesture shortcuts, choose \"2 redo · 3 undo\", or use the Redo button in the top bar (always there).",
        style = LoType.Caption,
        color = LoColors.TextSecondary,
    )
    Text("On the photo", style = LoType.Title, color = LoColors.TextPrimary)
    listOf(
        "Pinch to zoom (to 32×), drag with two fingers to pan; double tap fits or zooms to 100 %.",
        "Hold one finger still on the photo to see the original.",
        "Sliders: drag to change, double tap to reset, put a second finger down to see clipping.",
        "History: every change is a step; a slider drag is one step when you lift your finger.",
    ).forEach { Text("• $it", style = LoType.Body, color = LoColors.TextSecondary) }
}

@Composable
private fun ReservedNote(section: SettingsSection) {
    val stage = section.stage ?: return
    LoPlaceholderTool(section.title, stage = stage, icon = section.icon)
    Text(section.summary, style = LoType.Body, color = LoColors.TextSecondary)
}

@Composable
private fun AiModelsDetail(
    models: List<AiModelEntry>,
    onIntent: (SettingsIntent) -> Unit,
) {
    Text(
        "AI tools run on this phone. A model is downloaded the first time you use its tool, after showing its size " +
            "and licence, and is checked with SHA-256. Nothing is bundled in the app.",
        style = LoType.Body,
        color = LoColors.TextSecondary,
    )
    if (models.isEmpty()) {
        LoEmptyState(
            LoIcons.AutoFixHigh,
            "No models downloaded",
            body = "The first AI tools (subject and sky selection) arrive in Stage 16. Downloaded models will be listed here.",
        )
    } else {
        models.forEach { AiModelRow(it, onDelete = { onIntent(SettingsIntent.DeleteModel(it.id)) }) }
    }
}

/**
 * The Lensfun lens profile database (Stage 9): its size and licence, Download (nothing is
 * fetched until tapped) or Delete, and the attribution the CC BY-SA licence asks for.
 */
@Composable
internal fun LensProfilesRow(
    lens: LensProfileState,
    bytes: Long,
    onIntent: (SettingsIntent) -> Unit,
) {
    val status =
        when (lens) {
            is LensProfileState.Missing -> "Not downloaded"
            is LensProfileState.Downloading -> "Downloading ${lens.bytes * PERCENT / lens.total.coerceAtLeast(1)} %"
            is LensProfileState.Installed -> "Downloaded"
            is LensProfileState.Included -> "Included in the app"
            is LensProfileState.Failed -> "Download failed: ${lens.message}"
        }
    Column(Modifier.fillMaxWidth().testTag("lens-profiles")) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .background(LoColors.Surface1)
                .padding(start = LoSpacing.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Lens profiles (Lensfun)", style = LoType.Body, color = LoColors.TextPrimary)
                Text("${formatSize(bytes)} · ${LensfunManifest.LICENCE} · $status", style = LoType.Caption, color = LoColors.TextSecondary)
            }
            if (lens is LensProfileState.Included) {
                // Part of the app: nothing to download or delete.
            } else if (lens is LensProfileState.Installed) {
                LoIconButton(LoIcons.Delete, "Delete lens profiles", onClick = { onIntent(SettingsIntent.DeleteLensProfiles) })
            } else {
                LoIconButton(
                    LoIcons.Download,
                    "Download lens profiles",
                    onClick = { onIntent(SettingsIntent.DownloadLensProfiles) },
                    enabled = lens !is LensProfileState.Downloading,
                )
            }
        }
        Text(
            "Distortion, chromatic aberration and vignetting corrections for thousands of camera lenses " +
                "(Optics → Lens distortion → Lens profile). Phone cameras are not in the database. " +
                LensfunManifest.ATTRIBUTION + ".",
            style = LoType.Caption,
            color = LoColors.TextSecondary,
            modifier = Modifier.padding(horizontal = LoSpacing.M, vertical = LoSpacing.Xs),
        )
    }
}

private const val PERCENT = 100

/** One model of the download manager: name, size, licence, state, delete. */
@Composable
fun AiModelRow(
    model: AiModelEntry,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(LoColors.Surface1)
            .padding(start = LoSpacing.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(model.name, style = LoType.Body, color = LoColors.TextPrimary)
            Text(
                "${formatSize(model.sizeBytes)} · ${model.licence} · ${if (model.downloaded) "Downloaded" else "Not downloaded"}",
                style = LoType.Caption,
                color = LoColors.TextSecondary,
            )
        }
        LoIconButton(LoIcons.Delete, "Delete ${model.name}", onClick = onDelete, enabled = model.downloaded)
    }
}

/** Human-readable size: "1.2 MB", "834 KB". */
fun formatSize(bytes: Long): String {
    val kb = bytes / BYTES_PER_KB
    return if (kb < BYTES_PER_KB) "${kb.roundToLong().coerceAtLeast(1)} KB" else String.format(Locale.ROOT, "%.1f MB", kb / BYTES_PER_KB)
}

private const val BYTES_PER_KB = 1024.0

@Composable
private fun AboutDetail(versionName: String) {
    Text("LineOver $versionName", style = LoType.Title, color = LoColors.TextPrimary)
    Text("A non-destructive photo editor. Everything runs on this phone.", style = LoType.Body, color = LoColors.TextSecondary)
    Spacer(Modifier.size(LoSpacing.Xs))
    Text("Open-source licences", style = LoType.Title, color = LoColors.TextPrimary)
    OpenSourceLicences.forEach { item ->
        Column(Modifier.fillMaxWidth().padding(vertical = LoSpacing.Xxs)) {
            Text(item.name, style = LoType.Body, color = LoColors.TextPrimary)
            Text("${item.licence} · ${item.url}", style = LoType.Caption, color = LoColors.TextSecondary)
        }
    }
}

@Composable
private fun DebugDetail(onOpenGallery: (() -> Unit)?) {
    if (onOpenGallery == null) {
        Text("Debug tools are only in debug builds.", style = LoType.Body, color = LoColors.TextSecondary)
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(
                min = 56.dp,
            ).background(LoColors.Surface1)
            .clickable(role = Role.Button, onClick = onOpenGallery)
            .padding(horizontal = LoSpacing.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Component gallery", style = LoType.Body, color = LoColors.TextPrimary, modifier = Modifier.weight(1f))
        Icon(painterResource(LoIcons.BugReport), contentDescription = null, tint = LoColors.TextSecondary)
    }
}
