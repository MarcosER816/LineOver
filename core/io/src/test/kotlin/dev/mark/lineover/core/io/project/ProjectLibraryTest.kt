package dev.mark.lineover.core.io.project

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.EditHistory
import dev.mark.lineover.core.model.OpParams
import dev.mark.lineover.core.model.ProjectDocument
import dev.mark.lineover.core.model.Snapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Import, thumbnail, rename, duplicate, delete/undo and purge against a real (in-memory) database. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectLibraryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db =
        Room.inMemoryDatabaseBuilder(context, ProjectDatabase::class.java).allowMainThreadQueries().build()
    private var now = 1_000L

    private fun library(dispatcher: TestDispatcher) =
        AndroidProjectLibrary(
            resolver = context.contentResolver,
            repository = RoomProjectRepository(db.projects()),
            storage = ProjectStorage(temp.newFolder("files")),
            clock = { now },
            io = dispatcher,
        )

    @After
    fun close() = db.close()

    /** A 2000x1000 PNG on disk, imported by file URI (no persistable grant: the copy path). */
    private fun photo(name: String = "IMG_0001.png"): Uri {
        val file = File(temp.root, name)
        val bitmap = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 120, 40)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    @Test
    fun importCopiesTheOriginalAndWritesA512Thumbnail() =
        runTest {
            val library = library(StandardTestDispatcher(testScheduler))
            val source = photo()

            val result = library.importPicked(listOf(source)) as ImportResult.Imported

            assertEquals(0, result.failed)
            val project = library.observe(result.ids.single()).first()!!
            assertEquals("IMG_0001", project.name)
            assertEquals(1_000L, project.opened)
            val stored = File(Uri.parse(project.sourceUri).path!!)
            assertTrue(stored.path.endsWith("projects/${project.id}/original"))
            assertTrue(File(source.path!!).readBytes().contentEquals(stored.readBytes()))
            assertNotNull(project.thumbPath)
            val thumb = BitmapFactory.decodeFile(project.thumbPath)
            assertEquals(512, thumb.width)
            assertEquals(256, thumb.height)
        }

    @Test
    fun previewIsDecodedAtMost2048PxAndKnowsTheOriginalSize() =
        runTest {
            val library = library(StandardTestDispatcher(testScheduler))
            val file = File(temp.root, "big.png")
            Bitmap.createBitmap(6000, 4000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(10, 200, 90)) }.also { big ->
                file.outputStream().use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
                big.recycle()
            }
            val id = (library.importPicked(listOf(Uri.fromFile(file))) as ImportResult.Imported).ids.single()
            val project = library.observe(id).first()!!

            val preview = library.decodePreview(project)!!

            assertEquals(2048, preview.bitmap.width)
            assertEquals(1365, preview.bitmap.height)
            assertEquals(6000, preview.originalWidth)
            assertEquals(4000, preview.originalHeight)
            assertEquals(Bitmap.Config.ARGB_8888, preview.bitmap.config)
        }

    @Test
    fun previewOfAMissingOriginalIsNull() =
        runTest {
            val library = library(StandardTestDispatcher(testScheduler))
            val project =
                dev.mark.lineover.core.model
                    .Project(9, "gone", Uri.fromFile(File(temp.root, "gone.jpg")).toString(), null, 0, 0)

            assertNull(library.decodePreview(project))
        }

    @Test
    fun unreadableSourcesAreCountedAndLeaveNoRow() =
        runTest {
            val library = library(StandardTestDispatcher(testScheduler))

            val result = library.importPicked(listOf(photo(), Uri.fromFile(File(temp.root, "missing.jpg")))) as ImportResult.Imported

            assertEquals(1, result.ids.size)
            assertEquals(1, result.failed)
            assertEquals(1, library.observeProjects().first().size)
        }

    @Test
    fun aBrokenProjectFileIsRefusedWithAReason() =
        runTest {
            val library = library(StandardTestDispatcher(testScheduler))
            val file = File(temp.root, "trip.loproj").apply { writeText("{}") }

            val result = library.importDocument(Uri.fromFile(file)) as ImportResult.ProjectFileFailed
            assertEquals("Not a complete LineOver project file", result.reason)
            assertTrue(library.observeProjects().first().isEmpty())
        }

    private fun edited(): ProjectDocument {
        var history = EditHistory.start()
        history = history.record(EditGraph().withBackgroundOps(listOf(OpParams.Exposure(0.7f))), "Exposure +0.7", "exposure.ev")
        history =
            history.record(
                EditGraph().withBackgroundOps(listOf(OpParams.Exposure(0.7f), OpParams.Contrast(20f))),
                "Contrast +20",
                "contrast.amount",
            )
        return ProjectDocument.of(EditGraph()).withHistory(history).copy(
            snapshots = listOf(Snapshot("s1", "Bright", 5L, history.entries[1].graph)),
        )
    }

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        return java.io
            .ByteArrayOutputStream()
            .also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            .toByteArray()
    }

    @Test
    fun aSavedEditLoadsBackAndUpdatesTheHomeThumbnail() =
        runTest {
            val library = library(StandardTestDispatcher(testScheduler))
            val id = (library.importPicked(listOf(photo())) as ImportResult.Imported).ids.single()
            val project = library.observe(id).first()!!
            assertEquals(DocumentLoad.Loaded(ProjectDocument.of(EditGraph(), 1_000L), fresh = true), library.loadDocument(project))

            now = 7_000L
            library.saveDocument(project, edited(), mapOf("current.jpg" to jpeg()))

            val loaded = library.loadDocument(project) as DocumentLoad.Loaded
            assertFalse(loaded.fresh)
            assertEquals(edited(), loaded.document)
            val after = library.observe(id).first()!!
            assertEquals(7_000L, after.modified)
            assertTrue(after.thumbPath!!.endsWith("thumb-7000.jpg"))
            assertEquals(64, BitmapFactory.decodeFile(after.thumbPath).width)
            // The next save replaces the edited thumbnail (Home caches by path).
            now = 8_000L
            library.saveDocument(project, edited(), mapOf("current.jpg" to jpeg()))
            assertFalse(File(after.thumbPath!!).exists())
        }

    @Test
    fun anExportedProjectFileImportsAsANewProjectWithItsOriginalAndEdit() =
        runTest {
            val library = library(StandardTestDispatcher(testScheduler))
            val source = photo()
            val id = (library.importPicked(listOf(source)) as ImportResult.Imported).ids.single()
            val project = library.observe(id).first()!!
            library.saveDocument(project, edited())
            val shared = File(temp.root, "shared.loproj").apply { createNewFile() }

            assertTrue(library.exportProjectFile(id, Uri.fromFile(shared)))
            val result = library.importDocument(Uri.fromFile(shared)) as ImportResult.Imported

            val copy = library.observe(result.ids.single()).first()!!
            assertNotEquals(id, copy.id)
            assertEquals("IMG_0001", copy.name)
            assertTrue(File(source.path!!).readBytes().contentEquals(File(Uri.parse(copy.sourceUri).path!!).readBytes()))
            assertEquals(edited(), (library.loadDocument(copy) as DocumentLoad.Loaded).document)
            assertNotNull(copy.thumbPath)
        }

    @Test
    fun anInterruptedEditingSessionIsRecoveredOnTheNextLaunch() =
        runTest {
            val library = library(StandardTestDispatcher(testScheduler))
            val id = (library.importPicked(listOf(photo())) as ImportResult.Imported).ids.single()
            val project = library.observe(id).first()!!
            library.beginEditing(id)
            library.saveDocument(project, edited())
            // Killed during the next save: a truncated temp file and the marker stay behind.
            val file = File(temp.root, "files/projects/$id/project.loproj")
            File(file.path + ".tmp").writeBytes(file.readBytes().copyOf(100))

            assertEquals(listOf(id), library.recoverInterrupted().map { it.id })
            assertFalse(File(file.path + ".tmp").exists())
            assertEquals(edited(), (library.loadDocument(project) as DocumentLoad.Loaded).document)
            assertTrue("nothing left to recover", library.recoverInterrupted().isEmpty())
            // A session that ends normally is not "interrupted".
            library.beginEditing(id)
            library.endEditing(id)
            assertTrue(library.recoverInterrupted().isEmpty())
        }

    @Test
    fun projectsSortByModifiedAndRecentByOpened() =
        runTest {
            val library = library(StandardTestDispatcher(testScheduler))
            val a = (library.importPicked(listOf(photo("a.png"))) as ImportResult.Imported).ids.single()
            now = 2_000L
            val b = (library.importPicked(listOf(photo("b.png"))) as ImportResult.Imported).ids.single()
            now = 3_000L
            library.markOpened(a)

            assertEquals(listOf(b, a), library.observeProjects().first().map { it.id })
            assertEquals(listOf(a, b), library.observeRecent().first().map { it.id })
        }

    @Test
    fun renameCleansTheName() =
        runTest {
            val library = library(StandardTestDispatcher(testScheduler))
            val id = (library.importPicked(listOf(photo())) as ImportResult.Imported).ids.single()

            library.rename(id, "  Golden   hour ")

            assertEquals("Golden hour", library.observe(id).first()!!.name)
        }

    @Test
    fun duplicateGetsItsOwnCopiedOriginalAndThumbnail() =
        runTest {
            val library = library(StandardTestDispatcher(testScheduler))
            val id = (library.importPicked(listOf(photo())) as ImportResult.Imported).ids.single()

            val copyId = library.duplicate(id)!!

            val original = library.observe(id).first()!!
            val copy = library.observe(copyId).first()!!
            assertEquals("IMG_0001 copy", copy.name)
            assertNotEquals(original.sourceUri, copy.sourceUri)
            assertTrue(File(Uri.parse(copy.sourceUri).path!!).isFile)
            assertNotEquals(original.thumbPath, copy.thumbPath)
            assertTrue(File(copy.thumbPath!!).isFile)
        }

    @Test
    fun deleteCanBeUndoneAndPurgeRemovesTheFiles() =
        runTest {
            val library = library(StandardTestDispatcher(testScheduler))
            val id = (library.importPicked(listOf(photo())) as ImportResult.Imported).ids.single()
            val dir = File(Uri.parse(library.observe(id).first()!!.sourceUri).path!!).parentFile!!

            val deleted = library.delete(id)!!
            assertNull(library.observe(id).first())
            assertTrue("files stay until purge, so undo works", dir.isDirectory)

            library.restore(deleted)
            assertEquals(deleted, library.observe(id).first())

            library.delete(id)
            library.purge(deleted)
            assertFalse(dir.exists())
        }
}
