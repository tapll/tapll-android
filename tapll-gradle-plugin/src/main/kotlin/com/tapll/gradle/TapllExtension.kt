package com.tapll.gradle

import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import javax.inject.Inject

/**
 * ```kotlin
 * tapll {
 *     endpoint = "https://in.tapll.com/v1/mappings"             // default
 *     serverKey = providers.environmentVariable("TAPLL_SERVER_KEY") // default
 * }
 * ```
 *
 * The server key (`sk_...`) belongs to CI, never to the app: it is read at build time and is not an
 * input of any task, so it can not end up in a build cache entry, a report or the APK.
 */
abstract class TapllExtension @Inject constructor(providers: ProviderFactory) {
    /** `POST` target for the gzipped `mapping.txt`. */
    abstract val endpoint: Property<String>

    /** The project's server key. A missing key skips the upload with a warning; it never fails the build. */
    abstract val serverKey: Property<String>

    /** Switch the whole plugin off for a build (`-Ptapll.enabled=false` in CI, say). */
    abstract val enabled: Property<Boolean>

    init {
        endpoint.convention(DEFAULT_ENDPOINT)
        // TAPLL_SERVER_KEY wins; UNION_SERVER_KEY is the pre-0.2.0 name, still accepted.
        serverKey.convention(
            providers.environmentVariable("TAPLL_SERVER_KEY")
                .orElse(providers.environmentVariable("UNION_SERVER_KEY")) as Provider<String>,
        )
        // -Ptapll.enabled=false; the pre-0.2.0 -Punion.enabled=false is still accepted.
        enabled.convention(
            providers.gradleProperty("tapll.enabled")
                .orElse(providers.gradleProperty("union.enabled"))
                .map { it != "false" }
                .orElse(true),
        )
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://in.tapll.com/v1/mappings"
    }
}
