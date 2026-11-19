package dev.mark.lineover.core.io.lens

import dev.mark.lineover.core.model.LensDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Where the lens profile database stands. */
sealed interface LensProfileState {
    data object Missing : LensProfileState

    data class Downloading(
        val bytes: Long,
        val total: Long,
    ) : LensProfileState

    data class Installed(
        val bytes: Long,
    ) : LensProfileState

    /** Shipped inside the app (nothing to download or delete). */
    data class Included(
        val bytes: Long,
    ) : LensProfileState

    /** The database can be used ([Installed] or [Included]). */
    val ready: Boolean get() = this is Installed || this is Included

    data class Failed(
        val message: String,
    ) : LensProfileState
}

/**
 * The Lensfun lens database on the device (Stage 9). With [bundled] (the app's assets, since
 * 0.11.2) the pinned files ship inside the app: the state is [LensProfileState.Included] and
 * each file is checked against its SHA-256 of [LensfunManifest] when the database is first
 * read. Without it the files download on request (Settings → Lens profiles), are checked
 * before anything is installed, install atomically (a temporary folder renamed into place)
 * and Delete removes them. Blocking work runs on [io].
 */
class LensProfileStore(
    private val dir: File,
    private val files: List<LensfunFile> = LensfunManifest.FILES,
    private val baseUrl: String = LensfunManifest.BASE_URL,
    private val fetch: (String) -> InputStream = ::httpGet,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Opens a bundled database file by name, or null when the app ships none. */
    private val bundled: ((String) -> InputStream)? = null,
) {
    private val total = files.sumOf { it.size }
    private val stateFlow = MutableStateFlow(initialState())
    val state: StateFlow<LensProfileState> = stateFlow.asStateFlow()

    private val mutex = Mutex()
    private var cached: LensDatabase? = null

    val sizeBytes: Long get() = total

    private fun initialState(): LensProfileState =
        when {
            bundled != null -> LensProfileState.Included(total)
            isInstalled() -> LensProfileState.Installed(total)
            else -> LensProfileState.Missing
        }

    private fun isInstalled(): Boolean = File(dir, MARKER).isFile && files.all { File(dir, it.name).length() == it.size }

    /** Downloads and verifies every file, then installs them at once. False on any failure (nothing changes). */
    suspend fun download(): Boolean =
        if (bundled != null) {
            true
        } else {
            downloadFiles()
        }

    private suspend fun downloadFiles(): Boolean =
        mutex.withLock {
            withContext(io) {
                val temp = File(dir.parentFile, "${dir.name}.download")
                temp.deleteRecursively()
                temp.mkdirs()
                var done = 0L
                stateFlow.value = LensProfileState.Downloading(0, total)
                try {
                    for (file in files) {
                        currentCoroutineContext().ensureActive()
                        fetchVerified(file, File(temp, file.name)) { n ->
                            done += n
                            stateFlow.value = LensProfileState.Downloading(done, total)
                        }
                    }
                    File(temp, MARKER).writeText(baseUrl)
                    dir.deleteRecursively()
                    if (!temp.renameTo(dir)) throw IOException("could not install the lens profiles")
                    cached = null
                    stateFlow.value = LensProfileState.Installed(total)
                    true
                } catch (e: IOException) {
                    temp.deleteRecursively()
                    stateFlow.value = LensProfileState.Failed(e.message ?: "download failed")
                    false
                } finally {
                    temp.deleteRecursively()
                    if (stateFlow.value is LensProfileState.Downloading) stateFlow.value = initialState()
                }
            }
        }

    private fun fetchVerified(
        file: LensfunFile,
        target: File,
        progress: (Int) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val size = fetch(baseUrl + file.name).use { input -> target.outputStream().use { out -> copy(input, out, file, digest, progress) } }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (size != file.size || hex != file.sha256) throw IOException("${file.name} failed its checksum")
    }

    /** Copies [input] to [out] through [digest]; never more than [file]'s size. Returns the bytes copied. */
    private fun copy(
        input: InputStream,
        out: java.io.OutputStream,
        file: LensfunFile,
        digest: MessageDigest,
        progress: (Int) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER)
        var size = 0L
        var n = input.read(buffer)
        while (n >= 0) {
            size += n
            if (size > file.size) throw IOException("${file.name} is larger than expected")
            digest.update(buffer, 0, n)
            out.write(buffer, 0, n)
            progress(n)
            n = input.read(buffer)
        }
        return size
    }

    /** Deletes the database. */
    suspend fun delete() {
        if (bundled != null) return // part of the app
        mutex.withLock {
            withContext(io) {
                dir.deleteRecursively()
                cached = null
                stateFlow.value = LensProfileState.Missing
            }
        }
    }

    /** The installed database (parsed once), or null when it is not installed. */
    suspend fun database(): LensDatabase? =
        mutex.withLock {
            cached ?: withContext(io) {
                val open = bundled
                val texts =
                    when {
                        open != null -> files.map { readVerified(it, open) ?: return@withContext null }
                        isInstalled() -> files.map { File(dir, it.name).readText() }
                        else -> return@withContext null
                    }
                LensDatabase.parse(texts).also { cached = it }
            }
        }

    /** A bundled file's text when its size and SHA-256 match the manifest, else null (reported as Failed). */
    private fun readVerified(
        file: LensfunFile,
        open: (String) -> InputStream,
    ): String? {
        val bytes = runCatching { open(file.name).use { it.readBytes() } }.getOrNull()
        val hex = bytes?.let { b -> MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } }
        if (bytes == null || bytes.size.toLong() != file.size || hex != file.sha256) {
            stateFlow.value = LensProfileState.Failed("the bundled ${file.name} is damaged")
            return null
        }
        return bytes.toString(Charsets.UTF_8)
    }

    companion object {
        private const val MARKER = "installed"
        private const val BUFFER = 64 * 1024
        private const val TIMEOUT_MILLIS = 20_000

        private fun httpGet(url: String): InputStream {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw IOException("HTTP ${connection.responseCode} for $url")
            }
            return connection.inputStream
        }
    }
}
