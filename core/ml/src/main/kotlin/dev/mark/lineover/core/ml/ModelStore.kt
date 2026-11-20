package dev.mark.lineover.core.ml

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * The downloaded models (Stage 16, rule D3): nothing ships in the APK. [download] fetches a model
 * into [dir] (the app's no-backup files) as `<file>.part`, resuming with an HTTP Range request
 * after an interruption, checks its SHA-256 and only then renames it into place, so a model on
 * disk is always a verified one. [delete] removes it (Settings → AI models).
 */
class ModelStore(
    private val dir: File,
    private val transport: Transport,
    private val network: NetworkStatus,
    /** Download on Wi-Fi only (Settings; default on). */
    private val wifiOnly: () -> Boolean,
    val specs: List<ModelSpec> = ModelCatalog.all,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    private val mutex = Mutex()
    private val mutableStates = MutableStateFlow(specs.associate { it.id to initialState(it) })

    /** Every model's state, by id. */
    val states: StateFlow<Map<String, ModelState>> = mutableStates.asStateFlow()

    fun file(spec: ModelSpec): File = File(dir, spec.fileName)

    private fun part(spec: ModelSpec): File = File(dir, spec.fileName + PART)

    private fun initialState(spec: ModelSpec): ModelState =
        if (file(spec).length() ==
            spec.fileSize
        ) {
            ModelState.Ready
        } else {
            ModelState.Missing
        }

    fun state(spec: ModelSpec): ModelState = states.value[spec.id] ?: ModelState.Missing

    fun isReady(spec: ModelSpec): Boolean = state(spec) == ModelState.Ready && file(spec).isFile

    /** Bytes of [spec] already on the device (a partial download counts). */
    fun downloadedBytes(spec: ModelSpec): Long = if (file(spec).isFile) file(spec).length() else part(spec).length()

    private fun set(
        spec: ModelSpec,
        state: ModelState,
    ) = mutableStates.update { it + (spec.id to state) }

    /**
     * Downloads [spec] unless it is ready: refuses offline (nothing requested) and on mobile data
     * while Wi-Fi only is on, unless [allowMetered]. Interrupted transfers retry up to
     * [MAX_ATTEMPTS] times, each resuming where the last stopped. The final state is returned.
     */
    suspend fun download(
        spec: ModelSpec,
        allowMetered: Boolean = false,
    ): ModelState =
        mutex.withLock {
            withContext(io) {
                if (isReady(spec)) return@withContext ModelState.Ready
                val refusal =
                    when {
                        !network.isOnline() -> ModelError.Offline
                        wifiOnly() && !allowMetered && !network.isUnmetered() -> ModelError.NeedsWifi
                        else -> null
                    }
                val result = if (refusal != null) ModelState.Failed(refusal) else fetch(spec)
                set(spec, result)
                result
            }
        }

    private suspend fun fetch(spec: ModelSpec): ModelState {
        if (!dir.isDirectory && !dir.mkdirs()) return ModelState.Failed(ModelError.Storage)
        val part = part(spec)
        val error = transferWithRetries(spec, part)
        return when {
            error != null -> {
                ModelState.Failed(error)
            }

            !verify(spec, part) -> {
                ModelState.Failed(ModelError.Integrity)
            }

            spec.archiveEntry != null -> {
                unpack(spec, part)
            }

            else -> {
                val target = file(spec)
                target.delete()
                if (part.renameTo(target)) ModelState.Ready else ModelState.Failed(ModelError.Storage)
            }
        }
    }

    /** Copies the zip entry [name] of [archive] to [to]; false when there is none. */
    private fun extract(
        archive: File,
        name: String,
        to: File,
    ): Boolean =
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            val found = generateSequence { zip.nextEntry }.firstOrNull { it.name == name } != null
            if (found) FileOutputStream(to).use { out -> zip.copyTo(out, BUFFER) }
            found
        }

    /** Stage 17: the verified zip [archive]'s entry, itself verified, into place; the zip is deleted. */
    private fun unpack(
        spec: ModelSpec,
        archive: File,
    ): ModelState {
        val entry = File(dir, spec.fileName + ENTRY)
        val found =
            try {
                extract(archive, spec.archiveEntry.orEmpty(), entry)
            } catch (e: IOException) {
                lastFailure = e
                false
            }
        archive.delete()
        val ok = found && entry.length() == spec.entrySize && Sha256.matches(entry, spec.entrySha256)
        if (!ok) {
            entry.delete()
            return ModelState.Failed(if (dir.usableSpace < spec.entrySize) ModelError.Storage else ModelError.Integrity)
        }
        val target = file(spec)
        target.delete()
        return if (entry.renameTo(target)) ModelState.Ready else ModelState.Failed(ModelError.Storage)
    }

    private fun verify(
        spec: ModelSpec,
        part: File,
    ): Boolean {
        set(spec, ModelState.Verifying)
        val ok = Sha256.matches(part, spec.sha256)
        if (!ok) part.delete()
        return ok
    }

    /** Transfers until the whole file is in [part]: null, or why it stopped. */
    private suspend fun transferWithRetries(
        spec: ModelSpec,
        part: File,
    ): ModelError? {
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            val error =
                try {
                    transfer(spec, part)
                } catch (e: IOException) {
                    // Kept as a state, not rethrown: the part stays on disk and the next try resumes.
                    lastFailure = e
                    if (dir.usableSpace < spec.sizeBytes - part.length()) ModelError.Storage else ModelError.Network
                }
            attempt++
            if (error != ModelError.Network || attempt >= MAX_ATTEMPTS) return error
            delay(retryDelayMs * attempt)
        }
    }

    /** Why the last transfer failed (for logs and tests). */
    @Volatile var lastFailure: IOException? = null
        private set

    /** One request: null when the whole file is in [part]. */
    private suspend fun transfer(
        spec: ModelSpec,
        part: File,
    ): ModelError? {
        if (part.length() > spec.sizeBytes) part.delete()
        val have = part.length()
        if (have == spec.sizeBytes) return null
        return transport.open(spec.downloadUrl, have).use { response ->
            val body = response.body
            when {
                response.code == HTTP_RANGE_NOT_SATISFIABLE -> {
                    part.delete() // the part is not a prefix of the file: start again
                    throw IOException("range not satisfiable")
                }

                body == null || (response.code != HTTP_OK && response.code != HTTP_PARTIAL) -> {
                    ModelError.Network
                }

                else -> {
                    val bytes = copy(spec, body, part, if (response.code == HTTP_PARTIAL) have else 0L)
                    if (bytes < spec.sizeBytes) throw IOException("connection closed at $bytes of ${spec.sizeBytes} bytes")
                    null
                }
            }
        }
    }

    /** Writes [body] to [part] from byte [from] (0 truncates), reporting progress; the bytes in the part after. */
    private suspend fun copy(
        spec: ModelSpec,
        body: InputStream,
        part: File,
        from: Long,
    ): Long {
        var bytes = from
        set(spec, ModelState.Downloading(bytes, spec.sizeBytes))
        FileOutputStream(part, from > 0L).use { out ->
            val buffer = ByteArray(BUFFER)
            var sinceReport = 0L
            var read = body.read(buffer)
            while (read >= 0) {
                coroutineContext.ensureActive()
                out.write(buffer, 0, read)
                bytes += read
                sinceReport += read
                if (sinceReport >= REPORT_EVERY) {
                    sinceReport = 0L
                    set(spec, ModelState.Downloading(bytes, spec.sizeBytes))
                }
                read = body.read(buffer)
            }
        }
        set(spec, ModelState.Downloading(bytes, spec.sizeBytes))
        return bytes
    }

    /** Deletes [spec] (and any partial download); the next use asks again. */
    suspend fun delete(spec: ModelSpec) =
        mutex.withLock {
            withContext(io) {
                file(spec).delete()
                part(spec).delete()
                File(dir, spec.fileName + ENTRY).delete()
                set(spec, ModelState.Missing)
            }
        }

    private companion object {
        const val PART = ".part"
        const val ENTRY = ".entry"
        const val BUFFER = 64 * 1024
        const val REPORT_EVERY = 256L * 1024L
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1500L
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
