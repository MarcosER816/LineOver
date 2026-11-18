package dev.mark.lineover.core.io.projectfile

import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.EditGraphJson
import dev.mark.lineover.core.model.EditHistory
import dev.mark.lineover.core.model.OpParams
import dev.mark.lineover.core.model.ProjectDocument
import dev.mark.lineover.core.model.Snapshot
import dev.mark.lineover.core.model.Version
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ProjectFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun document(): ProjectDocument {
        var history = EditHistory.start()
        history = history.record(EditGraph().withBackgroundOps(listOf(OpParams.Exposure(0.7f))), "Exposure +0.7", "exposure.ev")
        history =
            history.record(
                EditGraph().withBackgroundOps(listOf(OpParams.Exposure(0.7f), OpParams.HighlightsShadows(-40f, 20f))),
                "Highlights / Shadows",
                "highlights_shadows",
            )
        history = history.undo()
        val warm = EditHistory.start(EditGraph().withBackgroundOps(listOf(OpParams.WhiteBalance(5200f, 4f))))
        return ProjectDocument(
            versions = listOf(Version("v1", "Version 1", 10L, history), Version("v2", "Warm", 20L, warm)),
            activeVersion = "v1",
            snapshots = listOf(Snapshot("s1", "Before grading", 30L, history.entries[2].graph)),
        )
    }

    private fun content(source: SourceRef = SourceRef(SourceRef.REFERENCE, "content://photos/1", "IMG_1.jpg")) =
        ProjectContent(
            name = "Harbour",
            created = 1_000L,
            modified = 2_000L,
            document = document(),
            source = source,
            thumbnails = mapOf("current.jpg" to byteArrayOf(1, 2, 3), "v2.jpg" to byteArrayOf(4, 5)),
            paint = mapOf("layer-2/0_0.zst" to ByteArray(300) { (it * 7).toByte() }),
            app = "0.8.0",
        )

    private fun save(
        file: File,
        content: ProjectContent = content(),
        original: File? = null,
    ) = AtomicFiles.write(file) { out -> ProjectFile.write(out, content, original?.let { o -> { o.inputStream() } }) }

    @Test
    fun saveThenLoadGivesTheSameProject() {
        val file = tmp.newFile("a.loproj")
        val saved = content()
        save(file, saved)
        val loaded = ProjectFile.read(file)
        assertEquals(saved.document, loaded.document)
        assertEquals(saved.graph, loaded.graph)
        assertEquals(1, loaded.document.active.history.index)
        assertEquals(saved.source, loaded.source)
        assertEquals(
            listOf(saved.name, saved.created, saved.modified, saved.app),
            listOf(loaded.name, loaded.created, loaded.modified, loaded.app),
        )
        assertEquals(saved.thumbnails.keys, loaded.thumbnails.keys)
        saved.thumbnails.forEach { (k, v) -> assertArrayEquals(v, loaded.thumbnails[k]) }
        assertArrayEquals(saved.paint.values.single(), loaded.paint["layer-2/0_0.zst"])
        // Saving what was loaded writes the same project again.
        val again = tmp.newFile("b.loproj")
        save(again, loaded)
        assertEquals(loaded.document, ProjectFile.read(again).document)
    }

    @Test
    fun theZipHoldsTheDocumentedEntries() {
        val file = tmp.newFile("c.loproj")
        val original = tmp.newFile("photo.jpg").apply { writeBytes(ByteArray(5000) { it.toByte() }) }
        save(file, content(SourceRef(SourceRef.EMBEDDED, displayName = "photo.jpg")), original)
        val names = ZipFile(file).use { zip -> zip.entries().toList().map { it.name } }
        assertEquals(
            listOf(
                "graph.json",
                "document.json",
                "thumbnails/current.jpg",
                "thumbnails/v2.jpg",
                "paint/layer-2/0_0.zst",
                "original",
                "manifest.json",
            ),
            names,
        )
        val manifest = ZipFile(file).use { zip -> String(zip.getInputStream(zip.getEntry("manifest.json")).readBytes()) }
        val json = EditGraphJson.format.parseToJsonElement(manifest).jsonObject
        assertEquals(JsonPrimitive(1), json["schemaVersion"])
        assertEquals(JsonPrimitive("lineover-project"), json["format"])
        val target = File(tmp.root, "extracted/original")
        ProjectFile.read(file, extractOriginal = target)
        assertArrayEquals(original.readBytes(), target.readBytes())
    }

    @Test
    fun aDamagedOrForeignFileIsRefused() {
        val file = tmp.newFile("d.loproj")
        save(file)
        // Replace graph.json by other bytes: the manifest's SHA-256 no longer matches.
        val tampered = tmp.newFile("e.loproj")
        ZipFile(file).use { zip ->
            ZipOutputStream(tampered.outputStream()).use { out ->
                for (entry in zip.entries()) {
                    out.putNextEntry(ZipEntry(entry.name))
                    val bytes = zip.getInputStream(entry).readBytes()
                    out.write(if (entry.name == "graph.json") EditGraphJson.encode(EditGraph()).toByteArray() else bytes)
                    out.closeEntry()
                }
            }
        }
        assertRefused(tampered, "damaged")
        val text = tmp.newFile("f.loproj").apply { writeText("hello") }
        assertRefused(text, "complete")
        assertFalse(ProjectFile.isValid(text))
    }

    @Test
    fun aFileCutShortAnywhereIsRefused() {
        val file = tmp.newFile("g.loproj")
        save(file)
        val bytes = file.readBytes()
        for (length in listOf(0, 10, bytes.size / 3, bytes.size / 2, bytes.size - 30, bytes.size - 1)) {
            val cut = File(tmp.root, "cut-$length.loproj").apply { writeBytes(bytes.copyOf(length)) }
            assertFalse("cut at $length bytes", ProjectFile.isValid(cut))
        }
        assertTrue(ProjectFile.isValid(file))
    }

    @Test
    fun aFileFromANewerLineOverIsRefused() {
        val json =
            ProjectJson(JsonObject(mapOf("format" to JsonPrimitive("lineover-project"), "schemaVersion" to JsonPrimitive(2))), null, null)
        try {
            ProjectMigrations.DEFAULT.migrate(json)
            fail("a newer file must not load")
        } catch (e: ProjectFileException) {
            assertTrue(e.message!!.contains("newer LineOver"))
        }
    }

    private fun assertRefused(
        file: File,
        reason: String,
    ) {
        try {
            ProjectFile.read(file)
            fail("$file must not load")
        } catch (e: IOException) {
            assertTrue("${e.message} mentions $reason", e.message!!.contains(reason))
        }
    }

    @Test
    fun killDuringSaveKeepsThePreviousFileAndIgnoresTheTruncatedTemp() {
        val file = tmp.newFile("h.loproj")
        save(file)
        val before = file.readBytes()
        // The process dies half-way through writing the next save.
        try {
            AtomicFiles.write(file) { out ->
                val next = java.io.ByteArrayOutputStream()
                ProjectFile.write(
                    next,
                    content().let { ProjectContent(it.name, it.created, 9_999L, ProjectDocument.of(EditGraph()), it.source) },
                )
                out.write(next.toByteArray(), 0, next.size() / 2)
                throw IOException("killed")
            }
            fail("the write was killed")
        } catch (_: IOException) {
            // Expected.
        }
        assertArrayEquals(before, file.readBytes())
        assertTrue("a truncated temp file is left behind", AtomicFiles.temp(file).exists())
        val recovered = AtomicFiles.recover(file, ProjectFile::isValid)
        assertEquals(file, recovered)
        assertFalse("the truncated temp file is deleted", AtomicFiles.temp(file).exists())
        assertEquals(content().document, ProjectFile.read(file).document)
    }

    @Test
    fun aCrashBetweenTheRenamesRecoversTheCompleteTempOrTheBackup() {
        val file = File(tmp.root, "i.loproj")
        save(file)
        val newer =
            ProjectContent(
                "Harbour",
                1_000L,
                5_000L,
                ProjectDocument.of(EditGraph().withBackgroundOps(listOf(OpParams.Contrast(9f)))),
                content().source,
            )
        // Complete temp written, old file already moved to .bak, rename not done.
        AtomicFiles.temp(file).outputStream().use { ProjectFile.write(it, newer) }
        file.renameTo(AtomicFiles.backup(file))
        assertEquals(file, AtomicFiles.recover(file, ProjectFile::isValid))
        assertEquals(newer.document, ProjectFile.read(file).document)
        // Only an incomplete temp and the backup: the backup comes back.
        val other = File(tmp.root, "j.loproj")
        save(other)
        other.renameTo(AtomicFiles.backup(other))
        AtomicFiles.temp(other).writeBytes(byteArrayOf(80, 75, 3, 4, 0, 0))
        assertEquals(other, AtomicFiles.recover(other, ProjectFile::isValid))
        assertEquals(content().document, ProjectFile.read(other).document)
        // Nothing usable at all.
        val none = File(tmp.root, "k.loproj")
        AtomicFiles.temp(none).writeBytes(byteArrayOf(1, 2))
        assertNull(AtomicFiles.recover(none, ProjectFile::isValid))
    }
}
