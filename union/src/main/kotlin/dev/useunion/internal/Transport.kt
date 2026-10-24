package dev.useunion.internal

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class TransportResponse(val status: Int, val body: ByteArray, val retryAfterSeconds: Double? = null) {
    val bodyText: String get() = body.toString(Charsets.UTF_8)
}

/** The seam tests replace: no network anywhere in the test suite. */
internal interface Transport {
    /** Throws on a network failure; any HTTP status (including 5xx) is a normal return. */
    suspend fun send(body: ByteArray, writeKey: String): TransportResponse
}

/** `/v1/batch` -> `/v1/crash`, so a staging ingest gets crashes there too without a second URL. */
internal fun crashEndpointFrom(batchEndpoint: String): String {
    val trimmed = batchEndpoint.trimEnd('/')
    val cut = trimmed.lastIndexOf('/')
    return if (cut < 0) "$trimmed/crash" else trimmed.substring(0, cut) + "/crash"
}

/**
 * `HttpURLConnection`, not OkHttp: one POST with a fixed header set does not justify a dependency in
 * every app. [gzip] is the crash route's requirement (`content-encoding: gzip`, body compressed
 * before it is measured); the event route sends plain JSON like iOS does.
 */
internal class HttpTransport(
    private val endpoint: String,
    private val userAgent: String,
    private val gzip: Boolean,
    private val timeoutMs: Int = if (gzip) 20_000 else 15_000,
) : Transport {
    override suspend fun send(body: ByteArray, writeKey: String): TransportResponse = withContext(Dispatchers.IO) {
        val payload = if (gzip) gzipBytes(body) else body
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json")
            if (gzip) conn.setRequestProperty("Content-Encoding", "gzip")
            conn.setRequestProperty("x-union-key", writeKey)
            conn.setRequestProperty("User-Agent", userAgent)
            // Fixed length, never chunked: the crash route reads a length-bounded body.
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val data = stream?.use { it.readBytes() } ?: ByteArray(0)
            TransportResponse(status, data, conn.getHeaderField("Retry-After")?.trim()?.toDoubleOrNull())
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun gzipBytes(body: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(body) }
            return out.toByteArray()
        }
    }
}
