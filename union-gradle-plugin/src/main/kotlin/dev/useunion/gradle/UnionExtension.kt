package dev.useunion.gradle

import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import javax.inject.Inject

/**
 * ```kotlin
 * union {
 *     endpoint = "https://in.useunion.dev/v1/mappings"             // default
 *     serverKey = providers.environmentVariable("UNION_SERVER_KEY") // default
 * }
 * ```
 *
 * The server key (`sk_...`) belongs to CI, never to the app: it is read at build time and is not an
 * input of any task, so it can not end up in a build cache entry, a report or the APK.
 */
abstract class UnionExtension @Inject constructor(providers: ProviderFactory) {
    /** `POST` target for the gzipped `mapping.txt`. */
    abstract val endpoint: Property<String>

    /** The project's server key. A missing key skips the upload with a warning; it never fails the build. */
    abstract val serverKey: Property<String>

    /** Switch the whole plugin off for a build (`-Punion.enabled=false` in CI, say). */
    abstract val enabled: Property<Boolean>

    init {
        endpoint.convention(DEFAULT_ENDPOINT)
        serverKey.convention(providers.environmentVariable("UNION_SERVER_KEY") as Provider<String>)
        enabled.convention(true)
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://in.useunion.dev/v1/mappings"
    }
}
