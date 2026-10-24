package dev.useunion.internal.crash

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import java.io.File

/** The device at the moment of a report. Every field optional, and absent stays absent: never zero. */
internal fun interface DeviceStateSampler {
    fun sample(): Map<String, Any>
}

internal class AndroidDeviceStateSampler(private val context: Context, private val foreground: () -> Boolean) : DeviceStateSampler {
    private val rooted: Boolean by lazy { RootHeuristic.looksRooted() }

    override fun sample(): Map<String, Any> {
        val m = LinkedHashMap<String, Any>()
        runCatching {
            m["orientation"] = when (context.resources.configuration.orientation) {
                Configuration.ORIENTATION_PORTRAIT -> "portrait"
                Configuration.ORIENTATION_LANDSCAPE -> "landscape"
                else -> "unknown"
            }
        }
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            if (info.totalMem > 0) {
                m["free_memory_bytes"] = info.availMem.coerceAtLeast(0)
                m["total_memory_bytes"] = info.totalMem
            }
        }
        runCatching {
            val free = StatFs(context.filesDir.path).availableBytes
            if (free >= 0) m["free_disk_bytes"] = free
        }
        runCatching {
            val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (battery != null) {
                val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) m["battery_level"] = (level.toDouble() / scale).coerceIn(0.0, 1.0)
                m["battery_state"] = when (battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                    BatteryManager.BATTERY_STATUS_FULL -> "full"
                    BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "unplugged"
                    else -> "unknown"
                }
            }
        }
        // On Android the field means "rooted": the OS's own sandbox is not in force. A heuristic, not a verdict.
        m["jailbroken"] = rooted
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            m["low_power_mode"] = pm.isPowerSaveMode
        }
        m["in_foreground"] = foreground()
        runCatching {
            val uptime = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
            if (uptime >= 0) m["uptime_ms"] = uptime
        }
        return m
    }
}

/**
 * Looks for the usual traces of a rooted device. A heuristic with known false positives (custom ROMs,
 * emulator images) and known false negatives (Magisk with hiding), reported as a sample, not a verdict.
 */
internal object RootHeuristic {
    private val suPaths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/su/bin/su",
        "/system/bin/.ext/.su", "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
    )

    fun looksRooted(tags: String? = Build.TAGS, exists: (String) -> Boolean = { File(it).exists() }): Boolean =
        (tags?.contains("test-keys") == true) || suPaths.any { runCatching { exists(it) }.getOrDefault(false) }
}
