package com.tapll

import com.tapll.internal.BreadcrumbKind
import com.tapll.internal.BuildId
import com.tapll.internal.DeviceContext
import com.tapll.internal.HttpTransport
import com.tapll.internal.Identity
import com.tapll.internal.InMemoryIdentityStore
import com.tapll.internal.InMemoryKeyValueStore
import com.tapll.internal.Json
import com.tapll.internal.Responses
import com.tapll.internal.Schemas
import com.tapll.internal.SdkLogger
import com.tapll.internal.StubTransport
import com.tapll.internal.TestClock
import com.tapll.internal.Transport
import com.tapll.internal.TransportResponse
import com.tapll.internal.crash.AnrExit
import com.tapll.internal.crash.AnrSource
import com.tapll.internal.crash.AnrTrace
import com.tapll.internal.crash.CrashLimits
import com.tapll.internal.crash.CrashReporter
import com.tapll.internal.crash.CrashStore
import com.tapll.internal.crash.CrashUploadOutcome
import com.tapll.internal.crash.DeviceStateSampler
import com.tapll.internal.crash.HangWatchdog
import com.tapll.internal.crash.InAppMatcher
import com.tapll.internal.crash.JvmStacks
import com.tapll.internal.crash.RootHeuristic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

@Suppress("UNCHECKED_CAST")
class CrashTests {
    @get:Rule val tmp = TemporaryFolder()

    private var savedHandler: Thread.UncaughtExceptionHandler? = null
    private val reporters = ArrayList<CrashReporter>()

    @Before fun saveHandler() {
        savedHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After fun restoreHandler() {
        reporters.forEach { it.stop() }
        Thread.setDefaultUncaughtExceptionHandler(savedHandler)
    }

    private val deviceA = DeviceContext("2.4.0", "240", SdkInfo0.version, null, "15", "Google Pixel 8", "en-US", "Europe/Warsaw")
    private val deviceB = deviceA.copy(appVersion = "2.5.0", appBuild = "250")

    private object SdkInfo0 { val version = com.tapll.internal.SdkInfo.version }

    private val sample = DeviceStateSampler {
        mapOf(
            "orientation" to "portrait", "free_memory_bytes" to 1_000_000_000L, "total_memory_bytes" to 4_000_000_000L,
            "free_disk_bytes" to 9_000_000_000L, "battery_level" to 0.5, "battery_state" to "unplugged", "jailbroken" to false,
            "low_power_mode" to false, "in_foreground" to true, "uptime_ms" to 12_345L,
        )
    }

    private class Env(val dir: File, val store: CrashStore, val transport: StubTransport, val reporter: CrashReporter, val clock: TestClock, val kv: InMemoryKeyValueStore)

    private fun env(
        dir: File = tmp.newFolder(),
        device: DeviceContext = deviceA,
        privacy: PrivacyMode = PrivacyMode.ProductAnalytics,
        // Offline by default: a non-fatal is also sent in-process, and an accepted upload deletes the file the
        // test is about to read. Tests that exercise the upload pass an online transport explicitly.
        transport: StubTransport = StubTransport().also { it.failWithNetworkError = true },
        kv: InMemoryKeyValueStore = InMemoryKeyValueStore(),
        clock: TestClock = TestClock(),
        buildId: String? = null,
        maxStored: Int = 16,
        anr: AnrSource? = null,
        identity: Identity = Identity("0190aaaa-bbbb-7ccc-8ddd-eeeeffff0000", "user_1", mapOf("plan" to "pro")),
        mainThread: () -> Thread? = { null },
        postToMain: ((Runnable) -> Unit)? = null,
        detectHangs: Boolean = false,
        hangThresholdMs: Long = 2000,
    ): Env {
        val store = CrashStore(dir)
        val reporter = CrashReporter(
            CrashReporter.Config("av_test", Environment.Production, privacy, hangThresholdMs, detectHangs, maxStored),
            store, transport, InMemoryIdentityStore().also { it.save(identity) }, kv, device,
            InAppMatcher("com.tapll", emptyList()), sample, SdkLogger.silent, clock,
            CoroutineScope(SupervisorJob() + Dispatchers.Default), buildId, anr, mainThread, postToMain,
        )
        reporters.add(reporter)
        return Env(dir, store, transport, reporter, clock, kv)
    }

    private fun Env.reports(): List<Map<String, Any?>> = store.pending().map { store.read(it)!! }

    /** Wraps a stored report the way the upload does, so the schema validates exactly what goes on the wire. */
    private fun batchOf(env: Env, report: Map<String, Any?>, privacy: PrivacyMode = PrivacyMode.ProductAnalytics): String =
        Json.write(
            com.tapll.internal.crash.CrashReportBuilder.batch(
                java.util.UUID.randomUUID().toString(), Environment.Production, privacy, 1_700_000_000_000, Identity(), listOf(report),
            ),
        )

    private fun assertValid(json: String) {
        val errors = Schemas.crashErrors(json)
        assertEquals(errors.joinToString("\n"), emptyList<String>(), errors)
    }

    private fun Map<String, Any?>.threads() = this["threads"] as List<Map<String, Any?>>
    private fun Map<String, Any?>.frames(thread: Int = 0) = threads()[thread]["frames"] as List<Map<String, Any?>>

    private fun fail(): Throwable = IllegalStateException("boom for user@example.com")

    // region Fatal

    @Test fun anUncaughtExceptionIsWrittenSynchronouslyThenHandedToThePreviousHandler() {
        val seen = AtomicReference<Pair<Thread, Throwable>?>(null)
        Thread.setDefaultUncaughtExceptionHandler { t, e -> seen.set(t to e) }
        val e = env()
        e.reporter.start(sessionId = "0190aaaa-bbbb-7ccc-8ddd-eeeeffff1111")
        e.reporter.breadcrumb(BreadcrumbKind.Screen, "Checkout")
        e.reporter.setCustomKey("plan", "pro")

        val error = fail()
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)

        // On disk immediately: no waiting, nothing queued.
        val report = e.reports().single()
        assertSame("the previous handler still runs, exactly once, with the original throwable", error, seen.get()?.second)
        assertEquals("fatal", report["kind"])
        assertEquals(true, report["is_fatal"])
        assertEquals("android", report["platform"])
        assertEquals("java.lang.IllegalStateException", (report["exception"] as Map<*, *>)["type"])
        assertEquals("boom for user@example.com", (report["exception"] as Map<*, *>)["reason"])
        assertEquals("0190aaaa-bbbb-7ccc-8ddd-eeeeffff1111", report["session_id"])
        assertEquals(emptyList<Any>(), report["images"])
        assertEquals(1, report.threads().count { it["crashed"] == true })
        assertEquals(mapOf("plan" to "pro"), report["custom_keys"])
        assertEquals(listOf(mapOf("kind" to "screen", "name" to "Checkout")), (report["breadcrumbs"] as List<Map<String, Any?>>).map { it.filterKeys { k -> k != "ts" } })
        assertEquals("the state is sampled at the crash", true, (report["state"] as Map<*, *>)["in_foreground"])
        assertValid(batchOf(e, report))
    }

    @Test fun theHandlerNeverMasksTheOriginalCrashEvenWhenItCannotWrite() {
        val seen = AtomicReference<Throwable?>(null)
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen.set(e) }
        val blocker = tmp.newFile("not-a-directory")
        val e = env(dir = blocker)
        e.reporter.start(null)
        val error = fail()
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
        assertSame(error, seen.get())
    }

    @Test fun aCrashWhileReportingACrashDoesNotRecurse() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> calls.incrementAndGet() }
        val e = env()
        e.reporter.start(null)
        val handler = Thread.getDefaultUncaughtExceptionHandler()!!
        // A throwable whose stack trace blows up while the report is being built.
        val hostile = object : RuntimeException("x") {
            override fun getStackTrace(): Array<StackTraceElement> = throw IllegalStateException("nope")
        }
        handler.uncaughtException(Thread.currentThread(), hostile)
        assertEquals(1, calls.get())
        assertTrue(e.reports().isEmpty())
    }

    @Test fun stoppingRestoresThePreviousHandlerOnlyIfNobodyChainedOverUs() {
        val previous = Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler(previous)
        val a = env()
        a.reporter.start(null)
        a.reporter.stop()
        assertSame(previous, Thread.getDefaultUncaughtExceptionHandler())

        val b = env()
        b.reporter.start(null)
        val ours = Thread.getDefaultUncaughtExceptionHandler()!!
        val chained = Thread.UncaughtExceptionHandler { t, e -> ours.uncaughtException(t, e) }
        Thread.setDefaultUncaughtExceptionHandler(chained)
        b.reporter.stop()
        assertSame("someone chained over us: their handler stays", chained, Thread.getDefaultUncaughtExceptionHandler())
        // ... and our now-disabled handler no longer reports when they call it.
        ours.uncaughtException(Thread.currentThread(), fail())
        assertTrue(b.reports().isEmpty())
    }

    @Test fun theCauseChainTravelsAsExtraThreadsAndTheThrownTypeNamesTheIssue() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val e = env()
        e.reporter.start(null)
        val root = java.io.IOException("disk full")
        val wrapped = RuntimeException("save failed", IllegalStateException("bad state", root))
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), wrapped)
        val report = e.reports().single()
        assertEquals("java.lang.RuntimeException", (report["exception"] as Map<*, *>)["type"])
        val threads = report.threads()
        assertEquals(3, threads.size)
        assertEquals(listOf(true, false, false), threads.map { it["crashed"] })
        assertEquals(listOf("caused by java.lang.IllegalStateException", "caused by java.io.IOException"), threads.drop(1).map { it["name"] })
        assertTrue(report.frames(2).any { (it["function"] as String).contains("CrashTests.") })
        assertValid(batchOf(e, report))
    }

    @Test fun aSelfReferencingCauseChainTerminates() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val threads = JvmStacks.threadsOf(a, "main", InAppMatcher("x", emptyList()))
        assertEquals(2, threads.size)
    }

    @Test fun framesBecomeManagedFramesWithInAppFromThePackagePrefix() {
        val matcher = InAppMatcher("com.example.app", listOf("com.example.core."))
        val frame = JvmStacks.frame(StackTraceElement("com.example.app.ui.Checkout\$Pay", "onClick", "Checkout.kt", 42), matcher)
        assertEquals("com.example.app.ui.Checkout\$Pay.onClick", frame.function)
        assertEquals("Checkout.kt", frame.file)
        assertEquals(42, frame.line)
        assertTrue(frame.inApp)
        assertTrue(JvmStacks.frame(StackTraceElement("com.example.core.Net", "get", "Net.kt", 1), matcher).inApp)
        assertFalse(JvmStacks.frame(StackTraceElement("com.example.appx.Other", "run", "Other.kt", 1), matcher).inApp)
        assertFalse(JvmStacks.frame(StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 106), matcher).inApp)
        assertTrue(matcher.isInApp("com.example.app"))
        // Native (-2) and unknown (-1) lines are absent, not line 0 or negative.
        assertNull(JvmStacks.frame(StackTraceElement("a.B", "c", null, -2), matcher).line)
        assertNull(JvmStacks.frame(StackTraceElement("a.B", "c", null, -1), matcher).file)
    }

    @Test fun deepStacksAreCappedAt128FramesAndSayTheyWereCut() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val e = env()
        e.reporter.start(null)
        val deep = RuntimeException("deep")
        deep.stackTrace = Array(300) { StackTraceElement("com.tapll.Deep", "m$it", "Deep.kt", it + 1) }
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), deep)
        val report = e.reports().single()
        assertEquals(128, report.frames().size)
        assertEquals((0..127).toList(), report.frames().map { (it["n"] as Number).toInt() })
        assertEquals("innermost frames are kept", "com.tapll.Deep.m0", report.frames().first()["function"])
        assertEquals(true, report.threads()[0]["frames_truncated"])
        assertValid(batchOf(e, report))
    }

    @Test fun theMainThreadStackRidesAlongWhenAnotherThreadCrashed() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val main = Thread { Thread.sleep(2000) }.also { it.name = "fake-main"; it.isDaemon = true; it.start() }
        val e = env(mainThread = { main })
        e.reporter.start(null)
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), fail())
        val threads = e.reports().single().threads()
        assertEquals(listOf(true, false), threads.map { it["crashed"] })
        assertEquals("fake-main", threads[1]["name"])
        assertValid(batchOf(e, e.reports().single()))
        main.interrupt()
    }

    @Test fun contextIsFromTheCrashNotFromTheUpload() = runBlocking {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val dir = tmp.newFolder()
        val before = env(dir = dir, device = deviceA)
        before.reporter.start(null)
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), fail())
        before.reporter.stop()

        // The app was updated before the next launch uploaded the report.
        val after = env(dir = dir, device = deviceB, transport = StubTransport())
        after.reporter.flushPending()
        val sent = after.transport.sent.single()
        val report = (sent["reports"] as List<Map<String, Any?>>).single()
        assertEquals("2.4.0", (report["context"] as Map<*, *>)["app_version"])
        assertEquals(2L, sent["contract_version"])
        assertEquals("android", report["platform"])
        assertEquals("the batch carries the identity at upload time", "user_1", (sent["identity"] as Map<*, *>)["user_id"])
        assertTrue(after.store.pending().isEmpty())
    }

    // endregion

    // region Non-fatal

    @Test fun aNonFatalIsItsOwnSeverityAndNeverAFatal() {
        val e = env()
        e.reporter.start(null)
        e.reporter.recordError(java.io.IOException("timeout"))
        e.reporter.waitForPendingWrites()
        val report = e.reports().single()
        assertEquals("nonfatal", report["kind"])
        assertEquals(false, report["is_fatal"])
        assertEquals("java.io.IOException", (report["exception"] as Map<*, *>)["type"])
        assertNull(report["hang_duration_ms"])
        assertValid(batchOf(e, report))
    }

    @Test fun aNamedErrorCarriesTheCallersStackWithoutTheSdkFrames() {
        val e = env()
        e.reporter.start(null)
        e.reporter.recordError("PaymentFailed", "card_declined")
        e.reporter.waitForPendingWrites()
        val report = e.reports().single()
        assertEquals("PaymentFailed", (report["exception"] as Map<*, *>)["type"])
        assertEquals("card_declined", (report["exception"] as Map<*, *>)["reason"])
        val first = report.frames().first()["function"] as String
        assertTrue("the top of the stack is the caller, not CrashReporter: $first", first.startsWith("com.tapll.CrashTests.aNamedErrorCarriesTheCallersStack"))
        assertEquals(true, report.frames().first()["in_app"])
        assertValid(batchOf(e, report))
    }

    @Test fun anErrorAfterStopIsIgnored() {
        val e = env()
        e.reporter.start(null)
        e.reporter.stop()
        e.reporter.recordError("Late", null)
        e.reporter.recordError(RuntimeException("late"))
        e.reporter.recordManagedError("Late", null, listOf(ManagedFrame("f", inApp = true)), fatal = true, threadName = "main")
        e.reporter.waitForPendingWrites()
        assertTrue(e.reports().isEmpty())
    }

    @Test fun aNonFatalIsSentInProcessWithoutWaitingForARestart() {
        val e = env(transport = StubTransport())
        e.reporter.start(null)
        e.reporter.recordError("Quick", null)
        val deadline = System.currentTimeMillis() + 5000
        while (e.transport.bodies.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, e.transport.sent.size)
        e.reporter.waitForPendingWrites()
        runBlocking { e.reporter.flushPending() }
        assertTrue(e.store.pending().isEmpty())
    }

    // endregion

    // region Custom keys, breadcrumbs, privacy

    @Test fun strictAnonymousRefusesCustomKeysAndStripsAnyThatWereOnDisk() = runBlocking {
        val e = env(privacy = PrivacyMode.StrictAnonymous)
        e.reporter.setCustomKey("email", "a@b.c")
        e.reporter.start(null)
        e.reporter.recordError("Oops", null)
        e.reporter.waitForPendingWrites()
        assertNull(e.reports().single()["custom_keys"])

        // A report written under another privacy mode must not smuggle keys through the upload.
        val smuggled = e.reports().single() + ("custom_keys" to mapOf("email" to "a@b.c"))
        e.store.write("0190aaaa-bbbb-7ccc-8ddd-eeeeffff2222", smuggled)
        val online = env(dir = e.dir, privacy = PrivacyMode.StrictAnonymous, transport = StubTransport())
        online.reporter.flushPending()
        val bodies = online.transport.bodies.map { it.toString(Charsets.UTF_8) }
        assertEquals(2, bodies.size)
        bodies.forEach {
            assertFalse(it.contains("custom_keys"))
            assertEquals("{}", Json.write((Json.parseObjectOrNull(it)!!["identity"])))
            assertValid(it)
        }
    }

    @Test fun customKeysAreCappedTrimmedAndRemovable() {
        val e = env()
        e.reporter.start(null)
        for (i in 0 until 10) e.reporter.setCustomKey("key$i", "v$i") // key8 and key9 are over the cap of 8
        e.reporter.setCustomKey("k".repeat(60), "v".repeat(300)) // full: refused
        e.reporter.setCustomKey("key0", "changed") // an existing key can still change when full
        e.reporter.setCustomKey("key1", null) // removing frees a slot ...
        e.reporter.setCustomKey("k".repeat(60), "v".repeat(300)) // ... which the long one takes, trimmed
        e.reporter.setCustomKey("", "ignored")
        e.reporter.recordError("Keys", null)
        e.reporter.waitForPendingWrites()
        val keys = e.reports().single()["custom_keys"] as Map<String, String>
        assertEquals(CrashLimits.MAX_CUSTOM_KEYS, keys.size)
        assertEquals("changed", keys["key0"])
        assertFalse(keys.containsKey("key1"))
        assertFalse(keys.containsKey("key8"))
        assertEquals("v".repeat(256), keys["k".repeat(40)])
        assertTrue(keys.keys.all { it.length <= 40 } && keys.values.all { it.length <= 256 })
        assertValid(batchOf(e, e.reports().single()))
    }

    @Test fun breadcrumbsKeepTheNewestSixtyFourAsNamesOnly() {
        val e = env()
        e.reporter.start(null)
        for (i in 0 until 100) e.reporter.breadcrumb(if (i % 2 == 0) BreadcrumbKind.Event else BreadcrumbKind.Screen, "step_$i")
        e.reporter.breadcrumb(BreadcrumbKind.Log, "x".repeat(300))
        e.reporter.recordError("Trail", null)
        e.reporter.waitForPendingWrites()
        val crumbs = e.reports().single()["breadcrumbs"] as List<Map<String, Any?>>
        assertEquals(64, crumbs.size)
        assertEquals("step_37", crumbs.first()["name"])
        assertEquals(128, (crumbs.last()["name"] as String).length)
        assertTrue("a breadcrumb has nowhere to put a value", crumbs.all { it.keys == setOf("ts", "kind", "name") })
        assertValid(batchOf(e, e.reports().single()))
    }

    // endregion

    // region Upload

    @Test fun uploadOutcomeRules() {
        for (s in listOf(200, 201, 202, 204)) assertEquals(CrashUploadOutcome.Sent, CrashUploadOutcome.of(s))
        for (s in listOf(400, 413, 422)) assertTrue(CrashUploadOutcome.of(s) is CrashUploadOutcome.Rejected)
        for (s in listOf(401, 403)) assertEquals(CrashUploadOutcome.Stop, CrashUploadOutcome.of(s))
        for (s in listOf(500, 502, 503, 504, 429, 404, 0)) assertEquals("$s", CrashUploadOutcome.RetryLater, CrashUploadOutcome.of(s))
    }

    /** Writes reports straight into the store, with explicit file times, so ordering never depends on a race. */
    private fun seedFiles(e: Env, count: Int): List<String> = (1..count).map { i ->
        val id = "0190aaaa-bbbb-7ccc-8ddd-eeeeffff00%02d".format(i)
        val inputs = com.tapll.internal.crash.ReportInputs(
            crashId = id, kind = com.tapll.internal.crash.CrashKind.Fatal, crashedAt = 1_700_000_000_000L + i, sessionId = null,
            context = deviceA, state = null, exceptionType = "T$i", exceptionReason = null,
            threads = listOf(com.tapll.internal.crash.ThreadDump("main", true, listOf(com.tapll.internal.crash.StackFrameData("f$i", inApp = true)))),
        )
        e.store.write(id, com.tapll.internal.crash.CrashReportBuilder.build(inputs))
        e.store.reportFile(id).setLastModified(1_700_000_000_000L + i * 10_000L)
        id
    }

    @Test fun onlyAcceptanceOrAPermanentRejectionClearsAReport() = runBlocking {
        val transport = StubTransport(listOf(Responses.status(500), Responses.status(400), Responses.accepted()))
        val e = env(transport = transport)
        seedFiles(e, 3)
        e.reporter.flushPending()
        assertEquals(3, transport.sent.size)
        assertEquals("only the 500 stays on disk", 1, e.store.pending().size)
        assertEquals("T1", ((e.reports().single()["exception"]) as Map<*, *>)["type"])
    }

    @Test fun anAuthRefusalStopsTheWalkAndKeepsEveryFile() = runBlocking {
        val transport = StubTransport(listOf(Responses.status(401)))
        val e = env(transport = transport)
        seedFiles(e, 3)
        e.reporter.flushPending()
        assertEquals(1, transport.sent.size)
        assertEquals(3, e.store.pending().size)
    }

    @Test fun aNetworkErrorKeepsTheReport() = runBlocking {
        val transport = StubTransport().also { it.failWithNetworkError = true }
        val e = env(transport = transport)
        seedFiles(e, 1)
        e.reporter.flushPending()
        assertEquals(1, e.store.pending().size)
    }

    @Test fun reportsAreSentOneAtATimeOldestFirstAndEveryBodyConformsToTheContract() = runBlocking {
        val e = env(transport = StubTransport(), buildId = "9f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f")
        val ids = seedFiles(e, 3)
        // Rewrite one with a build id the way the live path does, to see it travel.
        e.reporter.flushPending()
        assertEquals(listOf("T1", "T2", "T3"), e.transport.sent.map { (((it["reports"] as List<Map<String, Any?>>).single())["exception"] as Map<*, *>)["type"] })
        e.transport.bodies.forEach { assertValid(it.toString(Charsets.UTF_8)) }
        assertEquals(ids.size, e.transport.sent.size)
    }

    @Test fun theBuildIdTravelsInTheReport() {
        val e = env(buildId = "9f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f")
        e.reporter.start(null)
        e.reporter.recordError("WithBuildId", null)
        e.reporter.waitForPendingWrites()
        assertEquals("9f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f", e.reports().single()["build_id"])
        assertValid(batchOf(e, e.reports().single()))
    }

    @Test fun anUnreadableFileIsDiscardedNotRetriedForever() = runBlocking {
        val e = env()
        File(e.dir, "garbage.report.json").writeText("{not json")
        e.reporter.flushPending()
        assertTrue(e.store.pending().isEmpty())
        assertTrue(e.transport.sent.isEmpty())
    }

    @Test fun theBuildIdIsOptionalAndOnlyAcceptedInTheContractsAlphabet() {
        assertEquals("9f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f", BuildId.parse(" 9f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f\n"))
        assertNull(BuildId.parse("NOT-LOWERCASE-OR-HEX"))
        assertNull(BuildId.parse(""))
        assertNull(BuildId.parse("abc"))
        val e = env(buildId = null)
        e.reporter.start(null)
        e.reporter.recordError("NoBuildId", null)
        e.reporter.waitForPendingWrites()
        assertFalse(e.reports().single().containsKey("build_id"))
    }

    @Test fun theStoreKeepsTheNewestMaxStoredReportsAndOptOutTakesAll() {
        val e = env(maxStored = 3)
        e.reporter.start(null)
        seedFiles(e, 6)
        e.store.trim(3)
        assertEquals(3, e.store.pending().size)
        assertEquals("the oldest fall out first", listOf("T4", "T5", "T6"), e.reports().map { (it["exception"] as Map<*, *>)["type"] })
        e.reporter.wipe()
        e.reporter.waitForPendingWrites()
        assertTrue(e.store.pending().isEmpty())
    }

    // endregion

    // region Wrapper bridge

    @Test fun aManagedNonFatalValidatesAndCarriesNoImages() {
        val e = env()
        e.reporter.start(null)
        e.reporter.recordManagedError(
            "FormatException", "Unexpected character",
            listOf(
                ManagedFrame("_CheckoutState.build", module = "package:shop/checkout.dart", file = "checkout.dart", line = 42, column = 7, inApp = true),
                ManagedFrame("", line = 0, inApp = false),
            ),
            fatal = false, threadName = "main",
        )
        e.reporter.waitForPendingWrites()
        val report = e.reports().single()
        assertEquals("nonfatal", report["kind"])
        assertEquals(false, report["is_fatal"])
        assertEquals(emptyList<Any>(), report["images"])
        assertEquals(listOf(0, 1), report.frames().map { (it["n"] as Number).toInt() })
        assertEquals(42L, report.frames()[0]["line"])
        assertEquals(7L, report.frames()[0]["column"])
        assertEquals("<anonymous>", report.frames()[1]["function"])
        assertFalse("a zero line is unknown, not line 0", report.frames()[1].containsKey("line"))
        assertValid(batchOf(e, report))
    }

    @Test fun aManagedFatalIsOnDiskImmediatelyAndUploadsOnTheNextLaunch() = runBlocking {
        val dir = tmp.newFolder()
        val first = env(dir = dir)
        first.reporter.start(null)
        first.reporter.recordManagedError("TypeError", "undefined is not a function", listOf(ManagedFrame("onPress", file = "App.js", line = 10, column = 3, inApp = true)), fatal = true, threadName = "js")
        // No waiting: the runtime is about to take the process down.
        val report = first.reports().single()
        assertEquals("fatal", report["kind"])
        assertEquals(1, report.threads().count { it["crashed"] == true })
        assertEquals("js", report.threads()[0]["name"])
        assertValid(batchOf(first, report))
        first.reporter.stop()

        val second = env(dir = dir, transport = StubTransport())
        second.reporter.flushPending()
        val sent = second.transport.sent.single()
        assertEquals(2L, sent["contract_version"])
        assertEquals("onPress", ((sent["reports"] as List<Map<String, Any?>>).single().let { (it["threads"] as List<Map<String, Any?>>)[0]["frames"] as List<Map<String, Any?>> })[0]["function"])
        assertTrue(second.store.pending().isEmpty())
    }

    @Test fun aManagedReportWithABlankTypeStillNamesItsFailure() {
        val e = env()
        e.reporter.start(null)
        e.reporter.recordManagedError("  ", null, listOf(ManagedFrame("f", inApp = true)), fatal = true, threadName = "main")
        val type = (e.reports().single()["exception"] as Map<*, *>)["type"] as String
        assertEquals("a report with managed frames needs exception.type", "Error", type)
    }

    @Test fun managedStacksAreCappedAndSaySo() {
        val e = env()
        e.reporter.start(null)
        e.reporter.recordManagedError("Deep", null, (0 until 133).map { ManagedFrame("f$it", inApp = false) }, fatal = false, threadName = "main")
        e.reporter.waitForPendingWrites()
        val report = e.reports().single()
        assertEquals(128, report.frames().size)
        assertEquals(true, report.threads()[0]["frames_truncated"])
        assertValid(batchOf(e, report))
    }

    // endregion

    // region Hangs and ANRs

    @Test fun theWatchdogReportsABlockedMainThreadOnceWithTheDurationItMeasured() {
        val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val reports = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Int>>()
        val stack = arrayOf(StackTraceElement("com.example.Slow", "work", "Slow.kt", 10))
        val dog = HangWatchdog(thresholdMs = 200, tickMs = 20, postToMain = { queue.add(it) }, mainStack = { stack }, isForeground = { true }) { d, s -> reports.add(d to s.size) }
        dog.start()
        Thread.sleep(700) // the "main thread" never drains its queue: blocked
        assertTrue("nothing is reported while the episode is still going", reports.isEmpty())
        while (true) (queue.poll() ?: break).run() // ... and now it answers
        val deadline = System.currentTimeMillis() + 3000
        while (reports.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, reports.size)
        val (duration, frames) = reports.single()
        assertTrue("measured $duration ms, not the 200 ms threshold", duration in 500..2500)
        assertEquals(1, frames)
        // A healthy main thread afterwards reports nothing more.
        repeat(20) { Thread.sleep(25); while (true) (queue.poll() ?: break).run() }
        assertEquals("one episode is one hang, not one per tick", 1, reports.size)
        dog.stop()
    }

    @Test fun theWatchdogStaysQuietInTheBackground() {
        val posted = java.util.concurrent.atomic.AtomicInteger()
        val dog = HangWatchdog(thresholdMs = 50, tickMs = 10, postToMain = { posted.incrementAndGet() }, mainStack = { emptyArray() }, isForeground = { false }) { _, _ -> error("no hang in the background") }
        dog.start()
        Thread.sleep(300)
        dog.stop()
        assertEquals(0, posted.get())
    }

    @Test fun aHangReportIsItsOwnKindWithAMeasuredDuration() {
        val main = Thread { Thread.sleep(2000) }.also { it.name = "main"; it.isDaemon = true; it.start() }
        val e = env(mainThread = { main })
        e.reporter.start(null)
        e.reporter.reportHang(2750, arrayOf(StackTraceElement("com.tapll.Slow", "work", "Slow.kt", 10)))
        e.reporter.waitForPendingWrites()
        val report = e.reports().single()
        assertEquals("hang", report["kind"])
        assertEquals(false, report["is_fatal"])
        assertEquals(2750L, report["hang_duration_ms"])
        assertEquals("MainThreadHang", (report["exception"] as Map<*, *>)["type"])
        assertEquals("main", report.threads().single()["name"])
        assertEquals(true, report.threads().single()["crashed"])
        assertValid(batchOf(e, report))
        main.interrupt()
    }

    private val anrText = """
        ----- pid 4242 at 2026-10-23 10:00:00.000 -----
        Cmd line: com.example.app

        "main" prio=5 tid=1 Blocked
          | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72c1a2b8 self=0xb400007c1a2b8
          native: #00 pc 0000000000053f4c  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+28)
          at java.lang.Object.wait(Native Method)
          at java.lang.Thread.join(Thread.java:1308)
          at com.tapll.checkout.Pay.waitForLock(Pay.kt:88)
          at android.os.Handler.dispatchMessage(Handler.java:106)
          at android.os.Looper.loop(Looper.java:223)
          at android.app.ActivityThread.main(Unknown Source:5)

        "Binder:4242_1" prio=5 tid=7 Native
          at should.not.Appear.method(Appear.java:1)
    """.trimIndent()

    @Test fun anAnrTraceIsReadDownToTheMainThreadFrames() {
        val (frames, truncated) = AnrTrace.mainThreadFrames(anrText, InAppMatcher("com.tapll", emptyList()))
        assertFalse(truncated)
        assertEquals(
            listOf("java.lang.Object.wait", "java.lang.Thread.join", "com.tapll.checkout.Pay.waitForLock", "android.os.Handler.dispatchMessage", "android.os.Looper.loop", "android.app.ActivityThread.main"),
            frames.map { it.function },
        )
        assertEquals(listOf(false, false, true, false, false, false), frames.map { it.inApp })
        assertEquals(88, frames[2].line)
        assertEquals("Pay.kt", frames[2].file)
        assertNull("a native method has no line", frames[0].line)
        assertNull("Unknown Source has no file", frames[5].file)
        assertEquals(5, frames[5].line)
        assertTrue(AnrTrace.mainThreadFrames("nothing useful here", InAppMatcher("x", emptyList())).first.isEmpty())
    }

    @Test fun anrsAreReportedOnceNewestWatermarkOnlyAndWithTheContextOfTheLaunchThatDied() {
        val exits = java.util.concurrent.CopyOnWriteArrayList<AnrExit>()
        val kv = InMemoryKeyValueStore()
        val clock = TestClock(1_700_000_100_000)
        // First launch ever: an old ANR from before the SDK existed is not ours to report.
        exits.add(AnrExit(1_699_000_000_000, "old", anrText))
        val first = env(kv = kv, clock = clock, anr = AnrSource { exits.toList() })
        first.reporter.collectAnrs(null)
        assertTrue(first.store.pending().isEmpty())
        assertEquals("1699000000000", kv.string(CrashReporter.LAST_ANR_KEY))

        // A new ANR since: reported with the previous launch's context, not this one's.
        exits.add(AnrExit(1_700_000_050_000, "Input dispatching timed out", anrText))
        val second = env(kv = kv, clock = clock, device = deviceB, anr = AnrSource { exits.toList() })
        second.reporter.collectAnrs(deviceA)
        val report = second.reports().single()
        assertEquals("anr", report["kind"])
        assertEquals(false, report["is_fatal"])
        assertEquals(1_700_000_050_000L, report["crashed_at"])
        assertEquals("ApplicationNotResponding", (report["exception"] as Map<*, *>)["type"])
        assertEquals("Input dispatching timed out", (report["exception"] as Map<*, *>)["reason"])
        assertEquals("2.4.0", (report["context"] as Map<*, *>)["app_version"])
        assertNull(report["hang_duration_ms"])
        assertNull("nothing sampled the device at the time", report["state"])
        assertNull(report["session_id"])
        assertTrue(report.frames().first()["function"] == "java.lang.Object.wait")
        assertValid(batchOf(second, report))

        // The same exit is not reported twice.
        val third = env(kv = kv, clock = clock, anr = AnrSource { exits.toList() })
        third.reporter.collectAnrs(null)
        assertTrue(third.store.pending().isEmpty())
    }

    // endregion

    // region Misc

    @Test fun theRootHeuristicIsOnlyASample() {
        assertFalse(RootHeuristic.looksRooted(tags = "release-keys", exists = { false }))
        assertTrue(RootHeuristic.looksRooted(tags = "test-keys", exists = { false }))
        assertTrue(RootHeuristic.looksRooted(tags = "release-keys", exists = { it == "/system/xbin/su" }))
        assertFalse("a throwing probe is not a verdict", RootHeuristic.looksRooted(tags = null, exists = { error("denied") }))
    }

    // endregion

    // region Transports (loopback only; the rest of the suite uses fakes)

    private class Received(val headers: Map<String, String>, val body: ByteArray)

    /**
     * A one-connection-at-a-time HTTP/1.1 responder on loopback. `com.sun.net.httpserver` is not on the
     * Android compile classpath, and a real socket is the only way to see what `HttpURLConnection`
     * actually puts on the wire (the gzip header, the fixed length, the key).
     */
    private class Server(val status: Int, val response: String, val retryAfter: String?, val onRequest: (Received) -> Unit) {
        private val socket = java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}/v1/crash"
        private val thread = Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { c -> handle(c) }
                } catch (_: java.io.IOException) {
                    return@Thread
                }
            }
        }.also { it.isDaemon = true; it.start() }

        private fun handle(c: java.net.Socket) {
            val input = java.io.BufferedInputStream(c.getInputStream())
            fun line(): String {
                val sb = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b < 0 || b == '\n'.code) break
                    if (b != '\r'.code) sb.append(b.toChar())
                }
                return sb.toString()
            }
            line() // request line
            val headers = HashMap<String, String>()
            while (true) {
                val l = line()
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val length = headers["content-length"]?.toInt() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            onRequest(Received(headers, body))
            val bytes = response.toByteArray()
            val out = c.getOutputStream()
            val head = StringBuilder("HTTP/1.1 $status X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
            retryAfter?.let { head.append("Retry-After: $it\r\n") }
            head.append("\r\n")
            out.write(head.toString().toByteArray())
            out.write(bytes)
            out.flush()
        }

        fun stop() = socket.close()
    }

    private fun serve(status: Int, response: String = "{}", retryAfter: String? = null, block: (Received) -> Unit): Pair<Server, String> {
        val s = Server(status, response, retryAfter, block)
        return s to s.url
    }

    @Test fun theCrashTransportSendsAGzipBodyWithTheKeyAndTheUserAgent() = runBlocking {
        val got = AtomicReference<Received?>(null)
        val (server, url) = serve(202) { got.set(it) }
        try {
            val body = Json.writeBytes(mapOf("hello" to "world"))
            val response = HttpTransport(url, "tapll-android/0.1.0 flutter/0.2.0", gzip = true).send(body, "av_key")
            assertEquals(202, response.status)
            val r = got.get()!!
            assertEquals("gzip", r.headers["content-encoding"])
            assertEquals("av_key", r.headers["x-tapll-key"])
            assertEquals("tapll-android/0.1.0 flutter/0.2.0", r.headers["user-agent"])
            assertEquals("application/json", r.headers["content-type"])
            assertEquals(r.body.size.toString(), r.headers["content-length"])
            assertEquals(String(body), String(GZIPInputStream(r.body.inputStream()).readBytes()))
        } finally {
            server.stop()
        }
    }

    @Test fun theEventTransportSendsPlainJsonAndParsesRetryAfterAndErrorBodies() = runBlocking {
        val got = AtomicReference<Received?>(null)
        val (server, url) = serve(429, """{"error":"rate_limited","message":"slow"}""", retryAfter = "42") { got.set(it) }
        try {
            val response = HttpTransport(url, "tapll-android/0.1.0", gzip = false).send("""{"a":1}""".toByteArray(), "av_key")
            assertEquals(429, response.status)
            assertEquals(42.0, response.retryAfterSeconds)
            assertTrue(response.bodyText.contains("rate_limited"))
            assertNull(got.get()!!.headers["content-encoding"])
            assertEquals("""{"a":1}""", String(got.get()!!.body))
        } finally {
            server.stop()
        }
    }

    @Test fun aRefusedConnectionThrowsSoThePipelineCanBackOff() {
        val failed = try {
            runBlocking { HttpTransport("http://127.0.0.1:1/v1/batch", "ua", gzip = false, timeoutMs = 500).send(ByteArray(0), "k") }
            false
        } catch (e: java.io.IOException) {
            true
        }
        assertTrue(failed)
    }

    // endregion
}
