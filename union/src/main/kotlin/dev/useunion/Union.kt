package dev.useunion

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.RestrictTo
import dev.useunion.internal.Client
import dev.useunion.internal.ClientOverrides
import dev.useunion.internal.SdkLogger
import dev.useunion.internal.Validation
import dev.useunion.internal.ValidationException
import dev.useunion.internal.WrapperInfo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Union Android SDK, public facade. Every call is thread-safe, returns immediately and never throws
 * into the host app; invalid input is logged and dropped. Configure once, as early as possible
 * (`Application.onCreate`).
 *
 * ```kotlin
 * Union.configure(this, "av_...", PrivacyMode.ProductAnalytics)
 * Union.track("workout_started", mapOf("plan" to "strength", "minutes" to 30), role = FeatureRole.Start)
 *
 * val checkout = Union.feature("checkout")      // declares the feature; the server creates it
 * checkout.start("checkout_started")
 * checkout.success("order_placed")
 * ```
 */
public object Union {
    private val lock = Any()
    private var client: Client? = null

    /** `<name>/<version>` from `Wrapper.setWrapper`, read by `configure` under [lock]. */
    private var wrapper: String? = null

    private fun shared(): Client? = synchronized(lock) { client }

    /** Creates the SDK. Calling twice replaces the previous client (useful in tests and logout flows). */
    @JvmStatic
    @JvmOverloads
    public fun configure(context: Context, writeKey: String, privacyMode: PrivacyMode, options: Options = Options()) {
        configure(context, writeKey, privacyMode, options, overrides = null)
    }

    internal fun configure(context: Context, writeKey: String, privacyMode: PrivacyMode, options: Options, overrides: ClientOverrides?) {
        synchronized(lock) {
            try {
                client?.shutdown()
                client = null
                client = Client(context, writeKey, privacyMode, options, wrapper, overrides)
            } catch (t: Throwable) {
                // The SDK never takes the host down: an app whose analytics failed to start still starts.
                SdkLogger(options.logLevel, options.logHandler).log(LogLevel.Error) { "configure failed: ${t.javaClass.simpleName}: ${t.message}" }
            }
        }
    }

    /**
     * Custom event. [name] must be lowercase snake_case (`workout_started`); `$`-prefixed names are
     * reserved. [feature] + [role] declare the event's place in a feature; see [feature] for the typed form.
     * Property values are `String` (<= 256 chars), finite numbers or `Boolean`; anything else drops the event.
     */
    @JvmStatic
    @JvmOverloads
    public fun track(
        name: String,
        properties: Map<String, Any> = emptyMap(),
        feature: String? = null,
        role: FeatureRole? = null,
        screen: String? = null,
    ) {
        val props = properties.toMap()
        shared()?.post { it.pipeline.track(name, props, feature, role, screen) }
    }

    /** Manual `$screen_view`. With [feature], the view is that feature's discovery step. */
    @JvmStatic
    @JvmOverloads
    public fun screen(name: String, properties: Map<String, Any> = emptyMap(), feature: String? = null) {
        val props = properties.toMap()
        shared()?.post { it.pipeline.screen(name, props, feature) }
    }

    /**
     * A typed handle for one feature: `Union.feature("checkout").success("order_placed")`. The key is
     * validated here so a bad one is logged once, not on every event; events through a bad handle are dropped.
     */
    @JvmStatic
    public fun feature(key: String): Feature {
        shared()?.let { c ->
            try {
                Validation.featureKey(key)
            } catch (e: ValidationException) {
                c.logger.log(LogLevel.Warning) { "feature handle \"$key\" will drop its events: ${e.message}" }
            }
        }
        return Feature(key)
    }

    /**
     * Attach your own user id after login, optionally with traits describing the person (`name`, `email`,
     * `plan`). Traits are merged key by key across calls; up to 8 traits, values <= 256 characters. They are
     * stored as sent: an app that puts an email or a name here is collecting it and has to say so in its own
     * Play Data safety answers. Ignored (with a warning) in `StrictAnonymous`.
     */
    @JvmStatic
    @JvmOverloads
    public fun identify(userId: String, traits: Map<String, String> = emptyMap()) {
        val t = traits.toMap()
        shared()?.post { it.pipeline.identify(userId, t) }
    }

    /**
     * Logout: forgets the user id and starts a new session. The install id is kept. A no-op when nobody is
     * signed in, so a cold launch that resets before auth restores keeps one session.
     */
    @JvmStatic
    public fun reset() {
        shared()?.post { it.pipeline.reset() }
    }

    /** Enables or disables product analytics while leaving crash reporting active. */
    @JvmStatic
    public fun setAnalyticsCollectionEnabled(enabled: Boolean) {
        shared()?.post { it.pipeline.setAnalyticsCollectionEnabled(enabled) }
    }

    /** Stops all collection, crash reporting included, and wipes local data. Persists across launches until [optIn]. */
    @JvmStatic
    public fun optOut() {
        shared()?.post { it.pipeline.optOut() }
    }

    @JvmStatic
    public fun optIn() {
        shared()?.optIn()
    }

    /** Today equivalent to [optOut]. Server-side deletion is requested from the panel (Settings -> Privacy). */
    @JvmStatic
    public fun requestDataDeletion(): Unit = optOut()

    /** Records `$deep_link { url_scheme, host, path }`. The query string and fragment are never sent. */
    @JvmStatic
    public fun handleDeepLink(uri: Uri) {
        val scheme = uri.scheme
        val host = uri.host
        val path = uri.path
        shared()?.post { it.pipeline.deepLink(scheme, host, path) }
    }

    /** `Intent.data` of a launch or `onNewIntent`; an intent without data is ignored. */
    @JvmStatic
    public fun handleDeepLink(intent: Intent) {
        intent.data?.let(::handleDeepLink)
    }

    // region Crashes

    /**
     * An error the app caught and wants counted anyway. A `nonfatal` is a **separate severity** and never
     * added to the crash count. The stack is the throwable's own; its `cause` chain travels as extra threads.
     */
    @JvmStatic
    public fun recordError(error: Throwable) {
        shared()?.crash?.recordError(error)
    }

    /**
     * The same for a named error. The stack is the caller's, taken here: write it where the error was
     * handled, not in a shared logging helper (a helper's stack groups every unrelated error into one issue).
     */
    @JvmStatic
    @JvmOverloads
    public fun recordError(type: String, reason: String? = null) {
        shared()?.crash?.recordError(type, reason)
    }

    /**
     * A label attached to every crash report from now on. Up to 8 keys, values <= 256 characters; `null`
     * removes one. Ignored in `StrictAnonymous`.
     */
    @JvmStatic
    public fun setCrashKey(key: String, value: String?) {
        shared()?.crash?.setCustomKey(key, value)
    }

    /** A note in the trail a crash report carries: a name, never a value. */
    @JvmStatic
    public fun leaveBreadcrumb(name: String) {
        shared()?.crash?.breadcrumb(dev.useunion.internal.BreadcrumbKind.Log, name)
    }

    // endregion

    /**
     * Sends queued events now, including everything queued by calls made before this one. The SDK also
     * flushes every `Options.flushIntervalSeconds`, at `flushAt` events and on background.
     */
    @JvmStatic
    public suspend fun flush() {
        val c = shared() ?: return
        c.barrier()
        c.pipeline.flush()
    }

    /**
     * Callback variant of [flush] for Java callers and code that is not in a coroutine: `Union.flush { ... }`.
     * [onDone] runs on a background thread. (No default for [onDone] on purpose: `Union.flush()` must keep
     * meaning the suspend function, and a default here would make the two ambiguous.)
     */
    @JvmStatic
    public fun flush(onDone: Runnable) {
        val c = shared()
        if (c == null) {
            onDone.run()
            return
        }
        c.launch {
            try {
                flush()
            } finally {
                onDone.run()
            }
        }
    }

    /** Blocks the calling thread until [flush] finishes or [timeoutMs] passes. **Never call it on the main thread.** */
    @JvmStatic
    @JvmOverloads
    public fun flushBlocking(timeoutMs: Long = 5_000) {
        runBlocking { withTimeoutOrNull(timeoutMs) { flush() } }
    }

    @JvmStatic
    public val isConfigured: Boolean get() = shared() != null

    /**
     * The pseudonymous install id, or `null` before [configure] and in `StrictAnonymous`: that mode stores no
     * identity, and returning a fresh id would quietly defeat it.
     *
     * Pass it to Play Billing as `setObfuscatedAccountId(Union.installId)` so Union can join purchases to the
     * person, and to your backend for `/v1/server` events. It identifies a device, so an app that forwards it
     * answers for it in its own Play Data safety form. Unlike iOS it does **not** survive a reinstall.
     */
    @JvmStatic
    public val installId: String?
        get() = shared()?.identity?.ensure(System.currentTimeMillis())?.installId

    /**
     * The bridge for the Flutter and React Native wrappers, and nothing else. Hidden from app developers
     * (`RestrictTo(LIBRARY_GROUP)`) and with no stability promise beyond SDK-PLATFORMS.md ("Wrapper bridge").
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public object Wrapper {
        /**
         * Declares which wrapper sits in front of this SDK: sets `device.sdk_wrapper` to `<name>/<version>`
         * and appends ` <name>/<version>` to the User-Agent. Must be called **before** `configure`; after it
         * the call is ignored with a log (the device context is built once per client). An empty name or
         * version, whitespace, `/`, non-ASCII, or a value over 32 characters is ignored with a log too.
         */
        @JvmStatic
        public fun setWrapper(name: String, version: String) {
            synchronized(lock) {
                val c = client
                if (c != null) {
                    c.logger.log(LogLevel.Warning) { "setWrapper($name/$version) ignored: it must be called before configure" }
                    return
                }
                val value = WrapperInfo.value(name, version)
                if (value == null) {
                    SdkLogger(LogLevel.Warning, null).log(LogLevel.Warning) { "setWrapper ignored: \"$name\"/\"$version\" is not a valid <name>/<version> of at most 32 characters" }
                    return
                }
                wrapper = value
            }
        }

        /**
         * A Dart or JS error with its parsed stack. `fatal = false` is a `nonfatal`, like [recordError].
         * `fatal = true` is written to the crash store **synchronously** (the runtime is about to take the
         * process down) and uploaded on the next launch. [thread] names the crashed thread (`js` for React
         * Native's JS thread). Managed reports carry no binary images.
         */
        @JvmStatic
        @JvmOverloads
        public fun recordManagedError(type: String, reason: String?, frames: List<ManagedFrame>, fatal: Boolean, thread: String = "main") {
            shared()?.crash?.recordManagedError(type, reason, frames.toList(), fatal, thread)
        }
    }

    // region Test hooks

    internal fun resetForTesting() {
        synchronized(lock) {
            client?.shutdown()
            client = null
            wrapper = null
        }
    }

    internal val currentClient: Client? get() = shared()
    internal val registeredWrapper: String? get() = synchronized(lock) { wrapper }

    // endregion
}

/** One frame of a Dart or JavaScript stack, as the wrapper parsed it. `n` is assigned from list order, innermost first. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public data class ManagedFrame @JvmOverloads constructor(
    val function: String,
    /** The class, Dart library or JS bundle, when the runtime gives it apart from the function. */
    val module: String? = null,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    /** The wrapper's call: whether this frame is the app's own code. */
    val inApp: Boolean,
)
