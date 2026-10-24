package dev.useunion.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/** What happened to one upload. None of these ever fails a build. */
sealed interface UploadResult {
    data object Uploaded : UploadResult
    data class Skipped(val reason: String) : UploadResult
    data class Failed(val reason: String) : UploadResult
}

object MappingUploader {
    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /**
     * Skips (never throws) when there is nothing to send or nobody to send it as. A client key
     * (`av_...`) is refused outright: it ships in every APK, so a mapping uploaded with it would be
     * writable by anyone who unzips the app.
     */
    fun skipReason(serverKey: String?, mapping: File?): String? = when {
        serverKey.isNullOrBlank() -> "UNION_SERVER_KEY is not set"
        serverKey.startsWith("av_") -> "the key is a client key (av_...); the mapping upload needs the project's server key (sk_...)"
        mapping == null || !mapping.isFile -> "no mapping.txt was produced (is minification enabled for this variant?)"
        mapping.length() == 0L -> "mapping.txt is empty"
        else -> null
    }

    fun upload(
        endpoint: String,
        serverKey: String,
        buildId: String,
        appVersion: String,
        appBuild: String,
        mapping: File,
        timeoutMs: Int = 60_000,
    ): UploadResult {
        val body = gzip(mapping.readBytes())
        val conn = try {
            URL(endpoint).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return UploadResult.Failed("bad endpoint $endpoint: ${e.message}")
        }
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain")
            conn.setRequestProperty("Content-Encoding", "gzip")
            conn.setRequestProperty("x-union-key", serverKey)
            conn.setRequestProperty("x-union-build-id", buildId)
            conn.setRequestProperty("x-union-app-version", appVersion)
            conn.setRequestProperty("x-union-app-build", appBuild)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            (if (status >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() }
            if (status in 200..299) UploadResult.Uploaded else UploadResult.Failed("the server answered HTTP $status")
        } catch (e: Exception) {
            UploadResult.Failed("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }
}
