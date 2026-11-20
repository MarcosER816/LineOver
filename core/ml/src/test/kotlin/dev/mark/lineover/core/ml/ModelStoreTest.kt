package dev.mark.lineover.core.ml

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Collections
import kotlin.random.Random

/**
 * The model manager (Stage 16, rule D3) against a local HTTP server: a verified download, an
 * integrity failure, an interrupted download that resumes with a Range request, delete, and the
 * first use offline or on mobile data with Wi-Fi only on.
 */
class ModelStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val payload = Random(16).nextBytes(300_000)
    private lateinit var server: HttpServer
    private val ranges: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    /** Bytes to send before dropping the connection on the next request without a Range (0 = all). */
    @Volatile private var cutAfter = 0

    /** The server ignores Range headers (answers 200 with everything). */
    @Volatile private var ignoreRange = false

    /** What the server serves (a different file for the integrity test). */
    @Volatile private var served = payload

    private val network =
        object : NetworkStatus {
            var online = true
            var unmetered = true

            override fun isOnline() = online

            override fun isUnmetered() = unmetered
        }
    private var wifiOnly = true

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model.bin") { exchange -> serve(exchange) }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun serve(exchange: HttpExchange) {
        val range = exchange.requestHeaders.getFirst("Range")
        ranges += range
        val body = served
        val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
        exchange.use {
            if (from != null && !ignoreRange) {
                if (from >= body.size) {
                    it.sendResponseHeaders(416, -1)
                    return
                }
                it.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                it.sendResponseHeaders(206, (body.size - from).toLong())
                it.responseBody.write(body, from, body.size - from)
            } else {
                it.sendResponseHeaders(200, body.size.toLong())
                val cut = cutAfter
                if (cut > 0) {
                    cutAfter = 0
                    it.responseBody.write(body, 0, cut)
                    it.responseBody.flush()
                    // Drop the connection mid-transfer.
                    it.responseBody.close()
                    return
                }
                it.responseBody.write(body)
            }
        }
    }

    private fun spec(sha: String = sha256(payload)) =
        ModelSpec(
            id = "test",
            displayName = "Test model",
            downloadUrl = "http://127.0.0.1:${server.address.port}/model.bin",
            sizeBytes = payload.size.toLong(),
            license = "Apache License 2.0",
            sha256 = sha,
            fileName = "test.bin",
        )

    private fun store(spec: ModelSpec) =
        ModelStore(folder.root, HttpTransport(5_000), network, { wifiOnly }, listOf(spec), retryDelayMs = 0)

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun downloads_verifies_and_then_uses_the_file() =
        runBlocking {
            val spec = spec()
            val store = store(spec)
            assertEquals(ModelState.Missing, store.state(spec))
            assertEquals(ModelState.Ready, store.download(spec))
            assertArrayEquals(payload, store.file(spec).readBytes())
            assertFalse(java.io.File(folder.root, "test.bin.part").exists())
            // A second use requests nothing; a new store finds it ready.
            assertEquals(ModelState.Ready, store.download(spec))
            assertEquals(1, ranges.size)
            assertEquals(ModelState.Ready, store(spec).state(spec))
        }

    @Test
    fun integrity_failure_deletes_the_download() =
        runBlocking {
            served = payload.copyOf().also { it[1234] = (it[1234] + 1).toByte() } // one byte differs
            val spec = spec()
            val store = store(spec)
            assertEquals(ModelState.Failed(ModelError.Integrity), store.download(spec))
            assertFalse(store.file(spec).exists())
            assertFalse(java.io.File(folder.root, "test.bin.part").exists())
            assertEquals(0L, store.downloadedBytes(spec))
            assertTrue(ModelError.Integrity.message("Test model", "300 KB").contains("checksum"))
        }

    @Test
    fun interrupted_download_resumes_with_a_range_request() =
        runBlocking {
            cutAfter = 120_000
            val spec = spec()
            val store = store(spec)
            assertEquals(ModelState.Ready, store.download(spec))
            assertArrayEquals(payload, store.file(spec).readBytes())
            // First request whole, the retry from where the connection dropped.
            assertEquals(null, ranges[0])
            assertEquals("bytes=120000-", ranges[1])
            assertEquals(2, ranges.size)
        }

    @Test
    fun a_failed_download_keeps_its_part_and_the_next_use_resumes() =
        runBlocking {
            val spec = spec()
            // A partial file from an earlier session (the app was killed mid-download).
            java.io.File(folder.root, "test.bin.part").writeBytes(payload.copyOfRange(0, 200_000))
            val store = store(spec)
            assertEquals(200_000L, store.downloadedBytes(spec))
            assertEquals(ModelState.Ready, store.download(spec))
            assertEquals(listOf("bytes=200000-"), ranges.toList())
            assertArrayEquals(payload, store.file(spec).readBytes())
        }

    @Test
    fun a_server_without_ranges_restarts_from_the_beginning() =
        runBlocking {
            ignoreRange = true
            val spec = spec()
            java.io.File(folder.root, "test.bin.part").writeBytes(payload.copyOfRange(0, 50_000))
            val store = store(spec)
            assertEquals(ModelState.Ready, store.download(spec))
            assertArrayEquals(payload, store.file(spec).readBytes())
        }

    @Test
    fun delete_removes_the_model_and_the_next_use_downloads_again() =
        runBlocking {
            val spec = spec()
            val store = store(spec)
            store.download(spec)
            store.delete(spec)
            assertEquals(ModelState.Missing, store.state(spec))
            assertFalse(store.file(spec).exists())
            assertEquals(ModelState.Missing, store(spec).state(spec))
            assertEquals(ModelState.Ready, store.download(spec))
            assertEquals(2, ranges.size)
        }

    @Test
    fun offline_first_use_says_so_and_requests_nothing() =
        runBlocking {
            network.online = false
            val spec = spec()
            val store = store(spec)
            assertEquals(ModelState.Failed(ModelError.Offline), store.download(spec))
            assertEquals(ModelState.Failed(ModelError.Offline), store.state(spec))
            assertTrue(ranges.isEmpty())
            val message = ModelError.Offline.message(spec.displayName, sizeText(spec.sizeBytes))
            assertEquals("You're offline. Connect to the internet to download Test model (300 KB); it's needed once.", message)
            // Back online, the same use downloads.
            network.online = true
            assertEquals(ModelState.Ready, store.download(spec))
        }

    @Test
    fun wifi_only_refuses_mobile_data_unless_allowed() =
        runBlocking {
            network.unmetered = false
            val spec = spec()
            val store = store(spec)
            assertEquals(ModelState.Failed(ModelError.NeedsWifi), store.download(spec))
            assertTrue(ranges.isEmpty())
            assertEquals(ModelState.Ready, store.download(spec, allowMetered = true))
            // Wi-Fi only off: mobile data is fine.
            store.delete(spec)
            wifiOnly = false
            assertEquals(ModelState.Ready, store.download(spec))
        }

    @Test
    fun the_catalog_is_valid_and_sizes_read_well() {
        assertEquals(2, ModelCatalog.all.size)
        assertTrue(ModelCatalog.all.all { it.downloadUrl.startsWith("https://") && it.license.isNotBlank() })
        assertEquals(ModelCatalog.SKY, ModelCatalog["sky_deeplabv3_ade20k"])
        assertEquals("30.5 MB", sizeText(ModelCatalog.MAGIC_TOUCH.sizeBytes))
        assertEquals("2.3 MB", sizeText(ModelCatalog.SKY.sizeBytes))
        assertEquals("512 KB", sizeText(512_000))
    }
}
