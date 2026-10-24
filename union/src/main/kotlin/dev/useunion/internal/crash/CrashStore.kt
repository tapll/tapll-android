package dev.useunion.internal.crash

import dev.useunion.internal.AtomicFile
import dev.useunion.internal.Json
import java.io.File

/**
 * Reports on disk, one JSON file each: `noBackupFilesDir/Union/<keyHash>/crashes/<crash_id>.report.json`.
 *
 * Unlike iOS there is no binary record and sidecar: a JVM uncaught-exception handler runs ordinary
 * code in a healthy-enough thread, so the whole report is built and written (temp file + rename, so a
 * second crash mid-write never leaves half a file) in one step. Everything on disk is therefore
 * complete, and the next launch only has to read and upload it.
 */
internal class CrashStore(val directory: File) {
    init {
        directory.mkdirs()
    }

    fun reportFile(id: String) = File(directory, "$id.report.json")

    fun write(id: String, report: Map<String, Any?>) {
        AtomicFile.write(reportFile(id), Json.writeBytes(report))
    }

    /** Reports waiting to be sent, oldest first. */
    fun pending(): List<File> =
        (directory.listFiles { f -> f.isFile && f.name.endsWith(".report.json") } ?: emptyArray())
            .sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))

    fun read(file: File): Map<String, Any?>? = runCatching { Json.parseObjectOrNull(file.readText()) }.getOrNull()

    fun discard(file: File) {
        runCatching { file.delete() }
    }

    /** Oldest fall out first: a launch-crash loop offline must not fill the disk. */
    fun trim(max: Int) {
        val files = pending()
        if (files.size <= max) return
        files.take(files.size - max).forEach(::discard)
    }

    fun wipe() {
        directory.listFiles()?.forEach { runCatching { it.delete() } }
        // Leftover temp files from an interrupted write are crash data too.
    }
}
