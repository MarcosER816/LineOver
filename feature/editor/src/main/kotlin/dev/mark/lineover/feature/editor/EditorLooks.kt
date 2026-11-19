package dev.mark.lineover.feature.editor

import android.graphics.Bitmap
import dev.mark.lineover.core.io.looks.LooksStore
import dev.mark.lineover.core.model.BuiltInPresets
import dev.mark.lineover.core.model.ColorAdjustments
import dev.mark.lineover.core.model.DetailAdjustments
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.GeometryAdjustments
import dev.mark.lineover.core.model.GradeAdjustments
import dev.mark.lineover.core.model.LightAdjustments
import dev.mark.lineover.core.model.LookAdjustments
import dev.mark.lineover.core.model.OpParams
import dev.mark.lineover.core.model.Preset
import dev.mark.lineover.core.model.PresetMath
import dev.mark.lineover.core.model.SettingsGroup
import dev.mark.lineover.core.model.editOps
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Presets, Profiles, 3D LUTs, Copy / paste settings and Auto (Stage 11).

/** The Stage 11 tools. */
internal const val PRESETS_TOOL = "presets.presets"
internal const val PROFILES_TOOL = "presets.profiles"
internal const val LUTS_TOOL = "presets.3d_luts"
internal const val COPY_PASTE_TOOL = "presets.copy_paste_settings"
internal const val AUTO_TOOL = "auto.auto"
internal val LOOKS_TOOLS = setOf(PRESETS_TOOL, PROFILES_TOOL, LUTS_TOOL, COPY_PASTE_TOOL, AUTO_TOOL)

/** The tools whose panel is a thumbnail strip. */
internal val STRIP_TOOLS = setOf(PRESETS_TOOL, PROFILES_TOOL, LUTS_TOOL)

/** What a thumbnail or a long-press shows. */
sealed interface LookTarget {
    val key: String

    data class Preset(
        val id: String,
    ) : LookTarget {
        override val key get() = "preset:$id"
    }

    data class Profile(
        val name: String,
    ) : LookTarget {
        override val key get() = "profile:$name"
    }

    /** A LUT by id; null id = no LUT. */
    data class Lut(
        val id: String?,
    ) : LookTarget {
        override val key get() = "lut:${id ?: "none"}"
    }
}

/** The last preset applied: its Amount moves the photo from [base] (the ops before it). */
data class AppliedPreset(
    val id: String,
    val name: String,
    val base: List<OpParams>,
    val ops: List<OpParams>,
    val amount: Float = 1f,
)

sealed interface LooksAction {
    data class Apply(
        val target: LookTarget,
    ) : LooksAction

    /** Long-press: the canvas shows [target] while the finger stays; null = released. */
    data class Compare(
        val target: LookTarget?,
    ) : LooksAction

    /** Preset Amount 0..2 (0..200 %). */
    data class PresetAmount(
        val amount: Float,
    ) : LooksAction

    data class ProfileAmount(
        val amount: Float,
    ) : LooksAction

    data class LutAmount(
        val amount: Float,
    ) : LooksAction

    data class ToggleFavorite(
        val presetId: String,
    ) : LooksAction

    data class DeletePreset(
        val presetId: String,
    ) : LooksAction

    data class ImportLut(
        val name: String,
        val bytes: ByteArray,
    ) : LooksAction {
        override fun equals(other: Any?) = other is ImportLut && other.name == name && other.bytes.contentEquals(bytes)

        override fun hashCode() = name.hashCode() * 31 + bytes.contentHashCode()
    }

    data class DeleteLut(
        val id: String,
    ) : LooksAction

    data class ToggleGroup(
        val group: SettingsGroup,
    ) : LooksAction

    data object Copy : LooksAction

    data object Paste : LooksAction

    data object Auto : LooksAction

    /** A strip became visible or the edit under it changed: render its thumbnails. */
    data class Thumbnails(
        val toolId: String,
    ) : LooksAction
}

/** The background layer's ops. */
internal val EditorState.backgroundOps: List<OpParams>
    get() =
        graph.layers
            .firstOrNull()
            ?.ops
            .orEmpty()

/** Every preset the strip offers: favourites first, then the built-ins, then the person's own. */
internal val EditorState.allPresets: List<Preset> get() = BuiltInPresets.ALL + userPresets

internal fun EditorState.presetById(id: String): Preset? = allPresets.firstOrNull { it.id == id }

/**
 * [ops] as the edit: every panel reads its values back and the background is rebuilt in the
 * engine's order; [geometry] (crop and optics) is the state's unless pasted.
 */
internal fun EditorState.withBackground(
    ops: List<OpParams>,
    geometry: GeometryAdjustments = this.geometry,
): EditorState {
    val next =
        copy(
            light = LightAdjustments.fromOps(ops),
            color = ColorAdjustments.fromOps(ops),
            grade = GradeAdjustments.fromOps(ops),
            detail = DetailAdjustments.fromOps(ops),
            looks = LookAdjustments.fromOps(ops),
            geometry = geometry,
        )
    return next.copy(
        graph = graph.withBackgroundOps(editOps(next.light, next.color, next.grade, next.geometryOp, next.detail, looks = next.looks)),
    )
}

/** The ops [target] makes of the current edit (what its thumbnail and long-press show). */
internal fun EditorState.opsFor(target: LookTarget): List<OpParams>? {
    val base = appliedPreset?.base ?: backgroundOps
    return when (target) {
        is LookTarget.Preset -> {
            presetById(target.id)?.let { PresetMath.apply(base, it.ops, 1f) }
        }

        is LookTarget.Profile -> {
            backgroundOps.filterNot { it is OpParams.Profile } + OpParams.Profile(target.name, LookAdjustments.FULL)
        }

        is LookTarget.Lut -> {
            val name =
                looksLibrary.luts
                    .firstOrNull { it.id == target.id }
                    ?.name
                    .orEmpty()
            backgroundOps.filterNot { it is OpParams.Lut } + listOfNotNull(target.id?.let { OpParams.Lut(it, name, looks.lutAmount) })
        }
    }
}

/** The graph a target shows: [opsFor] rebuilt in the engine's order. */
internal fun EditorState.graphFor(target: LookTarget): EditGraph? = opsFor(target)?.let { withBackground(it).graph }

/**
 * The Stage 11 intents of [EditorViewModel]. Thumbnails render on the render dispatcher from
 * the 256 px proxy; LUT tables are registered with the engine before a graph names them.
 */
@Suppress("TooManyFunctions") // one function per Stage 11 intent, like the ViewModel it serves
internal class EditorLooksActions(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val host: Host,
    private val store: LooksStore?,
) {
    /** What the actions need of [EditorViewModel]. */
    interface Host {
        val state: EditorState

        fun reduce(
            label: String?,
            block: EditorState.() -> EditorState,
        )

        /** A keyed History step (a preset and its Amount). */
        fun reduceStep(
            label: String?,
            key: String?,
            block: EditorState.() -> EditorState,
        )

        fun setState(block: EditorState.() -> EditorState)

        fun message(text: String)

        /** Sends the canvas graph to the engine again (a LUT it names was just registered). */
        fun resubmit()
    }

    private fun state() = host.state

    private fun reduce(
        label: String?,
        block: EditorState.() -> EditorState,
    ) = host.reduce(label, block)

    private fun reduceStep(
        label: String?,
        key: String?,
        block: EditorState.() -> EditorState,
    ) = host.reduceStep(label, key, block)

    private fun setState(block: EditorState.() -> EditorState) = host.setState(block)

    private fun message(text: String) = host.message(text)

    private fun resubmit() = host.resubmit()

    private val registered = HashSet<String>()
    private var thumbnailJob: Job? = null

    init {
        store?.let { s ->
            scope.launch { s.state.collect { v -> setState { copy(looksLibrary = v) } } }
            scope.launch { s.userPresets.collect { v -> setState { copy(userPresets = v) } } }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    fun onAction(action: LooksAction) {
        when (action) {
            is LooksAction.Apply -> apply(action.target)
            is LooksAction.Compare -> compare(action.target)
            is LooksAction.PresetAmount -> presetAmount(action.amount)
            is LooksAction.ProfileAmount -> reduce(null) { withBackground(looks.copy(profileAmount = action.amount).let { lookOps(it) }) }
            is LooksAction.LutAmount -> reduce(null) { withBackground(lookOps(looks.copy(lutAmount = action.amount))) }
            is LooksAction.ToggleFavorite -> toggleFavorite(action.presetId)
            is LooksAction.DeletePreset -> store?.deletePreset(action.presetId)
            is LooksAction.ImportLut -> importLut(action)
            is LooksAction.DeleteLut -> deleteLut(action.id)
            is LooksAction.ToggleGroup -> setState { copy(copyGroups = copyGroups.toggle(action.group)) }
            is LooksAction.Copy -> copy()
            is LooksAction.Paste -> paste()
            is LooksAction.Auto -> auto()
            is LooksAction.Thumbnails -> thumbnails(action.toolId)
        }
    }

    /** The background with [looks]' profile and LUT in place of the current ones. */
    private fun EditorState.lookOps(looks: LookAdjustments): List<OpParams> =
        backgroundOps.filterNot { it is OpParams.Profile || it is OpParams.Lut } + looks.profileOps() + looks.lutOps()

    private fun apply(target: LookTarget) {
        when (target) {
            is LookTarget.Preset -> {
                val s = state()
                val preset = s.presetById(target.id) ?: return
                val base = s.backgroundOps
                withLuts(preset.ops) {
                    reduceStep("Preset: ${preset.name}", "preset:${preset.id}") {
                        withBackground(PresetMath.apply(base, preset.ops, 1f))
                            .copy(appliedPreset = AppliedPreset(preset.id, preset.name, base, preset.ops))
                    }
                }
            }

            is LookTarget.Profile -> {
                reduce("Profile: ${LookAdjustments.label(target.name)}") {
                    withBackground(lookOps(looks.copy(profile = target.name, profileAmount = LookAdjustments.FULL)))
                }
            }

            is LookTarget.Lut -> {
                applyLut(
                    target.id,
                    state()
                        .looksLibrary.luts
                        .firstOrNull { it.id == target.id }
                        ?.name
                        .orEmpty(),
                )
            }
        }
    }

    private fun applyLut(
        id: String?,
        name: String,
    ) {
        val ops = listOfNotNull(id?.let { OpParams.Lut(it, name) })
        withLuts(ops) {
            reduce(if (id == null) "3D LUT off" else "3D LUT: $name") {
                withBackground(lookOps(looks.copy(lutId = id, lutName = name, lutAmount = LookAdjustments.FULL)))
            }
        }
    }

    private fun compare(target: LookTarget?) {
        // reduce, not setState: the canvas follows canvasGraph (the edit itself is unchanged).
        if (target == null) return reduce(null) { copy(compareGraph = null) }
        val ops = state().opsFor(target) ?: return
        withLuts(ops) { reduce(null) { copy(compareGraph = graphFor(target)) } }
    }

    private fun presetAmount(amount: Float) {
        val applied = state().appliedPreset ?: return
        val a = amount.coerceIn(0f, PresetMath.MAX_AMOUNT)
        reduceStep("Preset: ${applied.name}", "preset:${applied.id}") {
            withBackground(PresetMath.apply(applied.base, applied.ops, a)).copy(appliedPreset = applied.copy(amount = a))
        }
    }

    private fun toggleFavorite(id: String) {
        val store = store ?: return
        store.setFavorite(id, id !in state().looksLibrary.favoritePresets)
    }

    private fun importLut(action: LooksAction.ImportLut) {
        val store = store ?: return message("3D LUTs are not available")
        scope.launch {
            val result = withContext(dispatcher) { store.importCube(action.name, action.bytes) }
            result.fold(
                onSuccess = { entry ->
                    message("Imported ${entry.name} (${entry.size}³)")
                    applyLut(entry.id, entry.name)
                },
                onFailure = { e -> message("Cannot use ${action.name}: ${e.message}") },
            )
        }
    }

    private fun deleteLut(id: String) {
        val store = store ?: return
        if (state().looks.lutId == id) apply(LookTarget.Lut(null))
        store.deleteLut(id)
    }

    private fun copy() {
        val s = state()
        val store = store ?: return message("Copy is not available")
        val ops = SettingsGroup.select(s.backgroundOps, s.copyGroups)
        store.copySettings(s.copyGroups, ops)
        message(if (s.copyGroups.isEmpty()) "Nothing checked to copy" else "Copied ${s.copyGroups.size} groups")
    }

    private fun paste() {
        val copied = state().looksLibrary.copied ?: return message("Copy settings from a photo first")
        val groups = copied.groups.toSet() intersect state().copyGroups
        if (groups.isEmpty()) return message("None of the checked groups were copied")
        withLuts(copied.ops) {
            reduce("Paste settings") {
                val ops = SettingsGroup.paste(backgroundOps, copied.ops, groups)
                // Crop and optics come as panel values and are rebuilt for this photo's size.
                val geometry =
                    if (SettingsGroup.Geometry in groups) GeometryAdjustments.fromOps(copied.ops) else geometry
                withBackground(ops.filterNot { it is OpParams.Geometry }, geometry)
            }
        }
    }

    private fun auto() {
        val canvas = state().canvas ?: return message("Auto needs the photo to finish loading")
        scope.launch {
            val result = withContext(dispatcher) { canvas.autoSettings() }
            if (result == null) {
                message("Auto needs the photo to finish loading")
                return@launch
            }
            reduce("Auto") {
                val light = result.applyTo(light)
                val color = result.applyTo(color)
                copy(light = light, color = color).let { s -> s.withBackground(s.rebuiltOps()) }
            }
        }
    }

    private fun EditorState.rebuiltOps(): List<OpParams> =
        editOps(light, color, grade, geometryOp, detail, looks = looks).filterNot { it is OpParams.Geometry }

    /** Registers the LUTs [ops] name (off the main thread), then runs [then]. */
    private fun withLuts(
        ops: List<OpParams>,
        then: () -> Unit,
    ) {
        val missing = ops.filterIsInstance<OpParams.Lut>().map { it.id }.filterNot { it in registered }
        if (missing.isEmpty()) return then()
        scope.launch {
            withContext(dispatcher) { register(missing) }
            then()
        }
    }

    private fun register(ids: List<String>) {
        val canvas = state().canvas ?: return
        for (id in ids) {
            val lut = store?.loadLut(id) ?: continue
            if (canvas.registerLut(id, lut)) synchronized(registered) { registered += id }
        }
    }

    /** A graph is about to reach the engine: register the LUTs it names that are new. */
    fun onCanvasGraph(graph: EditGraph) {
        val missing =
            graph.layers
                .flatMap { it.ops }
                .filterIsInstance<OpParams.Lut>()
                .map { it.id }
                .filter { synchronized(registered) { it !in registered } }
        if (missing.isEmpty() || store == null) return
        scope.launch {
            withContext(dispatcher) { register(missing) }
            if (missing.any { synchronized(registered) { it in registered } }) resubmit()
        }
    }

    /** Save current as preset: every group but crop and optics. */
    fun savePreset(name: String) {
        val store = store ?: return message("Presets cannot be saved here")
        val ops = SettingsGroup.select(state().backgroundOps, SettingsGroup.DEFAULTS)
        if (ops.isEmpty()) return message("Edit the photo first: there is nothing to save")
        val preset = store.savePreset(name, ops)
        message("Saved preset \"${preset.name}\"")
        thumbnails(PRESETS_TOOL)
    }

    /** After a state change: an open strip shows its looks on the current edit. */
    fun afterSync(
        before: EditorState?,
        after: EditorState,
    ) {
        val tool = after.selectedToolId ?: return
        val changed = before == null || before.graph != after.graph || before.selectedToolId != tool
        if (tool in STRIP_TOOLS && changed) thumbnails(tool)
    }

    private fun thumbnails(toolId: String) {
        val targets = thumbnailTargets(toolId)
        if (targets.isEmpty()) return
        thumbnailJob?.cancel()
        thumbnailJob =
            scope.launch {
                delay(THUMBNAIL_DEBOUNCE_MILLIS)
                val canvas = state().canvas ?: return@launch
                val lutIds = state().looksLibrary.luts.map { it.id }
                withContext(dispatcher) { register(lutIds.filterNot { synchronized(registered) { it in registered } }) }
                for (target in targets) {
                    val bitmap: Bitmap? = state().graphFor(target)?.let { g -> withContext(dispatcher) { canvas.smallThumbnail(g) } }
                    if (bitmap != null) setState { copy(lookThumbnails = lookThumbnails + (target.key to bitmap)) }
                }
            }
    }

    private fun thumbnailTargets(toolId: String): List<LookTarget> {
        val s = state()
        return when (toolId) {
            PRESETS_TOOL -> s.allPresets.map { LookTarget.Preset(it.id) }
            PROFILES_TOOL -> LookAdjustments.PROFILES.map { LookTarget.Profile(it) }
            LUTS_TOOL -> listOf(LookTarget.Lut(null)) + s.looksLibrary.luts.map { LookTarget.Lut(it.id) }
            else -> emptyList()
        }
    }

    private fun Set<SettingsGroup>.toggle(group: SettingsGroup) = if (group in this) this - group else this + group

    companion object {
        const val THUMBNAIL_DEBOUNCE_MILLIS = 250L
    }
}
