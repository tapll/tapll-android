package dev.useunion.internal

import android.util.Log
import dev.useunion.LogLevel

internal class SdkLogger(private val level: LogLevel, private val handler: ((LogLevel, String) -> Unit)?) {
    fun log(l: LogLevel, message: () -> String) {
        if (l == LogLevel.None || l.ordinal < level.ordinal) return
        val text = message()
        handler?.let {
            runCatching { it(l, text) }
            return
        }
        // android.util.Log is a stub that throws on a plain JVM; the pipeline tests run there with
        // LogLevel.None, but a stray log must never take the host (or a test) down.
        runCatching {
            when (l) {
                LogLevel.Debug -> Log.d(TAG, text)
                LogLevel.Info -> Log.i(TAG, text)
                LogLevel.Warning -> Log.w(TAG, text)
                LogLevel.Error -> Log.e(TAG, text)
                LogLevel.None -> Unit
            }
        }
    }

    companion object {
        const val TAG = "Union"
        val silent = SdkLogger(LogLevel.None, null)
    }
}
