package dev.useunion.internal

import dev.useunion.BuildConfig

internal object SdkInfo {
    /** From `VERSION_NAME` in gradle.properties via BuildConfig. Travels on every batch as `device.sdk_version`. */
    val version: String get() = BuildConfig.SDK_VERSION
    const val CONTRACT_VERSION: Int = 1
    const val CRASH_CONTRACT_VERSION: Int = 2

    /** `union-android/<ver>`, plus ` <name>/<ver>` when a wrapper is in front of this SDK. */
    fun userAgent(wrapper: String? = null, version: String = this.version): String =
        "union-android/$version" + (wrapper?.let { " $it" } ?: "")
}
