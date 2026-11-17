package dev.mark.lineover.core.io.project

import java.io.File

/** Files of a project in app storage: projects/<id>/original (copied sources) and thumb.jpg. */
internal class ProjectStorage(
    filesDir: File,
) {
    private val root = File(filesDir, "projects")

    fun dir(id: Long) = File(root, id.toString())

    fun original(id: Long) = File(dir(id), "original")

    fun thumb(id: Long) = File(dir(id), "thumb.jpg")

    /** True when [path] is inside this app's project storage (a copy we own). */
    fun owns(path: String): Boolean = File(path).canonicalPath.startsWith(root.canonicalPath + File.separator)

    fun delete(id: Long) {
        dir(id).deleteRecursively()
    }
}
