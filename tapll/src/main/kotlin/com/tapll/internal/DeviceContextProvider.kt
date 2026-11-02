package com.tapll.internal

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import com.tapll.Environment
import java.util.Locale
import java.util.TimeZone

internal object DeviceContextProvider {
    fun current(context: Context, wrapper: String?): DeviceContext {
        val pm = context.packageManager
        val info: PackageInfo? = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        val build = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else @Suppress("DEPRECATION") it.versionCode.toString() }
        return DeviceContext(
            appVersion = (info?.versionName ?: "0").take(32),
            appBuild = (build ?: "0").take(32),
            sdkVersion = SdkInfo.version.take(32),
            sdkWrapper = wrapper,
            osVersion = (Build.VERSION.RELEASE ?: "0").take(32),
            deviceModel = deviceModel(Build.MANUFACTURER, Build.MODEL),
            locale = Locale.getDefault().toLanguageTag().take(16),
            timezone = TimeZone.getDefault().id.take(64),
        )
    }

    /**
     * `"<Manufacturer capitalised> <MODEL>"` (`"Google Pixel 8"`, `"Samsung SM-S918B"`), at most 64
     * characters. Many devices already put the manufacturer in `MODEL` (`"HTC One"`), and repeating it
     * would make the same phone two values in the panel.
     */
    fun deviceModel(manufacturer: String?, model: String?): String {
        val make = manufacturer.orEmpty().trim()
        val name = model.orEmpty().trim()
        val capitalised = make.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        val joined = when {
            name.isEmpty() -> capitalised
            make.isEmpty() -> name
            name.startsWith(make, ignoreCase = true) -> name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            else -> "$capitalised $name"
        }
        return joined.ifEmpty { "Android" }.take(64)
    }
}

/** development -> the app is debuggable; production otherwise. `Options.environment` overrides. */
internal object EnvironmentDetector {
    fun detect(context: Context): Environment {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return if (debuggable) Environment.Development else Environment.Production
    }
}

/** `<name>/<version>` of a Flutter or React Native wrapper, validated against the contract's 32 characters. */
internal object WrapperInfo {
    /** Cut rather than accepted truncated: a clipped version string would name a release that does not exist. */
    fun value(name: String, version: String): String? {
        fun ok(part: String) = part.isNotEmpty() && part.none { it == '/' || it.isWhitespace() || it.isISOControl() || it.code > 127 }
        if (!ok(name) || !ok(version)) return null
        val joined = "$name/$version"
        return if (joined.length <= Limits.WRAPPER_MAX) joined else null
    }
}
