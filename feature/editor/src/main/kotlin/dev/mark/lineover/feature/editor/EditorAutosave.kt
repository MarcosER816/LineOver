package dev.mark.lineover.feature.editor

import android.graphics.Bitmap
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.core.model.ProjectDocument
import dev.mark.lineover.feature.editor.canvas.EditorCanvas
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Saving the editor's project (Stage 8): [schedule] 5 s after the last change (the editor is
 * idle), [saveNow] on onStop, [finish] when the editor closes. Saves run on [saveScope] (the
 * app scope) so they complete after the screen is gone; the newest state is read when a save
 * starts, and an unchanged document is not written again.
 */
internal class EditorAutosave(
    private val projectId: Long,
    private val library: ProjectLibrary,
    private val saveScope: CoroutineScope,
    private val renderDispatcher: CoroutineDispatcher,
    private val delayMillis: Long,
    private val host: Host,
) {
    /** The editor: its newest state, and where a failed save is reported. */
    interface Host {
        fun current(): EditorState

        fun saveFailed(message: String)
    }

    /** The saved project was read (or found new): from then on edits are saved. */
    var ready = false
        private set
    private var lastSaved: ProjectDocument? = null
    private var job: Job? = null
    private val mutex = Mutex()

    /** [saved] is what is on disk (null for a project never saved). */
    suspend fun loaded(saved: ProjectDocument?) {
        lastSaved = saved
        ready = true
        library.beginEditing(projectId)
    }

    /** (Re)starts the idle timer on [scope]; [beforeSave] runs when it fires (closes an idle drag). */
    fun schedule(
        scope: CoroutineScope,
        beforeSave: () -> Unit,
    ) {
        if (!ready) return
        job?.cancel()
        job =
            scope.launch {
                delay(delayMillis)
                beforeSave()
                saveScope.launch { save() }.join()
            }
    }

    fun saveNow(): Job {
        job?.cancel()
        return saveScope.launch { save() }
    }

    /** The last save, then the editing marker goes (a clean close is not a crash), then [canvas] closes. */
    fun finish(canvas: EditorCanvas?): Job {
        job?.cancel()
        return saveScope.launch {
            try {
                if (ready) {
                    save()
                    library.endEditing(projectId)
                }
            } finally {
                canvas?.close()
            }
        }
    }

    private suspend fun save() {
        if (!ready || host.current().saveBlocked != null) return
        mutex.withLock {
            val snapshot = host.current()
            val project = snapshot.project ?: return
            val document = snapshot.documentNow()
            if (document == lastSaved) return
            try {
                library.saveDocument(project, document, thumbnails(snapshot.canvas, document))
                lastSaved = document
            } catch (e: IOException) {
                host.saveFailed("Could not save: ${e.message}")
            }
        }
    }

    /** current.jpg (the Home thumbnail) and one per version, rendered off the main thread. */
    private suspend fun thumbnails(
        canvas: EditorCanvas?,
        document: ProjectDocument,
    ): Map<String, ByteArray> {
        canvas ?: return emptyMap()
        return withContext(renderDispatcher) {
            val out = linkedMapOf<String, ByteArray>()
            canvas.thumbnail(document.graph)?.let { out[CURRENT_THUMB] = it.jpeg() }
            if (document.versions.size > 1) {
                document.versions.forEach { v -> canvas.thumbnail(v.graph)?.let { out["${v.id}.jpg"] = it.jpeg() } }
            }
            out
        }
    }

    private fun Bitmap.jpeg(): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()

    private companion object {
        const val CURRENT_THUMB = "current.jpg"
        const val JPEG_QUALITY = 88
    }
}
