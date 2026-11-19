package dev.mark.lineover.core.io.lens

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The lens profile database download (Stage 9): verified, atomic, deletable. */
@OptIn(ExperimentalCoroutinesApi::class)
class LensProfileStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        requireNotNull(dir)
    }

    private val fixture by lazy { File(root, "testdata/lensfun/fixture.xml").readBytes() }
    private val empty = "<lensdatabase version=\"2\"></lensdatabase>".toByteArray()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifest() =
        listOf(
            LensfunFile("mil-sony.xml", fixture.size.toLong(), sha(fixture)),
            LensfunFile("empty.xml", empty.size.toLong(), sha(empty)),
        )

    private val served = mutableMapOf<String, ByteArray>()
    private val requested = mutableListOf<String>()

    private fun store(dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        LensProfileStore(
            dir = File(temp.root, "lensfun"),
            files = manifest(),
            baseUrl = "https://example.invalid/db/",
            fetch = { url ->
                requested += url
                ByteArrayInputStream(served[url.substringAfterLast('/')] ?: throw IOException("404 $url"))
            },
            io = dispatcher,
        )

    @Test
    fun aVerifiedDownloadInstallsTheDatabase() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            served["mil-sony.xml"] = fixture
            served["empty.xml"] = empty
            val store = store(dispatcher)
            assertEquals(LensProfileState.Missing, store.state.value)
            assertNull(store.database())
            assertTrue(store.download())
            assertEquals(LensProfileState.Installed(store.sizeBytes), store.state.value)
            val db = assertNotNullAndGet(store.database())
            assertEquals(4, db.lenses.size)
            assertEquals(listOf("https://example.invalid/db/mil-sony.xml", "https://example.invalid/db/empty.xml"), requested)
            // A new store over the same folder sees it installed.
            assertTrue(store(dispatcher).state.value is LensProfileState.Installed)
            store.delete()
            assertEquals(LensProfileState.Missing, store.state.value)
            assertFalse(File(temp.root, "lensfun").exists())
        }

    @Test
    fun aTamperedFileInstallsNothing() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            served["mil-sony.xml"] = fixture.copyOf().also { it[100] = 'X'.code.toByte() }
            served["empty.xml"] = empty
            val store = store(dispatcher)
            assertFalse(store.download())
            assertTrue(store.state.value is LensProfileState.Failed)
            assertNull(store.database())
            assertFalse(File(temp.root, "lensfun").exists())
            assertFalse(File(temp.root, "lensfun.download").exists())
        }

    @Test
    fun aFailedDownloadKeepsTheInstalledDatabase() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            served["mil-sony.xml"] = fixture
            served["empty.xml"] = empty
            val store = store(dispatcher)
            assertTrue(store.download())
            served.remove("empty.xml")
            assertFalse(store.download())
            assertNotNull(store.database())
        }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    // 0.11.2: the database ships in the app's assets.

    @Test
    fun theBundledFilesAreExactlyThePinnedOnes() {
        val assets = File(root, "core/io/src/main/assets/lensfun")
        val shipped =
            assets
                .listFiles { f -> f.name.endsWith(".xml") }
                .orEmpty()
                .map { it.name }
                .sorted()
        assertEquals(LensfunManifest.FILES.map { it.name }.sorted(), shipped)
        for (file in LensfunManifest.FILES) {
            val bytes = File(assets, file.name).readBytes()
            assertEquals(file.name, file.size, bytes.size.toLong())
            assertEquals(file.name, file.sha256, sha(bytes))
        }
        assertEquals(LensfunManifest.TOTAL_BYTES, LensfunManifest.FILES.sumOf { it.size })
        assertTrue(File(assets, "NOTICE.txt").readText().contains(LensfunManifest.LICENCE))
    }

    @Test
    fun theBundledDatabaseIsIncludedAndParses() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val assets = File(root, "core/io/src/main/assets/lensfun")
            val store =
                LensProfileStore(
                    dir = File(temp.root, "lensfun"),
                    fetch = { url -> throw IOException("no network in this test: $url") },
                    io = dispatcher,
                    bundled = { name -> File(assets, name).inputStream() },
                )
            assertEquals(LensProfileState.Included(LensfunManifest.TOTAL_BYTES), store.state.value)
            assertTrue(store.download())
            store.delete()
            assertEquals(LensProfileState.Included(LensfunManifest.TOTAL_BYTES), store.state.value)
            val database = store.database()
            assertNotNull(database)
            assertTrue(requireNotNull(database).lenses.size > 1000)
        }

    @Test
    fun aDamagedBundledFileIsReportedNotUsed() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store =
                LensProfileStore(
                    dir = File(temp.root, "lensfun"),
                    files = manifest(),
                    io = dispatcher,
                    bundled = { name -> ByteArrayInputStream(if (name == "empty.xml") "<broken/>".toByteArray() else fixture) },
                )
            assertNull(store.database())
            assertTrue(store.state.value is LensProfileState.Failed)
        }
}
