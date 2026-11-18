package dev.mark.lineover.core.io.project

import java.io.File

/** Files of a project in app storage: projects/<id>/original (copied sources) and thumb.jpg. */
internal class ProjectStorage(
    filesDir: File,
    private val cacheDir: File? = null,
) {
    private val root = File(filesDir, "projects")

    fun dir(id: Long) = File(root, id.toString())

    fun original(id: Long) = File(dir(id), "original")

    fun thumb(id: Long) = File(dir(id), "thumb.jpg")

    /** The edited thumbnail, named per save: Home caches thumbnails by path. */
    fun editedThumb(
        id: Long,
        stamp: Long,
    ) = File(dir(id), "thumb-$stamp.jpg")

    /** The project's autosaved edit (versions, histories, snapshots). */
    fun projectFile(id: Long) = File(dir(id), "project.loproj")

    /** Where an incoming .loproj is copied before it is read. */
    fun importTemp(stamp: Long) = File(cacheDir ?: root, "import-$stamp.loproj")

    /** Present while the editor has the project open: left behind by a crash or a kill. */
    fun editingMarker(id: Long) = File(dir(id), "editing")

    /** Ids of the project directories. */
    fun ids(): List<Long> = root.listFiles()?.mapNotNull { it.name.toLongOrNull() }.orEmpty()

    /** True when [path] is inside this app's project storage (a copy we own). */
    fun owns(path: String): Boolean = File(path).canonicalPath.startsWith(root.canonicalPath + File.separator)

    fun delete(id: Long) {
        dir(id).deleteRecursively()
    }
}
