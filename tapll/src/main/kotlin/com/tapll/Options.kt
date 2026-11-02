package com.tapll

/** Mirrors `environment` in the wire contract. */
public enum class Environment(internal val wire: String) {
    Development("development"),
    Testflight("testflight"),
    Production("production"),
}

/** Mirrors `privacy_mode` in the wire contract. `StrictAnonymous` never persists an install id or user id. */
public enum class PrivacyMode(internal val wire: String) {
    StrictAnonymous("strict_anonymous"),
    ProductAnalytics("product_analytics"),
}

/**
 * The event's role inside a feature, declared in code together with a feature key
 * (`Tapll.feature(key)`). See docs/CHECKLIST.md section 1c for what each role means.
 */
public enum class FeatureRole(internal val wire: String) {
    Discovery("discovery"),
    Start("start"),
    Use("use"),
    Success("success"),
    Failure("failure"),
}

public enum class LogLevel { Debug, Info, Warning, Error, None }

/**
 * SDK options. Every field has the same meaning as the iOS `Options`; Android-only additions are
 * marked. `analyticsCollectionEnabled` (deprecated on iOS since 0.3.3) is deliberately not carried over.
 */
public class Options {
    /** Ingest endpoint. Defaults to the hosted Tapll ingest. */
    public var endpoint: String = DEFAULT_ENDPOINT

    /**
     * Overrides automatic detection. Detection is the app's `FLAG_DEBUGGABLE`: debuggable -> development,
     * otherwise production. Play does not say which track a build is on, so a pre-release store build
     * (internal / closed testing) that should read as `testflight` must set this.
     */
    public var environment: Environment? = null

    /** Flush when this many events are queued. */
    public var flushAt: Int = 20

    /** Periodic flush while foregrounded, in seconds. */
    public var flushIntervalSeconds: Double = 10.0

    /** FIFO eviction beyond this many queued events (offline for a long time). */
    public var maxQueuedEvents: Int = 1000

    /** Emits `$screen_view` with the activity's simple name on every `onResume`. Off by default. */
    public var automaticScreenTracking: Boolean = false

    /**
     * Crash, ANR, hang and non-fatal reporting. **On by default**, for the same reason as on iOS: a
     * crash reporter nobody switched on reports nothing, and the panel would read that as a stable app.
     * `false` installs nothing: no uncaught-exception handler, no watchdog, no crash directory.
     */
    public var crashReporting: Boolean = true

    /** Reports a `hang` when the main thread stops answering for [hangThresholdSeconds]. */
    public var hangDetection: Boolean = true

    public var hangThresholdSeconds: Double = 2.0

    /** Crash upload endpoint. Derived from [endpoint] (`/v1/batch` -> `/v1/crash`) unless set. */
    public var crashEndpoint: String? = null

    /** Ceiling on crash reports kept on disk when uploads keep failing. Oldest are dropped first. */
    public var maxStoredCrashReports: Int = 16

    /**
     * Android only. Package prefixes whose classes count as the app's own code (`in_app: true`) in
     * crash stacks, in addition to `context.packageName`. Needed when the code namespace differs from
     * the applicationId, which is common with flavours and white-label builds.
     */
    public var inAppPackages: List<String> = emptyList()

    public var logLevel: LogLevel = LogLevel.Warning

    /** Receives every SDK log line; useful to forward into the host app's logger. */
    public var logHandler: ((LogLevel, String) -> Unit)? = null

    public companion object {
        public const val DEFAULT_ENDPOINT: String = "https://in.tapll.com/v1/batch"
    }
}
