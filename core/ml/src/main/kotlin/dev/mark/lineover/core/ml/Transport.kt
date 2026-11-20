package dev.mark.lineover.core.ml

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP response of a model download. */
class TransportResponse(
    /** 200 (whole file), 206 (from the requested byte), 416 (nothing after it) or an error code. */
    val code: Int,
    val body: InputStream?,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    override fun close() {
        body?.close()
        onClose()
    }
}

/** Fetches a URL from byte [from] (a Range request when > 0). */
fun interface Transport {
    @Throws(IOException::class)
    fun open(
        url: String,
        from: Long,
    ): TransportResponse
}

/** [Transport] over HttpURLConnection (HTTPS only for real models; tests use a local server). */
class HttpTransport(
    private val timeoutMs: Int = TIMEOUT_MS,
) : Transport {
    override fun open(
        url: String,
        from: Long,
    ): TransportResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (from > 0L) connection.setRequestProperty("Range", "bytes=$from-")
        val code = connection.responseCode
        val body = if (code == HttpURLConnection.HTTP_OK || code == HTTP_PARTIAL) connection.inputStream else null
        return TransportResponse(code, body) { connection.disconnect() }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
        const val HTTP_PARTIAL = 206
    }
}
