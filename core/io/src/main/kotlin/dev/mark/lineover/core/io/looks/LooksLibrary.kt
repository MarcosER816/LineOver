package dev.mark.lineover.core.io.looks

import dev.mark.lineover.core.io.projectfile.AtomicFiles
import dev.mark.lineover.core.model.CopiedSettings
import dev.mark.lineover.core.model.CubeFormatException
import dev.mark.lineover.core.model.CubeLut
import dev.mark.lineover.core.model.CubeParser
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.EditGraphJson
import dev.mark.lineover.core.model.LooksState
import dev.mark.lineover.core.model.LutEntry
import dev.mark.lineover.core.model.OpParams
import dev.mark.lineover.core.model.Preset
import dev.mark.lineover.core.model.SettingsGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** What the editor needs of the presets, imported LUTs and copied settings (Stage 11). */
interface LooksStore {
    val state: StateFlow<LooksState>

    /** The person's own presets, newest first. */
    val userPresets: StateFlow<List<Preset>>

    /** Imports a .cube file ([name] for people). The error message when it cannot be used. Blocking I/O. */
    fun importCube(
        name: String,
        bytes: ByteArray,
    ): Result<LutEntry>

    /** The LUT's table, or null when it is not on this device. Blocking I/O. */
    fun loadLut(id: String): CubeLut?

    fun deleteLut(id: String)

    fun savePreset(
        name: String,
        ops: List<OpParams>,
    ): Preset

    fun deletePreset(id: String)

    fun setFavorite(
        presetId: String,
        favorite: Boolean,
    )

    fun copySettings(
        groups: Set<SettingsGroup>,
        ops: List<OpParams>,
    )
}

/**
 * [LooksStore] on files under [root] (the app's files dir): `luts/<sha256>.cube`,
 * `presets/<id>.json`, and `looks.json` for the rest. Writes are atomic.
 */
class LooksLibrary(
    private val root: File,
) : LooksStore {
    private val lutDir = File(root, "luts")
    private val presetDir = File(root, "presets")
    private val stateFile = File(root, "looks.json")
    private val cache = HashMap<String, CubeLut>()

    private val stateFlow = MutableStateFlow(readState())
    private val presetsFlow = MutableStateFlow(readPresets())

    override val state: StateFlow<LooksState> = stateFlow.asStateFlow()
    override val userPresets: StateFlow<List<Preset>> = presetsFlow.asStateFlow()

    @Synchronized
    override fun importCube(
        name: String,
        bytes: ByteArray,
    ): Result<LutEntry> =
        runCatching {
            if (bytes.size > MAX_CUBE_BYTES) throw CubeFormatException("the file is larger than 20 MB")
            val lut = CubeParser.parse(bytes.toString(Charsets.UTF_8))
            val id = sha256(bytes)
            stateFlow.value.luts.firstOrNull { it.id == id } ?: run {
                AtomicFiles.write(File(lutDir, "$id.cube")) { it.write(bytes) }
                val label = name.removeSuffix(".cube").removeSuffix(".CUBE").ifBlank { lut.title.ifBlank { "LUT" } }
                val entry = LutEntry(id, label, lut.size)
                cache[id] = lut
                update { it.copy(luts = listOf(entry) + it.luts) }
                entry
            }
        }

    @Synchronized
    override fun loadLut(id: String): CubeLut? {
        cache[id]?.let { return it }
        val file = File(lutDir, "$id.cube").takeIf { it.isFile } ?: return null
        return runCatching { CubeParser.parse(file.readText()) }.getOrNull()?.also { cache[id] = it }
    }

    @Synchronized
    override fun deleteLut(id: String) {
        File(lutDir, "$id.cube").delete()
        cache.remove(id)
        update { s -> s.copy(luts = s.luts.filterNot { it.id == id }) }
    }

    @Synchronized
    override fun savePreset(
        name: String,
        ops: List<OpParams>,
    ): Preset {
        val preset =
            Preset("user.${UUID.randomUUID()}", name.trim().ifBlank { "My preset" }, USER_GROUP, EditGraph().withBackgroundOps(ops))
        AtomicFiles.write(File(presetDir, "${preset.id}.json")) { it.write(EditGraphJson.encodePreset(preset).toByteArray()) }
        presetsFlow.value = listOf(preset) + presetsFlow.value
        return preset
    }

    @Synchronized
    override fun deletePreset(id: String) {
        File(presetDir, "$id.json").delete()
        presetsFlow.value = presetsFlow.value.filterNot { it.id == id }
        update { s -> s.copy(favoritePresets = s.favoritePresets - id) }
    }

    @Synchronized
    override fun setFavorite(
        presetId: String,
        favorite: Boolean,
    ) = update { s ->
        val rest = s.favoritePresets - presetId
        s.copy(favoritePresets = if (favorite) rest + presetId else rest)
    }

    @Synchronized
    override fun copySettings(
        groups: Set<SettingsGroup>,
        ops: List<OpParams>,
    ) = update { it.copy(copied = CopiedSettings(groups.sortedBy { g -> g.ordinal }, EditGraph().withBackgroundOps(ops))) }

    private fun update(change: (LooksState) -> LooksState) {
        val next = change(stateFlow.value)
        AtomicFiles.write(stateFile) { it.write(EditGraphJson.encodeLooks(next).toByteArray()) }
        stateFlow.value = next
    }

    private fun readState(): LooksState {
        val file = AtomicFiles.recover(stateFile) { f -> runCatching { EditGraphJson.decodeLooks(f.readText()) }.isSuccess }
        return file?.let { runCatching { EditGraphJson.decodeLooks(it.readText()) }.getOrNull() } ?: LooksState()
    }

    private fun readPresets(): List<Preset> =
        presetDir
            .listFiles { f -> f.name.endsWith(".json") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { runCatching { EditGraphJson.decodePreset(it.readText()) }.getOrNull() }

    companion object {
        const val USER_GROUP = "My presets"
        const val MAX_CUBE_BYTES = 20 shl 20

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** [LooksStore] in memory: tests and screenshot previews. */
class InMemoryLooksStore(
    initial: LooksState = LooksState(),
    presets: List<Preset> = emptyList(),
) : LooksStore {
    private val luts = HashMap<String, CubeLut>()
    override val state = MutableStateFlow(initial)
    override val userPresets = MutableStateFlow(presets)

    override fun importCube(
        name: String,
        bytes: ByteArray,
    ): Result<LutEntry> =
        runCatching {
            val lut = CubeParser.parse(bytes.toString(Charsets.UTF_8))
            val entry = LutEntry(LooksLibrary.sha256(bytes), name.removeSuffix(".cube"), lut.size)
            luts[entry.id] = lut
            state.value = state.value.copy(luts = listOf(entry) + state.value.luts.filterNot { it.id == entry.id })
            entry
        }

    override fun loadLut(id: String): CubeLut? = luts[id]

    override fun deleteLut(id: String) {
        luts.remove(id)
        state.value = state.value.copy(luts = state.value.luts.filterNot { it.id == id })
    }

    override fun savePreset(
        name: String,
        ops: List<OpParams>,
    ): Preset =
        Preset("user.${userPresets.value.size + 1}", name, LooksLibrary.USER_GROUP, EditGraph().withBackgroundOps(ops)).also {
            userPresets.value = listOf(it) + userPresets.value
        }

    override fun deletePreset(id: String) {
        userPresets.value = userPresets.value.filterNot { it.id == id }
    }

    override fun setFavorite(
        presetId: String,
        favorite: Boolean,
    ) {
        val rest = state.value.favoritePresets - presetId
        state.value = state.value.copy(favoritePresets = if (favorite) rest + presetId else rest)
    }

    override fun copySettings(
        groups: Set<SettingsGroup>,
        ops: List<OpParams>,
    ) {
        state.value = state.value.copy(copied = CopiedSettings(groups.sortedBy { it.ordinal }, EditGraph().withBackgroundOps(ops)))
    }
}
