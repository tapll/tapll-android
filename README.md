# Tapll Android SDK

Product analytics and crash reporting for Android apps: live sessions, screens, features, releases, crashes, ANRs. Kotlin, minSdk 24, no third-party dependencies beyond AndroidX lifecycle and kotlinx-coroutines, no advertising ID.

| Artifact | What it is |
|---|---|
| `com.tapll:tapll` | the SDK |
| `com.tapll:tapll-compose` | `TrackScreen("Name")` for Jetpack Compose |
| Gradle plugin `com.tapll` | build id + R8 mapping upload, so crash stacks are readable |

## Install

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("com.tapll") version "0.2.0"     // optional, see "Readable crash stacks"
}

dependencies {
    implementation("com.tapll:tapll:0.2.0")
    implementation("com.tapll:tapll-compose:0.2.0")   // only if you use Compose
}
```

The SDK declares `android.permission.INTERNET` in its own manifest (a normal permission, no prompt).

**Integrating with a coding agent** (Claude Code, Cursor, Codex)? Copy the setup prompt from the Tapll panel, **Settings -> SDK setup**: it is filled in with your project's write key, privacy mode and connected integrations. A generic version is at [tapll.com/llms.txt](https://tapll.com/llms.txt).

### Upgrading from Union (`dev.useunion:union` 0.1.x)

0.2.0 is the Union to Tapll rename. It is a **new set of coordinates and a new package**, so the upgrade is a one-time edit:

| Before | Now |
|---|---|
| `dev.useunion:union` | `com.tapll:tapll` |
| `dev.useunion:union-compose` | `com.tapll:tapll-compose` |
| Gradle plugin `dev.useunion.union`, extension `union { }` | Gradle plugin `com.tapll`, extension `tapll { }` |
| `import dev.useunion.*`, `Union.configure(...)` | `import com.tapll.*`, `Tapll.configure(...)` |
| `UNION_SERVER_KEY`, `-Punion.enabled` | `TAPLL_SERVER_KEY`, `-Ptapll.enabled` (the old names still work as a fallback) |
| default endpoint `https://in.useunion.dev` | `https://in.tapll.com` |
| RevenueCat attribute `union_install_id` | `tapll_install_id` |

`com.tapll.Union` remains as a deprecated typealias of `Tapll` for one or two minor releases (Kotlin only), so `Union.track(...)` keeps compiling with a warning. Apps already in the field keep working: the first launch on 0.2.0 moves the on-device state (`noBackupFilesDir/Union` to `noBackupFilesDir/Tapll`: install id, offline queue, pending crash reports) to the new location, so the install id and anything unsent from the previous launch survive. Builds made with the old Gradle plugin still get their build id read from `assets/union-build-id.txt`.

## 10-minute setup

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Tapll.configure(this, writeKey = "av_...", privacyMode = PrivacyMode.ProductAnalytics)
    }
}
```

Register `MyApp` in the manifest (`android:name=".MyApp"`). That already sends `$first_open`, `$app_install` / `$app_update`, `$session_start` / `$session_end`, `$foreground` / `$background` and device context (app version + build, Android version, device model, locale, timezone). The panel's **Live** view shows the first session within seconds.

```kotlin
Tapll.screen("WorkoutDetail")                                                        // or TrackScreen("WorkoutDetail") in Compose
Tapll.track("workout_started", mapOf("plan" to "strength", "minutes" to 30), role = FeatureRole.Start)
Tapll.track("workout_finished", role = FeatureRole.Success)
Tapll.identify("user_123")                                                           // after login (ProductAnalytics only)
Tapll.identify("user_123", mapOf("email" to "ada@example.com", "name" to "Ada"))    // named in the panel
Tapll.reset()                                                                        // on logout
Tapll.handleDeepLink(intent)                                                         // onCreate / onNewIntent; or handleDeepLink(uri)
```

Traits describe the person, so the panel can head a profile with a name instead of an id. Up to 8 keys, values up to 256 characters, merged key by key across calls; `reset()` forgets them with the user id. They are stored as sent: an app that puts an email or a name here is collecting that data and must declare it in its own Play Data safety form, which the SDK cannot do on its behalf.

Event names: lowercase `snake_case`, max 64 characters. Properties: up to 32 keys; values are `String` (up to 256 characters), finite numbers or `Boolean`. Anything invalid is logged and dropped. The SDK never throws into your app.

### Screens

```kotlin
@Composable
fun CheckoutScreen() {
    TrackScreen("Checkout", feature = "checkout")   // one $screen_view per entry into the composition
    ...
}
```

`TrackScreen` fires when the composable enters the composition, not on every recomposition; it fires again when `name` or `feature` change, or when it leaves and comes back. For view-based apps, set `Options.automaticScreenTracking = true`: every `Activity.onResume` becomes a `$screen_view` named with the activity's simple name (a rotation is the same screen and is not counted twice). Single-activity apps with fragments or custom navigation call `Tapll.screen("Name")`.

## Features

Declare a feature where its events are sent, and the funnel builds itself:

```kotlin
val checkout = Tapll.feature("checkout")
checkout.screen("Checkout")                                     // discovery: saw it exists
checkout.start("checkout_started")                              // start: tried; the funnel's denominator
checkout.use("shipping_selected")                               // use: working inside it, not a step
checkout.success("order_placed", mapOf("total" to 49.9))        // success: got what they came for
checkout.failure("payment_failed")                              // failure: the user's attempt failed
```

The roles are a funnel, so `start` and `success` are the two that make one: a feature with only `use` counts traffic and shows nothing about where people drop, and a feature with no `failure` reads as if nobody ever fails. `failure` means an attempt that failed *for the user* (a refused permission, an empty result, a save that did not happen), not any caught error; those are `recordError`. Give a giving-up its **own event name**: one name declared `success` in one place and `failure` in another is a conflict the server shows and does not resolve.

A feature is something a user can fail to adopt. Naming one after a database table means every user touches it on day one, so it reports full adoption and tells you nothing; events in that part of a product are better sent with no feature key at all.

A declaration is a definition, not a hint. From production and pre-release builds the server creates the feature (or adds the new events to one the panel already has), and the panel refines it: rename it, set an owner, add events. Development builds only pre-fill the panel's editor. A typo in the key creates a second feature. Keys follow the event-name rules (`snake_case`, max 64 characters). `Tapll.track(name, feature = "checkout", role = FeatureRole.Start)` is the same thing without the handle.

## Crashes, ANRs and non-fatals

On by default. `configure` installs an uncaught-exception handler (chained to whatever was installed before, never replacing it) and a watchdog on the main looper. Nothing else is needed:

```kotlin
Tapll.recordError(throwable)                                  // a caught error, counted separately
Tapll.recordError("PaymentFailed", reason = "card_declined")  // same, by name
Tapll.setCrashKey("plan", "pro")                              // up to 8 labels on every later report
Tapll.leaveBreadcrumb("retrying_upload")                      // a name in the trail, never a value
```

**A crash is not sent by the process that died.** The handler writes the report to disk and the SDK uploads it on the next launch, so a crash appears minutes later, days later, or, for someone who uninstalls, never. That is why the panel's crash-free rate is a ceiling for a window that is still filling, and why "no crashes today" is not the same claim as "a stable day".

Four kinds are reported, and never added together: `fatal` (uncaught exception), `nonfatal` (`recordError`), `hang` (main thread blocked for at least `hangThresholdSeconds`, reported once per episode with the measured duration) and `anr` (read from `ApplicationExitInfo` on the next launch, Android 11+).

How a report is shaped:

- `exception.type` is the class name of the thrown exception, `reason` its message. The `cause` chain is not flattened: the crashed thread carries the thrown exception's own stack and each cause becomes an extra thread named `caused by <type>`.
- Frames are `{function: "pkg.Class.method", file, line, in_app}`. `in_app` is true for classes in your application's package (`context.packageName`) or in `Options.inAppPackages`; set the latter when your code namespace differs from the `applicationId`.
- Messages are app-authored text and may carry personal data; they are excluded from the fingerprint.

```kotlin
val options = Options().apply {
    crashReporting = false          // installs nothing at all: no handler, no watchdog, no crash directory
    hangDetection = true            // main-thread freezes, reported as `hang`
    hangThresholdSeconds = 2.0
    inAppPackages = listOf("com.example.core.")
}
```

There are two switches and both are on by default: `Options.crashReporting` here, and the project's own **crash reporting** setting in Tapll. Turning the project's off does not stop the upload; it turns it into a recorded refusal, so nothing is stored and nothing is silently lost either way.

Custom keys are refused in `StrictAnonymous` for the same reason `identify` is, and an app that puts an identifier there is collecting it and answers for it in its Data safety form. Breadcrumbs have no field for a value at all.

### Readable crash stacks (R8 mapping upload)

A release build that is minified with R8 reports `a.b.c` frames. The `com.tapll` Gradle plugin makes them readable:

```kotlin
// app/build.gradle.kts
plugins { id("com.tapll") version "0.2.0" }

tapll {
    endpoint = "https://in.tapll.com/v1/mappings"               // the default
    serverKey = providers.environmentVariable("TAPLL_SERVER_KEY")  // the default
}
```

For every **minified release variant** it (1) generates a lowercase UUIDv4 build id and merges it into the app as `assets/tapll-build-id.txt`, which the SDK puts in every report as `build_id`, and (2) registers `uploadTapllMapping<Variant>` (for example `uploadTapllMappingRelease`), which runs after `minify<Variant>WithR8` and `POST`s the gzipped `mapping.txt` with the headers `x-tapll-key`, `x-tapll-build-id`, `x-tapll-app-version`, `x-tapll-app-build` and `content-encoding: gzip`.

- The key is the project's **server key** (`sk_...`), set as `TAPLL_SERVER_KEY` in CI. **Never put it in the app** and never use the `av_...` write key here; the plugin refuses a client key.
- No key, no mapping, an empty mapping or a failed upload is a **warning, never a failed build**.
- The identity of a frame is the obfuscated `function:line`. Uploading a mapping changes how it is displayed, never how issues are grouped, so you can upload late or not at all.
- Debug builds are untouched.

## Linking purchases

Tapll joins purchases, renewals and refunds to a person through the account id you give Play Billing. Set it from the install id when you launch the billing flow:

```kotlin
val params = BillingFlowParams.newBuilder()
    .setProductDetailsParamsList(listOf(productParams))
    .apply { Tapll.installId?.let { setObfuscatedAccountId(it) } }
    .build()
billingClient.launchBillingFlow(activity, params)
```

`Tapll.installId` is `null` before `configure` and in `StrictAnonymous`. Purchases made before you set it cannot be linked afterwards, so set it before your first release that sells anything. **Unlike on iOS, the install id does not survive a reinstall**: nothing on Android does, so a reinstall is a new install.

Using RevenueCat instead? Set the subscriber attribute `tapll_install_id` to `Tapll.installId`, and call `Tapll.identify(userId)` with the same id you give RevenueCat as `app_user_id`.

Revenue never goes through the SDK; it reaches Tapll from the store and RevenueCat.

## Options

```kotlin
val o = Options().apply {
    environment = Environment.Testflight   // default: debuggable app -> development, otherwise production
    automaticScreenTracking = true
    flushAt = 20; flushIntervalSeconds = 10.0; maxQueuedEvents = 1000
    logLevel = LogLevel.Debug
    logHandler = { level, message -> Log.d("Tapll", "$level $message") }
    endpoint = "https://in.tapll.com/v1/batch"     // default; the crash endpoint is derived from it
}
Tapll.configure(this, "av_...", PrivacyMode.ProductAnalytics, o)
```

One write key serves every build. The SDK detects the environment itself (a debuggable app is `development`, anything else `production`) and sends it with each batch. Google Play does not tell an app which track it was installed from, so a build on an internal or closed testing track that should read as pre-release (`testflight` on the wire) sets `Options.environment` itself.

From an emulator, the host machine is `10.0.2.2`, not `localhost`.

## Privacy modes

| | `StrictAnonymous` | `ProductAnalytics` |
|---|---|---|
| Identifier across sessions | none (`identity: {}`) | pseudonymous `install_id` (private app storage, this device only) + optional `user_id` |
| Panel capabilities | screens, events, versions, features per session | + journeys, retention, repeat usage, cohorts |
| Custom crash keys | refused | allowed |

Neither mode uses the advertising ID. IP addresses are truncated server-side before storage; location is coarse (country/region). Don't describe `ProductAnalytics` data as "fully anonymous".

`Tapll.optOut()` stops all collection, crash reporting included, and wipes the local queue, identity, session and crash files; it persists across launches until `optIn()`. `Tapll.setAnalyticsCollectionEnabled(false)` pauses product analytics only and leaves crash reporting on. `requestDataDeletion()` is currently `optOut()`; server-side deletion is requested from the panel.

## Delivery guarantees

Events are persisted to an NDJSON queue in `noBackupFilesDir` (so Auto Backup never restores another phone's queue or install id) and sent in batches (up to 100 events / 256 KB) every 10 s, at 20 queued events, when the app goes to the background (`ON_STOP`), and on `flush()`.

```kotlin
Tapll.flush()                 // suspend: returns once everything queued before the call has been sent, or refused
Tapll.flush { /* done */ }    // callback
Tapll.flushBlocking(5_000)    // blocks the calling thread: never on the main thread
```

A `flush()` made during an automatic flush waits for it rather than returning early. Retries keep the same `event_id`, so the server deduplicates.

Server responses: a partial `400` drops only the rejected events; any other `400` and `privacy_violation` drop the batch; `401` stops the SDK for this launch; `403` pauses for an hour; `413` halves the batch; `429` pauses for `Retry-After` (or 5 minutes); network errors and `5xx` back off exponentially from 2 s up to 5 minutes, with jitter. Sessions end after 30 minutes of inactivity, the same rule the server applies. A process killed in the background cannot send `$session_end`; the server closes that session on its timeout.

Crash reports are separate: gzip, `POST /v1/crash`, deleted from disk only after a `2xx` or a permanent rejection (`400`/`413`/`422`); `5xx` and network errors keep the file for the next launch.

## Google Play Data safety

Answers for the SDK's own collection. Your app's other SDKs and your own features add to this.

| Data type (Play category) | `ProductAnalytics` | `StrictAnonymous` | Purpose | Notes |
|---|---|---|---|---|
| Device or other IDs (install id) | Collected | Not collected | Analytics | pseudonymous, per install, not the advertising ID; `Tapll.installId` also goes to Play Billing / your backend if you forward it |
| App activity: app interactions | Collected | Collected | Analytics | screens, events, sessions, deep link scheme/host/path (never the query or fragment) |
| App info and performance: crash logs, diagnostics | Collected | Collected | Analytics | stack traces, exception type and message, device state (memory, disk, battery, orientation, rooted heuristic), breadcrumb names |
| Approximate location | Collected | Collected | Analytics | derived **on the server** from the truncated IP (country/region); the SDK reads no location |
| User IDs | Collected only if you call `identify` | Never | Analytics | the id you pass |
| Personal info (name, email, ...) | Only if **you** pass it as a trait or crash key | Never | Analytics | the SDK never collects these itself |

- **Shared with third parties:** No. Tapll processes the data on your behalf as a service provider, which Google does not count as "sharing".
- **Encrypted in transit:** Yes (HTTPS).
- **Data deletion:** users can request it through you; use `Tapll.requestDataDeletion()` for the on-device part and the panel (Settings -> Privacy) for what the server holds.
- **Collection is optional** for the user if you wire `Tapll.optOut()` to a setting.

## Using Tapll from Flutter or React Native

Cross-platform apps should use the Tapll wrappers (`tapll_analytics` for Flutter, `@tapll/react-native` for React Native) rather than calling this SDK from Dart or JavaScript. The wrappers sit on top of this SDK and forward to it, so sessions, queueing, retries, privacy modes and crash files stay the native SDK's, and reports from a wrapped app carry the wrapper's name and version. You still add this SDK to the Android side through the wrapper's own setup instructions. The two calls wrappers use (`Tapll.Wrapper.setWrapper` and `Tapll.Wrapper.recordManagedError`) are hidden from app code (`@RestrictTo(LIBRARY_GROUP)`) and carry no stability promise beyond `SDK-PLATFORMS.md`.

## Development

```
./gradlew build test     # every module, the Gradle plugin (an included build) and the sample, with lint
```

| Path | |
|---|---|
| `tapll/` | the SDK; its JVM and Robolectric tests |
| `tapll-compose/` | `TrackScreen` |
| `tapll-gradle-plugin/` | `com.tapll` (an included build, so the sample applies it like a customer would) |
| `sample/` | a tiny app that configures Tapll, tracks a screen and an event (not published) |
| `docs/CHECKLIST.md` | the behaviour spec: what the SDK sends, when, and every difference from iOS |

`tapll/src/test/resources/event-batch.v1.json` and `crash-batch.v2.json` are copied from the platform repo (`packages/contract/schema`); every batch and report the tests build is validated against them. Tests use a fake transport; the only sockets are loopback ones that check the real HTTP transport's headers and gzip body.

Local publishing for a trial: `./gradlew publishToMavenLocal` (libraries) and `./gradlew -p tapll-gradle-plugin publishToMavenLocal` (plugin).
