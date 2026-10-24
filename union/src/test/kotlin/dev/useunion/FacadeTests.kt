package dev.useunion

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import dev.useunion.internal.Client
import dev.useunion.internal.ClientOverrides
import dev.useunion.internal.DeviceContextProvider
import dev.useunion.internal.EnvironmentDetector
import dev.useunion.internal.Json
import dev.useunion.internal.Schemas
import dev.useunion.internal.StubTransport
import dev.useunion.internal.TestClock
import dev.useunion.internal.events
import dev.useunion.internal.names
import dev.useunion.internal.obj
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.UUID

/** The Android-facing half: package info, environment, facade, deep links, wrapper, lifecycle, screens. */
@RunWith(RobolectricTestRunner::class)
class FacadeTests {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var savedHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var transport: StubTransport
    private lateinit var crashTransport: StubTransport
    private var keyCounter = 0
    private lateinit var key: String

    @Before fun setUp() {
        Union.resetForTesting()
        savedHandler = Thread.getDefaultUncaughtExceptionHandler()
        transport = StubTransport()
        // Offline: crash files stay on disk for the test to read.
        crashTransport = StubTransport().also { it.failWithNetworkError = true }
        key = "av_test_${System.nanoTime()}_${keyCounter++}"
    }

    @After fun tearDown() {
        Union.resetForTesting()
        shadowOf(Looper.getMainLooper()).idle()
        Thread.setDefaultUncaughtExceptionHandler(savedHandler)
    }

    private fun configure(privacy: PrivacyMode = PrivacyMode.ProductAnalytics, options: Options = Options().also { it.logLevel = LogLevel.None }) {
        Union.configure(app, key, privacy, options, ClientOverrides(transport, crashTransport, TestClock()))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun flush() = runBlocking { Union.flush() }

    private fun baseDir(): File = File(app.noBackupFilesDir, "Union/${Client.stableHash(key)}")

    // region Device context and environment

    @Test fun deviceContextReadsVersionNameAndCodeFromThePackageInfo() {
        val info = PackageInfo().apply {
            packageName = app.packageName
            versionName = "3.1.4"
            longVersionCode = 314
        }
        shadowOf(app.packageManager).installPackage(info)
        val d = DeviceContextProvider.current(app, wrapper = null)
        assertEquals("3.1.4", d.appVersion)
        assertEquals("314", d.appBuild)
        assertEquals("Android", d.osName)
        assertEquals(android.os.Build.VERSION.RELEASE, d.osVersion)
        assertEquals(SdkInfo0.version, d.sdkVersion)
        assertNull(d.sdkWrapper)
        assertTrue(d.deviceModel.isNotEmpty() && d.deviceModel.length <= 64)
        assertTrue(d.locale.isNotEmpty() && d.locale.length <= 16)
        assertTrue(d.timezone.isNotEmpty() && d.timezone.length <= 64)
    }

    private object SdkInfo0 { val version = dev.useunion.internal.SdkInfo.version }

    @Test fun deviceContextFallsBackToZeroWhenThePackageHasNoVersion() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = app.packageName })
        val d = DeviceContextProvider.current(app, "flutter/0.1.0")
        assertEquals("0", d.appVersion)
        assertEquals("flutter/0.1.0", d.sdkWrapper)
    }

    @Test fun aDebuggableAppIsDevelopmentAndEverythingElseIsProduction() {
        val flags = app.applicationInfo.flags
        try {
            app.applicationInfo.flags = flags or ApplicationInfo.FLAG_DEBUGGABLE
            assertEquals(Environment.Development, EnvironmentDetector.detect(app))
            app.applicationInfo.flags = flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            assertEquals(Environment.Production, EnvironmentDetector.detect(app))
        } finally {
            app.applicationInfo.flags = flags
        }
    }

    @Test fun optionsEnvironmentOverridesDetectionAndReachesTheBatch() {
        configure(options = Options().also { it.environment = Environment.Testflight; it.logLevel = LogLevel.None })
        Union.track("e_one")
        flush()
        assertEquals("testflight", transport.sent[0]["environment"])
        assertTrue(Schemas.eventErrors(transport.bodies[0]).isEmpty())
    }

    // endregion

    // region Facade end to end

    @Test fun theFacadeRunsAColdStartThroughTheRealStoresAndBatchConformsToTheSchema() {
        configure()
        Union.track("workout_started", mapOf("plan" to "strength", "minutes" to 30), role = FeatureRole.Start, screen = "WorkoutDetail")
        flush()
        val batch = transport.sent.single()
        assertEquals(listOf("\$first_open", "\$app_install", "\$session_start", "workout_started"), batch.names())
        assertEquals("Android", batch.obj("device")["os_name"])
        assertNotNull(batch.obj("identity")["install_id"])
        val errors = Schemas.eventErrors(transport.bodies[0])
        assertEquals(errors.joinToString("\n"), emptyList<String>(), errors)
        assertTrue("the queue lives in noBackupFilesDir, which Auto Backup skips", File(baseDir(), "queue.ndjson").exists())
        assertTrue(Union.isConfigured)
    }

    @Test fun flushSeesEverythingPostedBeforeIt() {
        configure()
        repeat(5) { Union.track("e_one") }
        flush()
        assertEquals(1, transport.sent.size)
        assertEquals(5, transport.sent[0].names().count { it == "e_one" })
    }

    @Test fun theCallbackAndBlockingFlushVariantsFlushToo() {
        configure()
        Union.track("e_one")
        val done = java.util.concurrent.CountDownLatch(1)
        Union.flush(Runnable { done.countDown() })
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, transport.sent.size)
        Union.track("e_two")
        Union.flushBlocking(5_000)
        assertEquals(2, transport.sent.size)
    }

    @Test fun callsBeforeConfigureAreNoOpsAndNeverThrow() {
        assertFalse(Union.isConfigured)
        assertNull(Union.installId)
        Union.track("e_one")
        Union.screen("S")
        Union.identify("u")
        Union.reset()
        Union.optOut()
        Union.optIn()
        Union.recordError("X")
        Union.recordError(RuntimeException())
        Union.setCrashKey("a", "b")
        Union.leaveBreadcrumb("x")
        Union.handleDeepLink(Uri.parse("a://b/c"))
        Union.feature("checkout").success("order_placed")
        runBlocking { Union.flush() }
        Union.flushBlocking(100)
    }

    @Test fun anInvalidFeatureHandleDropsItsEvents() {
        configure()
        Union.feature("Bad Key").success("order_placed")
        Union.feature("checkout").success("order_placed")
        Union.feature("checkout").screen("Checkout")
        flush()
        val events = transport.sent.single().events()
        assertEquals(1, events.count { it["name"] == "order_placed" })
        assertEquals("checkout", events.first { it["name"] == "\$screen_view" }["feature"])
        assertEquals("discovery", events.first { it["name"] == "\$screen_view" }["role"])
    }

    @Test fun installIdIsStableAMintedUuidAndAbsentInStrictAnonymous() {
        configure()
        val id = Union.installId!!
        assertEquals(id, UUID.fromString(id).toString())
        assertEquals(id, Union.installId)
        flush()
        assertEquals("the batch carries the id the app was given", id, transport.sent.single().obj("identity")["install_id"].also { })
        configure(privacy = PrivacyMode.StrictAnonymous)
        assertNull(Union.installId)
    }

    @Test fun theInstallIdSurvivesAConfigureOnTheSameKey() {
        configure()
        val first = Union.installId
        configure()
        assertEquals(first, Union.installId)
    }

    @Test fun identifyAndResetThroughTheFacade() {
        configure()
        Union.identify("user_1", mapOf("plan" to "pro"))
        Union.track("e_one")
        flush()
        assertEquals("user_1", transport.sent.last().obj("identity")["user_id"])
        Union.reset()
        Union.track("e_two")
        flush()
        assertNull(transport.sent.last().obj("identity")["user_id"])
        assertNull(transport.sent.last().obj("identity")["traits"])
    }

    // endregion

    // region Deep links

    @Test fun aDeepLinkReportsSchemeHostAndPathButNeverTheQueryOrFragment() {
        configure()
        Union.handleDeepLink(Uri.parse("myapp://workouts/42?token=SECRET&email=a@b.c#frag-secret"))
        Union.handleDeepLink(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/invite/abc?code=SECRET2")))
        Union.handleDeepLink(Intent(Intent.ACTION_MAIN)) // no data: ignored
        Union.handleDeepLink(Uri.parse("mailto:someone@example.com"))
        flush()
        val body = transport.bodies[0].toString(Charsets.UTF_8)
        for (secret in listOf("SECRET", "token", "frag-secret", "email", "code=", "someone@example.com")) assertFalse("$secret leaked: $body", body.contains(secret))
        val links = transport.sent[0].events().filter { it["name"] == "\$deep_link" }
        assertEquals(3, links.size)
        assertEquals(mapOf("url_scheme" to "myapp", "host" to "workouts", "path" to "/42"), links[0]["properties"])
        assertEquals(mapOf("url_scheme" to "https", "host" to "example.com", "path" to "/invite/abc"), links[1]["properties"])
        assertEquals("mailto", (links[2]["properties"] as Map<*, *>)["url_scheme"])
        assertTrue(Schemas.eventErrors(transport.bodies[0]).isEmpty())
    }

    // endregion

    // region Device state and ANR source

    @Test fun theDeviceStateSamplerReportsOnlyWhatItCouldReadAndValidates() {
        var foreground = true
        val sampler = dev.useunion.internal.crash.AndroidDeviceStateSampler(app) { foreground }
        val state = sampler.sample()
        assertEquals(true, state["in_foreground"])
        assertTrue(state["orientation"] in listOf("portrait", "landscape", "unknown"))
        assertTrue(state["jailbroken"] is Boolean)
        assertTrue((state["uptime_ms"] as Long) >= 0)
        (state["battery_level"] as Double?)?.let { assertTrue(it in 0.0..1.0) }
        foreground = false
        assertEquals(false, sampler.sample()["in_foreground"])

        val report = dev.useunion.internal.crash.CrashReportBuilder.build(
            dev.useunion.internal.crash.ReportInputs(
                crashId = UUID.randomUUID().toString(), kind = dev.useunion.internal.crash.CrashKind.NonFatal, crashedAt = 1, sessionId = null,
                context = DeviceContextProvider.current(app, null), state = state, exceptionType = "T", exceptionReason = null, threads = emptyList(),
            ),
        )
        val batch = Json.write(dev.useunion.internal.crash.CrashReportBuilder.batch(UUID.randomUUID().toString(), Environment.Production, PrivacyMode.ProductAnalytics, 1, dev.useunion.internal.Identity(), listOf(report)))
        assertEquals(emptyList<String>(), Schemas.crashErrors(batch))
    }

    @Test fun theExitInfoSourceReadsOnlyAnrReasons() {
        val am = app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        shadowOf(am).addApplicationExitInfo(app.packageName, 1234, android.app.ApplicationExitInfo.REASON_ANR, 0)
        shadowOf(am).addApplicationExitInfo(app.packageName, 1235, android.app.ApplicationExitInfo.REASON_CRASH, 0)
        val exits = dev.useunion.internal.ExitInfoAnrSource(app).exits()
        assertEquals(1, exits.size)
    }

    // endregion

    // region Wrapper bridge

    @Test fun setWrapperBeforeConfigureLabelsTheBatchAndTheUserAgent() {
        Union.Wrapper.setWrapper("flutter", "0.3.1")
        assertEquals("flutter/0.3.1", Union.registeredWrapper)
        configure()
        Union.track("e_one")
        flush()
        assertEquals("flutter/0.3.1", transport.sent[0].obj("device")["sdk_wrapper"])
        assertEquals(SdkInfo0.version, transport.sent[0].obj("device")["sdk_version"])
        assertTrue(Schemas.eventErrors(transport.bodies[0]).isEmpty())
        assertEquals("union-android/${SdkInfo0.version} flutter/0.3.1", dev.useunion.internal.SdkInfo.userAgent("flutter/0.3.1"))
    }

    @Test fun setWrapperAfterConfigureAndInvalidWrappersAreIgnored() {
        Union.Wrapper.setWrapper("bad name", "1")
        Union.Wrapper.setWrapper("flutter", "")
        Union.Wrapper.setWrapper("a/b", "1")
        assertNull(Union.registeredWrapper)
        configure()
        Union.Wrapper.setWrapper("react-native", "1.0.0")
        assertNull("the device context is built once per client", Union.registeredWrapper)
        Union.track("e_one")
        flush()
        assertFalse(transport.bodies[0].toString(Charsets.UTF_8).contains("sdk_wrapper"))
    }

    @Test fun aManagedErrorThroughTheBridgeLandsInTheCrashStoreWithTheWrapperContext() {
        Union.Wrapper.setWrapper("react-native", "1.2.3")
        configure()
        Union.Wrapper.recordManagedError("TypeError", "undefined is not a function", listOf(ManagedFrame("onPress", file = "App.js", line = 10, column = 3, inApp = true)), fatal = true, thread = "js")
        val dir = File(baseDir(), "crashes")
        val file = dir.listFiles { f -> f.name.endsWith(".report.json") }!!.single()
        val report = Json.parseObjectOrNull(file.readText())!!
        assertEquals("fatal", report["kind"])
        assertEquals("react-native/1.2.3", report.obj("context")["sdk_wrapper"])
        val batch = Json.write(
            dev.useunion.internal.crash.CrashReportBuilder.batch(UUID.randomUUID().toString(), Environment.Production, PrivacyMode.ProductAnalytics, 1, dev.useunion.internal.Identity(), listOf(report)),
        )
        assertEquals(emptyList<String>(), Schemas.crashErrors(batch))
    }

    // endregion

    // region Crash reporting wiring

    @Test fun configureInstallsTheUncaughtHandlerChainedToThePreviousOne() {
        val previous = Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler(previous)
        configure()
        val ours = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(ours !== previous)
        Union.leaveBreadcrumb("before_crash")
        Union.setCrashKey("plan", "pro")
        ours!!.uncaughtException(Thread.currentThread(), IllegalStateException("fatal in test"))
        val report = File(baseDir(), "crashes").listFiles { f -> f.name.endsWith(".report.json") }!!.single().let { Json.parseObjectOrNull(it.readText())!! }
        assertEquals("fatal", report["kind"])
        assertEquals("java.lang.IllegalStateException", report.obj("exception")["type"])
        assertEquals(mapOf("plan" to "pro"), report["custom_keys"])
    }

    @Test fun crashReportingOffInstallsNothing() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        configure(options = Options().also { it.crashReporting = false; it.logLevel = LogLevel.None })
        assertSame(previous, Thread.getDefaultUncaughtExceptionHandler())
        Union.recordError("Ignored")
        assertFalse(File(baseDir(), "crashes").exists())
    }

    @Test fun optOutTakesTheCrashHandlerAndAnOptedOutUserStaysOptedOutAcrossLaunches() {
        val previous = Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler(previous)
        configure()
        Union.optOut()
        flush() // the barrier: the opt-out has been processed
        assertSame("opt-out stops the crash handler", previous, Thread.getDefaultUncaughtExceptionHandler())

        // The next launch: the persisted opt-out is honoured by crash reporting too (it is not on iOS: see CHECKLIST).
        Union.resetForTesting()
        configure()
        assertSame(previous, Thread.getDefaultUncaughtExceptionHandler())
        Union.track("e_one")
        flush()
        assertTrue(transport.sent.isEmpty())

        Union.optIn()
        flush()
        assertTrue("opting back in re-arms crash reporting", Thread.getDefaultUncaughtExceptionHandler() !== previous)
    }

    // endregion

    // region Lifecycle and screens

    @Test fun processLifecycleDrivesForegroundAndBackgroundAndFlushesOnStop() {
        val registry = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        assertFalse("no activity is running in a unit test", registry.currentState.isAtLeast(Lifecycle.State.STARTED))
        configure()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        shadowOf(Looper.getMainLooper()).idle()
        runBlocking { Union.flush() }
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        shadowOf(Looper.getMainLooper()).idle()
        val deadline = System.currentTimeMillis() + 5000
        while (transport.sent.flatMap { it.names() }.none { it == "\$background" } && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val names = transport.sent.flatMap { it.names() }
        assertEquals(listOf("\$first_open", "\$app_install", "\$session_start", "\$foreground", "\$background"), names)
        assertEquals("the ON_STOP flush is its own batch", 2, transport.sent.size)
    }

    @Test fun configuringWhileTheAppIsAlreadyStartedDoesNotEmitAForegroundOnTheReplay() {
        val registry = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        try {
            configure() // addObserver replays ON_START: on iOS willEnterForeground never fires on a cold start
            Union.track("e_one")
            flush()
            assertFalse(transport.sent.flatMap { it.names() }.contains("\$foreground"))
            assertEquals(listOf("\$first_open", "\$app_install", "\$session_start", "e_one"), transport.sent.single().names())
        } finally {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun anActivityResumeIsAScreenViewWithTheSimpleNameAndARotationIsNot() {
        configure(options = Options().also { it.automaticScreenTracking = true; it.logLevel = LogLevel.None })
        val controller = Robolectric.buildActivity(CheckoutActivity::class.java).setup()
        controller.configurationChange() // destroy + recreate the same class: still the same screen
        Robolectric.buildActivity(SettingsActivity::class.java).setup()
        flush()
        val screens = transport.sent.flatMap { it.events() }.filter { it["name"] == "\$screen_view" }.map { it["screen"] }
        assertEquals(listOf("CheckoutActivity", "SettingsActivity"), screens)
    }

    @Test fun screenNamesComeFromTheSimpleNameAndAnonymousClassesAreNotScreens() {
        assertEquals("CheckoutActivity", dev.useunion.internal.ScreenTracking.screenName(CheckoutActivity::class.java))
        assertNull(dev.useunion.internal.ScreenTracking.screenName(object : Activity() {}.javaClass))
    }

    class CheckoutActivity : Activity()
    class SettingsActivity : Activity()

    // endregion
}
