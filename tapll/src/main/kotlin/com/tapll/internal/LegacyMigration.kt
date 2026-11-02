package com.tapll.internal

import com.tapll.LogLevel
import java.io.File

/**
 * One-time move of the state a pre-0.2.0 (Union) SDK left in `noBackupFilesDir`: `Union` + key hash directory
 * (`identity.json` with the install id, `state.json`, `queue.ndjson`, the crash reports under `crashes`)
 * becomes the same layout under `Tapll`.
 *
 * Merge rule, per file: only the old one exists -> it is moved; both exist -> the new one wins and the
 * old one is deleted; neither -> nothing happens. Whatever is left of the old tree is deleted. Idempotent,
 * and it never throws: a failed move only means that file starts fresh.
 */
internal object LegacyMigration {
    const val OLD_DIR = "Union"
    const val NEW_DIR = "Tapll"

    /** Asset written by the pre-0.2.0 Gradle plugin; still read when the new one is absent. */
    const val OLD_BUILD_ID_ASSET = "union-build-id.txt"

    fun run(noBackupFilesDir: File, logger: SdkLogger = SdkLogger.silent) {
        runCatching {
            val old = File(noBackupFilesDir, OLD_DIR)
            if (!old.exists()) return
            merge(old, File(noBackupFilesDir, NEW_DIR))
            old.deleteRecursively()
            logger.log(LogLevel.Info) { "migrated state from $OLD_DIR to $NEW_DIR" }
        }.onFailure { logger.log(LogLevel.Warning) { "state migration failed: ${it.message}" } }
    }

    private fun merge(from: File, to: File) {
        if (from.isDirectory) {
            if (to.exists() && !to.isDirectory) return // new wins
            to.mkdirs()
            from.listFiles()?.forEach { merge(it, File(to, it.name)) }
            return
        }
        if (to.exists()) {
            from.delete()
            return
        }
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            runCatching { from.copyTo(to); from.delete() }
        }
    }
}
