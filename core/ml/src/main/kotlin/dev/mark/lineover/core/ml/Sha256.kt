package dev.mark.lineover.core.ml

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 helpers used to verify downloaded models before they are loaded. */
object Sha256 {
    private const val BUFFER_SIZE = 1 shl 16

    /** Lower-case hex digest of everything readable from [input]. Does not close it. */
    fun hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun hex(file: File): String = file.inputStream().use(::hex)

    /** True when [file] exists and its digest equals [expectedHex] (case-insensitive). */
    fun matches(
        file: File,
        expectedHex: String,
    ): Boolean = file.isFile && hex(file).equals(expectedHex, ignoreCase = true)
}
