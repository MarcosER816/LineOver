package dev.mark.lineover.core.io.projectfile

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Crash-safe file replacement: the content goes to `<name>.tmp`, is flushed to the disk
 * (fsync), the previous file becomes `<name>.bak` and the temp file is renamed into place.
 * A crash leaves either the old file, or a complete temp file next to the backup, or an
 * incomplete temp file: [recover] sorts that out on the next start.
 */
object AtomicFiles {
    fun temp(target: File) = File(target.parentFile, target.name + ".tmp")

    fun backup(target: File) = File(target.parentFile, target.name + ".bak")

    fun write(
        target: File,
        block: (OutputStream) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val temp = temp(target)
        FileOutputStream(temp).use { out ->
            block(out)
            out.flush()
            out.fd.sync()
        }
        val backup = backup(target)
        if (target.exists()) {
            backup.delete()
            if (!target.renameTo(backup)) throw IOException("cannot keep the previous ${target.name}")
        }
        if (!temp.renameTo(target)) throw IOException("cannot move ${temp.name} into place")
    }

    /**
     * After a crash: [target] wins if it is [valid]; else a complete temp file (the crash came
     * between the two renames) is moved into place; else the backup is restored. Incomplete
     * temp files are deleted. Returns the file to read, or null when there is none.
     */
    fun recover(
        target: File,
        valid: (File) -> Boolean,
    ): File? {
        val temp = temp(target)
        val backup = backup(target)
        val source =
            when {
                target.exists() && valid(target) -> target
                temp.exists() && valid(temp) -> temp
                backup.exists() && valid(backup) -> backup
                else -> null
            }
        if (source != null && source != target) promote(source, target)
        if (temp.exists()) temp.delete()
        return if (source != null) target else null
    }

    private fun promote(
        from: File,
        target: File,
    ) {
        if (target.exists() && !target.delete()) throw IOException("cannot replace ${target.name}")
        if (!from.renameTo(target)) throw IOException("cannot move ${from.name} into place")
    }
}
