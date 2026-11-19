package dev.mark.lineover.feature.editor

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoShapes
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoChip
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.designsystem.component.LoSlider
import dev.mark.lineover.core.designsystem.component.LoSliderFill
import dev.mark.lineover.core.designsystem.component.LoSwitchRow
import dev.mark.lineover.core.io.looks.LooksLibrary
import dev.mark.lineover.core.model.BuiltInPresets
import dev.mark.lineover.core.model.LookAdjustments
import dev.mark.lineover.core.model.PresetMath
import dev.mark.lineover.core.model.SettingsGroup
import dev.mark.lineover.core.ui.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The panel of one Stage 11 tool. */
@Composable
internal fun LooksPanel(
    toolId: String,
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    onClose: () -> Unit,
) {
    val looks: (LooksAction) -> Unit = { onIntent(EditorIntent.Looks(it)) }
    Column(Modifier.fillMaxWidth().testTag(LooksTags.PANEL)) {
        PanelHeader(requireNotNull(ToolRegistry[toolId]).name, subtitle(toolId, state), onClose)
        Column(Modifier.testTag(EditorTags.slot(toolId))) {
            when (toolId) {
                PRESETS_TOOL -> PresetsControls(state, onIntent, looks)
                PROFILES_TOOL -> ProfilesControls(state, looks) { onIntent(EditorIntent.EndEdit) }
                LUTS_TOOL -> LutsControls(state, looks) { onIntent(EditorIntent.EndEdit) }
                COPY_PASTE_TOOL -> CopyPasteControls(state, looks)
                AUTO_TOOL -> AutoControls(state, looks)
            }
        }
    }
    state.nameDialog?.let { if (it is NameDialog.NewPreset) NameDialogView(it, onIntent) }
}

private fun subtitle(
    toolId: String,
    state: EditorState,
): String? =
    when (toolId) {
        PRESETS_TOOL -> {
            "Tap to apply · hold to compare"
        }

        PROFILES_TOOL -> {
            "Base look · ${LookAdjustments.label(state.looks.profile)}"
        }

        LUTS_TOOL -> {
            state.looks.lutId?.let { id ->
                state.looksLibrary.luts
                    .firstOrNull { it.id == id }
                    ?.name ?: "LUT not on this device"
            }
        }

        COPY_PASTE_TOOL -> {
            state.looksLibrary.copied?.let { c -> "Copied: " + c.groups.joinToString(", ") { it.label } }
        }

        else -> {
            null
        }
    }

/** One thumbnail of a strip: tap applies, a long press shows it on the canvas until released. */
@Composable
private fun LookThumbnail(
    target: LookTarget,
    label: String,
    state: EditorState,
    selected: Boolean,
    looks: (LooksAction) -> Unit,
    favorite: Boolean? = null,
) {
    val bitmap = state.lookThumbnails[target.key]
    Column(
        Modifier.width(THUMB_WIDTH).testTag(LooksTags.look(target.key)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(THUMB_WIDTH, THUMB_HEIGHT)
                .clip(LoShapes.Small)
                .background(LoColors.Surface3)
                .then(if (selected) Modifier.border(2.dp, LoColors.Accent, LoShapes.Small) else Modifier)
                .semantics { contentDescription = label }
                .pointerInput(target) {
                    detectTapGestures(
                        onTap = { looks(LooksAction.Apply(target)) },
                        onLongPress = { looks(LooksAction.Compare(target)) },
                        onPress = {
                            tryAwaitRelease()
                            looks(LooksAction.Compare(null))
                        },
                    )
                },
        ) {
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            }
            if (favorite != null && target is LookTarget.Preset) {
                // A small star in the corner (40 dp to touch): the rest of the thumbnail stays tap / hold.
                val description = if (favorite) "Remove $label from favourites" else "Add $label to favourites"
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(STAR_TOUCH)
                        .clickable(onClickLabel = description) { looks(LooksAction.ToggleFavorite(target.id)) }
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(LoIcons.Star),
                        contentDescription = null,
                        tint = if (favorite) LoColors.Accent else Color.White,
                        modifier = Modifier.size(STAR_SIZE),
                    )
                }
            }
        }
        Text(
            label,
            style = LoType.Caption,
            color = if (selected) LoColors.Accent else LoColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = LoSpacing.Xxs),
        )
    }
}

@Composable
private fun Strip(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) =
    LazyRow(
        Modifier.fillMaxWidth().testTag(LooksTags.STRIP),
        horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(horizontal = LoSpacing.M, vertical = LoSpacing.Xs),
        content = content,
    )

@Composable
private fun PresetsControls(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    looks: (LooksAction) -> Unit,
) {
    val favorites = state.looksLibrary.favoritePresets
    val ordered = state.allPresets.sortedBy { if (it.id in favorites) favorites.indexOf(it.id) else favorites.size }
    Strip {
        items(ordered, key = { it.id }) { preset ->
            LookThumbnail(
                LookTarget.Preset(preset.id),
                preset.name,
                state,
                selected = state.appliedPreset?.id == preset.id,
                looks = looks,
                favorite = preset.id in favorites,
            )
        }
    }
    val applied = state.appliedPreset
    if (applied != null) {
        LoSlider(
            applied.amount * PERCENT,
            { looks(LooksAction.PresetAmount(it / PERCENT)) },
            "Amount",
            valueRange = 0f..PresetMath.MAX_AMOUNT * PERCENT,
            defaultValue = PERCENT,
            fill = LoSliderFill.FromStart,
            valueText = { "${it.roundToInt()} %" },
            onValueChangeFinished = { onIntent(EditorIntent.EndEdit) },
        )
    }
    Row(Modifier.padding(horizontal = LoSpacing.M), horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        LoChip(
            "Save current as preset",
            selected = false,
            onClick = { onIntent(EditorIntent.OpenNameDialog(NameDialog.NewPreset("My preset ${state.userPresets.size + 1}"))) },
            leadingIcon = LoIcons.Add,
            modifier = Modifier.testTag(LooksTags.SAVE_PRESET),
        )
        if (applied != null && applied.id.startsWith("user.")) {
            LoChip("Delete", selected = false, onClick = { looks(LooksAction.DeletePreset(applied.id)) }, leadingIcon = LoIcons.Delete)
        }
    }
    Text(
        "${BuiltInPresets.ALL.size} LineOver presets · ${state.userPresets.size} yours (${LooksLibrary.USER_GROUP})",
        style = LoType.Caption,
        color = LoColors.TextSecondary,
        modifier = Modifier.padding(LoSpacing.M),
    )
}

@Composable
private fun ProfilesControls(
    state: EditorState,
    looks: (LooksAction) -> Unit,
    endEdit: () -> Unit,
) {
    Strip {
        items(LookAdjustments.PROFILES, key = { it }) { name ->
            LookThumbnail(
                LookTarget.Profile(name),
                LookAdjustments.label(name),
                state,
                selected = state.looks.profile == name,
                looks = looks,
            )
        }
    }
    if (state.looks.profile != LookAdjustments.NEUTRAL) {
        LoSlider(
            state.looks.profileAmount,
            { looks(LooksAction.ProfileAmount(it)) },
            "Amount",
            valueRange = 0f..LookAdjustments.FULL,
            defaultValue = LookAdjustments.FULL,
            fill = LoSliderFill.FromStart,
            valueText = { "${it.roundToInt()} %" },
            onValueChangeFinished = endEdit,
        )
    }
}

@Composable
private fun LutsControls(
    state: EditorState,
    looks: (LooksAction) -> Unit,
    endEdit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val (name, bytes) =
                    withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        val name =
                            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                                if (c.moveToFirst()) c.getString(0) else null
                            } ?: "LUT.cube"
                        val bytes = resolver.openInputStream(uri)?.use { it.readNBytes(LooksLibrary.MAX_CUBE_BYTES + 1) } ?: ByteArray(0)
                        name to bytes
                    }
                looks(LooksAction.ImportLut(name, bytes))
            }
        }
    Strip {
        item(key = "none") { LookThumbnail(LookTarget.Lut(null), "None", state, selected = state.looks.lutId == null, looks = looks) }
        items(state.looksLibrary.luts, key = { it.id }) { lut ->
            LookThumbnail(LookTarget.Lut(lut.id), lut.name, state, selected = state.looks.lutId == lut.id, looks = looks)
        }
    }
    val current = state.looks.lutId
    if (current != null) {
        LoSlider(
            state.looks.lutAmount,
            { looks(LooksAction.LutAmount(it)) },
            "Amount",
            valueRange = 0f..LookAdjustments.FULL,
            defaultValue = LookAdjustments.FULL,
            fill = LoSliderFill.FromStart,
            valueText = { "${it.roundToInt()} %" },
            onValueChangeFinished = endEdit,
        )
    }
    Row(Modifier.padding(horizontal = LoSpacing.M), horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        LoChip(
            "Import .cube",
            selected = false,
            onClick = { open.launch(arrayOf("*/*")) },
            leadingIcon = LoIcons.Add,
            modifier = Modifier.testTag(LooksTags.IMPORT_LUT),
        )
        if (current != null && state.looksLibrary.luts.any { it.id == current }) {
            LoChip("Remove", selected = false, onClick = { looks(LooksAction.DeleteLut(current)) }, leadingIcon = LoIcons.Delete)
        }
    }
    Text(
        "3D LUTs (.cube, 2–65 points) stay on this phone; projects refer to them.",
        style = LoType.Caption,
        color = LoColors.TextSecondary,
        modifier = Modifier.padding(LoSpacing.M),
    )
}

@Composable
private fun CopyPasteControls(
    state: EditorState,
    looks: (LooksAction) -> Unit,
) {
    for (group in SettingsGroup.entries) {
        LoSwitchRow(
            group.label,
            checked = group in state.copyGroups,
            onCheckedChange = { looks(LooksAction.ToggleGroup(group)) },
            modifier = Modifier.testTag(LooksTags.copyGroup(group.name)),
        )
    }
    Row(Modifier.padding(LoSpacing.M), horizontalArrangement = Arrangement.spacedBy(LoSpacing.Xs)) {
        LoChip("Copy", selected = false, onClick = { looks(LooksAction.Copy) }, leadingIcon = LoIcons.ContentCopy)
        LoChip(
            "Paste",
            selected = false,
            enabled = state.looksLibrary.copied != null,
            onClick = { looks(LooksAction.Paste) },
            leadingIcon = LoIcons.ContentPaste,
        )
    }
}

@Composable
private fun AutoControls(
    state: EditorState,
    looks: (LooksAction) -> Unit,
) {
    Text(
        "White balance from the average colour, exposure, contrast, whites and blacks from the brightness spread, " +
            "and a little vibrance on muted photos. One step in History.",
        style = LoType.Body,
        color = LoColors.TextSecondary,
        modifier = Modifier.padding(horizontal = LoSpacing.M, vertical = LoSpacing.Xs),
    )
    Row(Modifier.padding(LoSpacing.M)) {
        LoChip(
            "Auto",
            selected = false,
            onClick = { looks(LooksAction.Auto) },
            leadingIcon = LoIcons.AutoAwesome,
            modifier = Modifier.testTag(LooksTags.AUTO),
        )
    }
    Text(
        "Now: ${state.color.temperature.roundToInt()} K, exposure ${"%.2f".format(
            java.util.Locale.ROOT,
            state.light[dev.mark.lineover.core.model.LightParam.Exposure],
        )} EV",
        style = LoType.Caption,
        color = LoColors.TextSecondary,
        modifier = Modifier.padding(horizontal = LoSpacing.M),
    )
}

/** Test tags of the Stage 11 panels. */
object LooksTags {
    const val PANEL = "looks-panel"
    const val STRIP = "look-strip"
    const val SAVE_PRESET = "save-preset"
    const val IMPORT_LUT = "import-lut"
    const val AUTO = "auto-apply"

    fun look(key: String) = "look:$key"

    fun copyGroup(name: String) = "copy-group:$name"
}

private val THUMB_WIDTH = 72.dp
private val STAR_TOUCH = 40.dp
private val STAR_SIZE = 20.dp
private val THUMB_HEIGHT = 72.dp
private const val PERCENT = 100f
