package com.tapll.internal

import com.tapll.FeatureRole
import com.tapll.PrivacyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class PipelineTests {
    @get:Rule val tmp = TemporaryFolder()

    private fun assertSchemaOk(body: ByteArray) {
        val errors = Schemas.eventErrors(body)
        assertEquals(errors.joinToString("\n"), emptyList<String>(), errors)
    }

    private fun EventPipeline.start0() = start()

    // region Cold start, conformance

    @Test fun coldStartEmitsInstallAndSessionStartInTheDocumentedOrderAndBatchConformsToSchema() = runBlocking {
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport)
        p.start()
        p.track("workout_started", mapOf("plan" to "strength", "minutes" to 30), role = FeatureRole.Start, screen = "WorkoutDetail")
        p.flush()

        assertEquals(1, transport.sent.size)
        val batch = transport.sent[0]
        // $first_open / $app_install go BEFORE $session_start of their own session: they already carry its session_id.
        assertEquals(listOf("\$first_open", "\$app_install", "\$session_start", "workout_started"), batch.names())
        assertEquals(1, batch.events().map { it["session_id"] }.toSet().size)
        assertNotNull(batch.obj("identity")["install_id"])
        assertEquals(1L, batch["contract_version"])
        assertEquals("Android", batch.obj("device")["os_name"])
        assertSchemaOk(transport.bodies[0])
        assertTrue("acknowledged events leave the queue", p.queuedEvents.isEmpty())
    }

    @Test fun appInstallNeverCarriesReinstallOnAndroid() = runBlocking {
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport)
        p.start()
        p.flush()
        val install = transport.sent[0].events().first { it["name"] == "\$app_install" }
        assertNull("nothing survives an uninstall on Android, so reinstall is never sent", install["properties"])
    }

    @Test fun anUpdatedAppEmitsAppUpdateWithThePreviousVersionBeforeSessionStart() = runBlocking {
        val kv = InMemoryKeyValueStore()
        kv.set("last_app_version", "2.3.0")
        kv.set("last_app_build", "230")
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport, kv = kv)
        p.start()
        p.flush()
        val events = transport.sent[0].events()
        assertEquals(listOf("\$app_update", "\$session_start"), events.map { it["name"] })
        assertEquals(mapOf("previous_version" to "2.3.0", "previous_build" to "230"), events[0]["properties"])
        assertSchemaOk(transport.bodies[0])
    }

    @Test fun aSecondLaunchWithTheSameVersionEmitsNoInstallEvents() = runBlocking {
        val kv = InMemoryKeyValueStore()
        val clock = TestClock()
        Fixtures.pipeline(clock = clock, kv = kv).start()
        val transport = StubTransport()
        val p = Fixtures.pipeline(clock = clock, kv = kv, transport = transport)
        p.start()
        p.flush()
        // The session continued (inside the 30 minute window) and nothing else happened.
        assertTrue(transport.sent.isEmpty())
    }

    // endregion

    // region Privacy

    @Test fun strictAnonymousSendsEmptyIdentityAndIgnoresIdentify() = runBlocking {
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport, privacy = PrivacyMode.StrictAnonymous)
        p.start()
        p.identify("u1", mapOf("email" to "a@b.c"))
        p.flush()
        val json = transport.bodies[0].toString(Charsets.UTF_8)
        assertEquals(emptyMap<String, Any?>(), transport.sent[0].obj("identity"))
        assertTrue(json, json.contains("\"identity\":{}"))
        assertFalse(json.contains("install_id"))
        assertFalse(json.contains("traits"))
        assertFalse(json.contains("user_id"))
        assertEquals("strict_anonymous", transport.sent[0]["privacy_mode"])
        assertSchemaOk(transport.bodies[0])
    }

    @Test fun strictAnonymousExposesNoInstallIdAndWipesWhatAnEarlierModeLeft() {
        val store = InMemoryIdentityStore()
        store.save(Identity(installId = "left-behind", userId = "u", traits = mapOf("a" to "b")))
        val coordinator = IdentityCoordinator(store, strict = true)
        assertNull(coordinator.ensure(0).installId)
        assertEquals(Identity(), store.load())
    }

    @Test fun mintedInstallIdParsesAsAUuidSoPlayBillingAndBackendsCanUseIt() {
        val coordinator = IdentityCoordinator(InMemoryIdentityStore(), strict = false)
        val id = coordinator.ensure(1_700_000_000_000).installId!!
        assertEquals(id, UUID.fromString(id).toString())
        assertEquals(id, coordinator.ensure(1).installId)
    }

    @Test fun constructingThePipelineTouchesNoStorage() {
        val identity = CountingIdentityStore()
        Fixtures.pipeline(identity = identity)
        assertEquals(0, identity.loads)
        assertEquals(0, identity.saves)
    }

    @Test fun theInstallIdIsReadFromStorageOnlyOnce() {
        val identity = CountingIdentityStore()
        val coordinator = IdentityCoordinator(identity, strict = false)
        repeat(5) { coordinator.ensure(1) }
        assertEquals(1, identity.loads)
    }

    @Test fun optOutWipesAndStopsAndSurvivesARelaunch() = runBlocking {
        val kv = InMemoryKeyValueStore()
        val identity = InMemoryIdentityStore()
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport, kv = kv, identity = identity)
        p.start()
        p.track("a_one", emptyMap())
        p.optOut()
        p.track("a_two", emptyMap())
        p.flush()
        assertTrue(transport.sent.isEmpty())
        assertTrue(p.queuedEvents.isEmpty())
        assertEquals(Identity(), identity.load())

        val relaunched = Fixtures.pipeline(transport = transport, kv = kv, identity = identity)
        relaunched.start()
        relaunched.track("a_three", emptyMap())
        relaunched.flush()
        assertTrue("opt-out persists across launches", transport.sent.isEmpty())

        relaunched.optIn()
        relaunched.track("a_four", emptyMap())
        relaunched.flush()
        assertEquals(1, transport.sent.size)
        assertTrue(transport.sent[0].names().contains("a_four"))
        assertNotNull("opt-in mints a fresh install id", transport.sent[0].obj("identity")["install_id"])
    }

    @Test fun disablingAnalyticsWipesEventsButCanBeEnabledAgain() = runBlocking {
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport)
        p.start()
        p.track("a_one", emptyMap())
        p.setAnalyticsCollectionEnabled(false)
        assertTrue(p.queuedEvents.isEmpty())
        assertFalse(p.hasTimer)
        p.track("a_two", emptyMap())
        p.flush()
        assertTrue(transport.sent.isEmpty())

        p.setAnalyticsCollectionEnabled(true)
        p.track("a_three", emptyMap())
        p.flush()
        val names = transport.sent.flatMap { it.names() }
        assertFalse(names.contains("a_one"))
        assertFalse(names.contains("a_two"))
        assertTrue(names.contains("a_three"))
    }

    // endregion

    // region Validation

    @Test fun invalidEventsAreDroppedLocallyAndNeverSent() = runBlocking {
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport)
        p.track("BadName", emptyMap())
        p.track("\$session_start", emptyMap())
        p.track("ok_name", mapOf("nested" to listOf(1)))
        p.track("ok_name", mapOf("nan" to Double.NaN))
        p.track("ok_name", (0..32).associate { "k$it" to 1 })
        p.track("ok_name", mapOf("long" to "x".repeat(257)))
        p.track("ok_name", emptyMap(), screen = "s".repeat(129))
        p.flush()
        assertTrue(transport.sent.isEmpty())
        assertTrue(p.queuedEvents.isEmpty())
    }

    @Test fun propertyNumbersKeepIntegersWithoutAFraction() = runBlocking {
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport)
        p.track("measured", mapOf("minutes" to 30, "ratio" to 0.5, "ok" to true, "big" to (1L shl 40), "whole" to 3.0))
        p.flush()
        val json = transport.bodies[0].toString(Charsets.UTF_8)
        assertTrue(json, json.contains("\"minutes\":30"))
        assertTrue(json, json.contains("\"ratio\":0.5"))
        assertTrue(json, json.contains("\"ok\":true"))
        assertTrue(json, json.contains("\"whole\":3,") || json.contains("\"whole\":3}"))
        assertSchemaOk(transport.bodies[0])
    }

    @Test fun declaredFeatureTravelsOnTheEventAndTheScreenViewAndConformsToSchema() = runBlocking {
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport)
        p.start()
        p.screen("Checkout", mapOf("step" to 1), feature = "checkout")
        p.track("checkout_started", emptyMap(), feature = "checkout", role = FeatureRole.Start)
        p.track("order_placed", mapOf("total" to 49.9), feature = "checkout", role = FeatureRole.Success)
        p.flush()
        val events = transport.sent[0].events()
        val screen = events.first { it["name"] == "\$screen_view" }
        assertEquals("checkout", screen["feature"])
        assertEquals("discovery", screen["role"])
        assertEquals("Checkout", screen["screen"])
        assertEquals("success", events.first { it["name"] == "order_placed" }["role"])
        assertSchemaOk(transport.bodies[0])
    }

    @Test fun anInvalidFeatureKeyDropsTheEventInsteadOfStrippingTheKey() = runBlocking {
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport)
        p.track("checkout_started", emptyMap(), feature = "Checkout", role = FeatureRole.Start)
        p.screen("Checkout", emptyMap(), feature = "bad key")
        p.flush()
        assertTrue("an event stripped of its feature would read as part of no feature", transport.sent.isEmpty())
    }

    @Test fun screenViewCarriesTheScreenNameAndItsProperties() = runBlocking {
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport)
        p.screen("WorkoutDetail", mapOf("source" to "push"))
        p.flush()
        val e = transport.sent[0].events().first { it["name"] == "\$screen_view" }
        assertEquals("WorkoutDetail", e["screen"])
        assertEquals(mapOf("source" to "push"), e["properties"])
        assertNull(e["feature"])
    }

    @Test fun deepLinkReportsSchemeHostAndPath() = runBlocking {
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport)
        p.deepLink("myapp", "workouts", "/42")
        p.deepLink("mailto", null, null)
        p.flush()
        val links = transport.sent[0].events().filter { it["name"] == "\$deep_link" }
        assertEquals(mapOf("url_scheme" to "myapp", "host" to "workouts", "path" to "/42"), links[0]["properties"])
        assertEquals(mapOf("url_scheme" to "mailto", "path" to ""), links[1]["properties"])
        assertSchemaOk(transport.bodies[0])
    }

    // endregion

    // region Response policy

    @Test fun partialRejectionDropsOnlyFlaggedEventsAndRetriesTheRestWithTheSameIds() = runBlocking {
        val transport = StubTransport(
            listOf(
                Responses.error(
                    400,
                    """{"error":"invalid_batch","message":"bad","details":[{"path":"events.1.properties.x","message":"no"},{"path":"events.2.name","message":"no"}]}""",
                ),
            ),
        )
        val p = Fixtures.pipeline(transport = transport)
        for (n in listOf("a_one", "a_two", "a_three", "a_four")) p.track(n, emptyMap())
        val ids = p.queuedEvents.associate { it.name to it.eventId }
        p.flush()
        assertEquals(2, transport.sent.size)
        // Indices 1 and 2 of [$session_start, a_one, a_two, a_three, a_four] are a_one and a_two.
        assertEquals(listOf("\$session_start", "a_three", "a_four"), transport.sent[1].names())
        assertEquals(listOf(ids["\$session_start"], ids["a_three"], ids["a_four"]), transport.sent[1].events().map { it["event_id"] })
        assertTrue(p.queuedEvents.isEmpty())
    }

    @Test fun anEnvelopeProblemDropsTheWholeBatch() = runBlocking {
        val transport = StubTransport(listOf(Responses.error(400, """{"error":"invalid_batch","message":"env","details":[{"path":"environment","message":"no"},{"path":"events.0.name","message":"no"}]}""")))
        val p = Fixtures.pipeline(transport = transport)
        p.track("a_one", emptyMap())
        p.flush()
        assertEquals(1, transport.sent.size)
        assertTrue(p.queuedEvents.isEmpty())
    }

    @Test fun aPrivacyViolationAndAnUnparseable400DropTheBatch() = runBlocking {
        val transport = StubTransport(listOf(Responses.error(400, """{"error":"privacy_violation","message":"identity in strict"}"""), Responses.error(400, "not json")))
        val p = Fixtures.pipeline(transport = transport)
        p.track("a_one", emptyMap())
        p.flush()
        p.track("a_two", emptyMap())
        p.flush()
        assertEquals(2, transport.sent.size)
        assertTrue(p.queuedEvents.isEmpty())
        assertFalse(p.isStopped)
    }

    @Test fun unauthorizedStopsTheSdkForThisLaunch() = runBlocking {
        val transport = StubTransport(listOf(Responses.error(401, """{"error":"invalid_write_key","message":"unknown"}""")))
        val p = Fixtures.pipeline(transport = transport)
        p.track("a_one", emptyMap())
        p.flush()
        assertTrue(p.isStopped)
        p.track("a_two", emptyMap())
        p.flush()
        assertEquals("nothing is sent after a 401", 1, transport.sent.size)
    }

    @Test fun rateLimitPausesForRetryAfterAndThenResumes() = runBlocking {
        val clock = TestClock()
        val transport = StubTransport(listOf(Responses.error(429, """{"error":"rate_limited","message":"slow"}""", retryAfter = 60.0)))
        val p = Fixtures.pipeline(clock = clock, transport = transport)
        p.track("a_one", emptyMap())
        p.flush()
        assertTrue(p.isPaused)
        p.flush()
        assertEquals("no send while paused", 1, transport.sent.size)
        clock.advanceSeconds(61.0)
        p.flush()
        assertFalse(p.isPaused)
        assertEquals(2, transport.sent.size)
    }

    @Test fun rateLimitWithoutRetryAfterPausesFiveMinutes() = runBlocking {
        val clock = TestClock()
        val transport = StubTransport(listOf(Responses.status(429)))
        val p = Fixtures.pipeline(clock = clock, transport = transport)
        p.track("a_one", emptyMap())
        p.flush()
        clock.advanceSeconds(299.0)
        p.flush()
        assertEquals(1, transport.sent.size)
        clock.advanceSeconds(2.0)
        p.flush()
        assertEquals(2, transport.sent.size)
    }

    @Test fun aDisabledProjectPausesForAnHourAndKeepsTheQueue() = runBlocking {
        val clock = TestClock()
        val transport = StubTransport(listOf(Responses.error(403, """{"error":"project_disabled","message":"off"}""")))
        val p = Fixtures.pipeline(clock = clock, transport = transport)
        p.track("a_one", emptyMap())
        p.flush()
        assertTrue(p.isPaused)
        assertEquals(2, p.queuedEvents.size)
        clock.advanceSeconds(3599.0)
        p.flush()
        assertEquals(1, transport.sent.size)
        clock.advanceSeconds(2.0)
        p.flush()
        assertEquals(2, transport.sent.size)
        assertTrue(p.queuedEvents.isEmpty())
    }

    @Test fun tooLargeHalvesTheLimitInsteadOfDroppingTheBatch() = runBlocking {
        val transport = StubTransport(List(5) { Responses.status(413) })
        val p = Fixtures.pipeline(transport = transport)
        repeat(8) { p.track("e_$it", emptyMap()) }
        p.flush()
        // The cap halves from 100 on every 413 (100, 50, 25, 12, 6, 3), so a 9-event queue is only
        // actually cut from the fourth refusal on; the first accepted batch resets the cap.
        assertEquals(listOf(9, 9, 9, 9, 6, 3, 6), transport.sent.map { it.events().size })
        assertTrue(p.queuedEvents.isEmpty())
        assertEquals("no event is lost to a split", 9, transport.sent.takeLast(2).sumOf { it.events().size })
    }

    @Test fun aSingleEventTooLargeIsDroppedRatherThanBlockingTheQueue() = runBlocking {
        val transport = StubTransport(List(10) { Responses.status(413) })
        val p = Fixtures.pipeline(transport = transport)
        p.track("a_one", emptyMap())
        p.track("a_two", emptyMap())
        p.flush()
        assertTrue("both events are dropped, none blocks the queue", p.queuedEvents.isEmpty())
        assertFalse(p.isStopped)
    }

    @Test fun networkErrorKeepsTheQueueAndBacksOff() = runBlocking {
        val clock = TestClock()
        val transport = StubTransport().also { it.failWithNetworkError = true }
        val p = Fixtures.pipeline(clock = clock, transport = transport)
        p.track("a_one", emptyMap())
        p.flush()
        assertTrue("nothing is lost on network failure", p.queuedEvents.any { it.name == "a_one" })
        assertEquals(1, transport.sent.size)
        assertTrue(p.isPaused)
        // First failure: 2 s base, jittered to 1-2 s.
        clock.advanceSeconds(2.1)
        assertFalse(p.isPaused)
    }

    @Test fun serverErrorsBackOffAndTheRetryKeepsTheEventIds() = runBlocking {
        val clock = TestClock()
        val transport = StubTransport(listOf(Responses.status(503), Responses.status(500)))
        val p = Fixtures.pipeline(clock = clock, transport = transport)
        p.track("a_one", emptyMap())
        val id = p.queuedEvents.first { it.name == "a_one" }.eventId
        p.flush()
        assertTrue(p.isPaused)
        clock.advanceSeconds(2.1)
        p.flush()
        assertTrue("the second failure backs off for longer", p.isPaused)
        clock.advanceSeconds(4.1)
        assertFalse(p.isPaused)
        p.flush()
        assertEquals(3, transport.sent.size)
        assertEquals(listOf(id), transport.sent.map { b -> b.events().first { it["name"] == "a_one" }["event_id"] }.distinct())
        assertTrue(p.queuedEvents.isEmpty())
    }

    @Test fun backoffDoublesFromTwoSecondsToFiveMinutesWithJitter() {
        assertEquals(listOf(2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0, 256.0, 300.0, 300.0), (1..10).map { Backoff.baseSeconds(it) })
        repeat(200) {
            for (attempt in 1..10) {
                val d = Backoff.delaySeconds(attempt)
                val base = Backoff.baseSeconds(attempt)
                assertTrue("$d outside [0.5, 1.0] x $base", d >= base * 0.5 && d <= base)
            }
        }
    }

    @Test fun anAcceptedBatchReportsServerSideFilteringWithoutRetryingIt() = runBlocking {
        val transport = StubTransport(listOf(Responses.accepted(rejected = 1)))
        val p = Fixtures.pipeline(transport = transport)
        p.track("a_one", emptyMap())
        p.flush()
        assertEquals(1, transport.sent.size)
        assertTrue(p.queuedEvents.isEmpty())
    }

    @Test fun dispositionTable() {
        fun d(status: Int, body: String = "{}", retry: Double? = null) = Disposition.from(Responses.error(status, body, retry))
        assertEquals(Disposition.Accepted(0), Disposition.from(TransportResponse(202, "not json".toByteArray())))
        assertEquals(Disposition.Stop("invalid write key"), d(401))
        assertEquals(Disposition.Split, d(413))
        assertEquals(Disposition.RetryLater, d(500))
        assertEquals(Disposition.RetryLater, d(502))
        assertEquals(Disposition.RetryLater, d(0))
        assertEquals(Disposition.RetryLater, d(404))
        assertEquals(Disposition.Pause(3600.0, "project disabled"), d(403))
        assertEquals(Disposition.Pause(12.0, "rate limited / quota exceeded"), d(429, retry = 12.0))
        assertEquals(Disposition.DropEvents(setOf(3)), d(400, """{"error":"invalid_batch","message":"m","details":[{"path":"events.3.name","message":"x"}]}"""))
    }

    // endregion

    // region Sessions

    @Test fun sessionRotatesAfterALongBackgroundWithSessionEndBeforeSessionStart() = runBlocking {
        val clock = TestClock()
        val transport = StubTransport()
        val p = Fixtures.pipeline(clock = clock, transport = transport)
        p.start()
        clock.advanceSeconds(10.0)
        p.didEnterBackground()
        val firstSession = p.currentSessionId!!
        val lastActivity = clock.nowMs()
        clock.advanceSeconds(31 * 60.0)
        p.willEnterForeground()
        p.flush()
        val names = transport.sent.flatMap { it.names() }
        assertEquals(listOf("\$first_open", "\$app_install", "\$session_start", "\$background"), transport.sent[0].names())
        val second = transport.sent[1].events()
        assertEquals(listOf("\$session_end", "\$session_start"), second.map { it["name"] })
        assertEquals("the closing event carries the session it closes", firstSession, second[0]["session_id"])
        assertEquals("session_end is timestamped at the last activity, not now", lastActivity, (second[0]["timestamp"] as Number).toLong())
        assertTrue(second[1]["session_id"] != firstSession)
        assertTrue(names.contains("\$background"))
    }

    @Test fun foregroundWithinTheWindowContinuesTheSession() = runBlocking {
        val clock = TestClock()
        val transport = StubTransport()
        val p = Fixtures.pipeline(clock = clock, transport = transport)
        p.start()
        val sid = p.currentSessionId
        p.didEnterBackground()
        clock.advanceSeconds(5 * 60.0)
        p.willEnterForeground()
        p.flush()
        assertEquals(sid, p.currentSessionId)
        val names = transport.sent.flatMap { it.names() }
        assertTrue(names.contains("\$foreground"))
        assertEquals(1, names.count { it == "\$session_start" })
    }

    @Test fun backgroundingFlushesWhatIsQueued() = runBlocking {
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport)
        p.start()
        p.didEnterBackground()
        assertEquals(1, transport.sent.size)
        assertEquals(listOf("\$first_open", "\$app_install", "\$session_start", "\$background"), transport.sent[0].names())
    }

    @Test fun anEventAfterATimeoutRotatesTheSessionBeforeItIsRecorded() = runBlocking {
        val clock = TestClock()
        val transport = StubTransport()
        val p = Fixtures.pipeline(clock = clock, transport = transport)
        p.track("a_one", emptyMap())
        clock.advanceSeconds(30 * 60.0 + 1)
        p.track("a_two", emptyMap())
        p.flush()
        assertEquals(listOf("\$session_start", "a_one", "\$session_end", "\$session_start", "a_two"), transport.sent[0].names())
    }

    @Test fun resetWithoutAUserIdKeepsTheSession() = runBlocking {
        val p = Fixtures.pipeline()
        p.start()
        val sid = p.currentSessionId
        val before = p.queuedEvents.size
        p.reset()
        assertEquals(sid, p.currentSessionId)
        assertEquals(before, p.queuedEvents.size)
    }

    @Test fun resetAfterIdentifyRotatesTheSessionAndForgetsTheUser() = runBlocking {
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport)
        p.start()
        val sid = p.currentSessionId!!
        p.identify("user_1", mapOf("email" to "a@b.c"))
        p.reset()
        assertTrue(p.currentSessionId != sid)
        assertNull(p.currentIdentity.userId)
        assertNull(p.currentIdentity.traits)
        assertNotNull("the install id identifies the device, not the person", p.currentIdentity.installId)
        p.flush()
        val tail = transport.sent[0].names().takeLast(2)
        assertEquals(listOf("\$session_end", "\$session_start"), tail)
        assertEquals(sid, transport.sent[0].events().first { it["name"] == "\$session_end" }["session_id"])
    }

    @Test fun strictAnonymousResetAlwaysRotates() = runBlocking {
        val p = Fixtures.pipeline(privacy = PrivacyMode.StrictAnonymous)
        p.start()
        val sid = p.currentSessionId
        p.reset()
        assertTrue(p.currentSessionId != sid)
    }

    // endregion

    // region Traits

    @Test fun identifyMergesTraitsAndSendsThemWithEveryBatch() = runBlocking {
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport)
        p.start()
        p.identify("u1", mapOf("email" to "a@b.c", "plan" to "free"))
        p.identify("u1", mapOf("plan" to "pro"))
        p.track("a_one", emptyMap())
        p.flush()
        val identity = transport.sent[0].obj("identity")
        assertEquals("u1", identity["user_id"])
        assertEquals(mapOf("email" to "a@b.c", "plan" to "pro"), identity["traits"])
        assertSchemaOk(transport.bodies[0])
    }

    @Test fun invalidTraitsLeaveTheIdentityUntouched() = runBlocking {
        val p = Fixtures.pipeline()
        p.start()
        p.identify("u1", mapOf("plan" to "free"))
        p.identify("u2", (0..8).associate { "k$it" to "v" })
        p.identify("u3", mapOf("" to "v"))
        p.identify("u4", mapOf("k" to "x".repeat(257)))
        p.identify("", emptyMap())
        assertEquals("u1", p.currentIdentity.userId)
        assertEquals(mapOf("plan" to "free"), p.currentIdentity.traits)
    }

    @Test fun traitsSurviveARelaunchAndResetForgetsThem() = runBlocking {
        val identity = InMemoryIdentityStore()
        val kv = InMemoryKeyValueStore()
        val first = Fixtures.pipeline(identity = identity, kv = kv)
        first.start()
        first.identify("u1", mapOf("name" to "Ada"))
        val second = Fixtures.pipeline(identity = identity, kv = kv)
        second.start()
        assertEquals("u1", second.currentIdentity.userId)
        assertEquals(mapOf("name" to "Ada"), second.currentIdentity.traits)
        second.reset()
        assertNull(identity.load().traits)
        assertNull(identity.load().userId)
    }

    // endregion

    // region Flush mechanics

    @Test fun flushAtTriggersAnAutomaticFlush() = runBlocking {
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport, flushAt = 3)
        repeat(3) { p.track("e_one", emptyMap()) }
        withTimeout(5_000) { while (transport.bodies.isEmpty()) delay(10) }
        assertTrue(transport.sent[0].events().size >= 3)
    }

    @Test fun thePeriodicTimerFlushesQueuedEvents() = runBlocking {
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport, flushIntervalMs = 100)
        p.start()
        withTimeout(5_000) { while (transport.bodies.isEmpty()) delay(10) }
        assertTrue(transport.sent[0].names().contains("\$session_start"))
        p.shutdown()
    }

    @Test fun cancellingTheFlushTimerDoesNotFlush() = runBlocking {
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport, flushIntervalMs = 150)
        p.start()
        assertTrue(p.hasTimer)
        p.setAnalyticsCollectionEnabled(false) // cancels the timer
        delay(500)
        assertTrue("a cancelled timer must not fire on its way out", transport.sent.isEmpty())
        p.shutdown()
    }

    @Test fun flushDuringAnInFlightFlushWaitsAndSendsEverything() = runBlocking {
        val transport = GatedTransport()
        val p = Fixtures.pipeline(transport = transport)
        p.track("e_one", emptyMap())
        val first = async(Dispatchers.Default) { p.flush() }
        transport.waitUntilHeld()
        p.track("e_two", emptyMap())
        val second = async(Dispatchers.Default) { p.flush() }
        delay(100)
        assertFalse("the second flush waits for the drain in flight", second.isCompleted)
        transport.release()
        first.await()
        second.await()
        assertTrue("flush never returns with its own events still queued", p.queuedEvents.isEmpty())
        assertEquals(listOf("\$session_start", "e_one", "e_two"), transport.sent.flatMap { it.names() })
    }

    @Test fun batcherRespectsTheEventAndByteLimits() {
        val events = (0 until 150).map { Event("id$it", "s", "e_one", 0L) }
        assertEquals(Limits.BATCH_MAX_EVENTS, Batcher.nextBatch(events).size)
        val big = (0 until 5).map { Event("id$it", "s", "e_one", 0L, properties = mapOf("blob" to "x".repeat(256))) }
        val capped = Batcher.nextBatch(big, maxBytes = 1024 + 700)
        assertTrue(capped.size in 1..4)
        val one = Batcher.nextBatch(big, maxBytes = 10)
        assertEquals("a single oversized event still goes out alone", 1, one.size)
        assertTrue(Batcher.nextBatch(emptyList()).isEmpty())
    }

    @Test fun aBatchOfOneHundredEventsStaysUnderTheByteLimit() = runBlocking {
        val transport = StubTransport()
        val p = Fixtures.pipeline(transport = transport, maxQueued = 1000)
        repeat(250) { p.track("e_one", mapOf("blob" to "x".repeat(250))) }
        p.flush()
        assertTrue(transport.bodies.all { it.size <= Limits.BATCH_MAX_BYTES })
        assertTrue(transport.sent.all { it.events().size <= 100 })
        assertEquals("250 events and the session start they opened", 251, transport.sent.sumOf { it.events().size })
        transport.bodies.forEach { assertSchemaOk(it) }
    }

    // endregion

    // region Storage

    @Test fun theQueueIsEvictedFifoBeyondMaxQueuedEvents() = runBlocking {
        val store = InMemoryEventStore()
        val p = Fixtures.pipeline(store = store, maxQueued = 5)
        repeat(8) { p.track("e_$it", emptyMap()) }
        assertEquals(5, p.queuedEvents.size)
        assertEquals(5, store.load().size)
        assertEquals(listOf("e_3", "e_4", "e_5", "e_6", "e_7"), p.queuedEvents.map { it.name })
    }

    @Test fun unacknowledgedEventsSurviveARelaunchThroughTheNdjsonQueue() = runBlocking {
        val dir = tmp.newFolder("queue")
        val clock = TestClock()
        val first = Fixtures.pipeline(clock = clock, store = FileEventStore(dir), transport = StubTransport().also { it.failWithNetworkError = true })
        first.track("e_one", mapOf("n" to 1), feature = "feat", role = FeatureRole.Use, screen = "S")
        first.flush()

        val transport = StubTransport()
        val second = Fixtures.pipeline(clock = clock, store = FileEventStore(dir), transport = transport)
        second.flush()
        val e = transport.sent[0].events().single { it["name"] == "e_one" }
        assertEquals("feat", e["feature"])
        assertEquals("use", e["role"])
        assertEquals("S", e["screen"])
        assertEquals(1L, (e["properties"] as Map<*, *>)["n"])
        assertSchemaOk(transport.bodies[0])
        assertEquals("an acknowledged queue is compacted to empty", emptyList<Event>(), FileEventStore(dir).load())
    }

    @Test fun theFileStoresRoundTripAndSurviveCorruptLines() {
        val dir = tmp.newFolder("state")
        val kv = FileKeyValueStore(java.io.File(dir, "state.json"))
        kv.set("a", "1")
        kv.set("b", "two \"quoted\"")
        kv.set("a", null)
        val reopened = FileKeyValueStore(java.io.File(dir, "state.json"))
        assertNull(reopened.string("a"))
        assertEquals("two \"quoted\"", reopened.string("b"))

        val ident = FileIdentityStore(java.io.File(dir, "identity.json"))
        ident.save(Identity("i", "u", mapOf("k" to "v")))
        assertEquals(Identity("i", "u", mapOf("k" to "v")), FileIdentityStore(java.io.File(dir, "identity.json")).load())
        ident.wipe()
        assertEquals(Identity(), ident.load())

        val q = java.io.File(dir, "queue.ndjson")
        q.writeText("garbage\n" + Json.write(Event("a", "b", "e_one", 5L).toJson()) + "\n\n{not json\n")
        assertEquals(listOf("e_one"), FileEventStore(dir).load().map { it.name })
    }

    // endregion

    // region Wrapper and crash hooks

    @Test fun sdkWrapperIsOnTheWireOnlyWhenSet() = runBlocking {
        val plain = StubTransport()
        Fixtures.pipeline(transport = plain).also { it.track("e_one", emptyMap()); it.flush() }
        assertFalse(plain.bodies[0].toString(Charsets.UTF_8).contains("sdk_wrapper"))

        val wrapped = StubTransport()
        val device = Fixtures.device.copy(sdkWrapper = "flutter/0.1.0")
        Fixtures.pipeline(transport = wrapped, device = device).also { it.track("e_one", emptyMap()); it.flush() }
        assertEquals("flutter/0.1.0", wrapped.sent[0].obj("device")["sdk_wrapper"])
        assertSchemaOk(wrapped.bodies[0])
    }

    private class FakeSink : CrashSink {
        val sessions = ArrayList<String?>()
        val crumbs = ArrayList<Pair<BreadcrumbKind, String>>()
        var wiped = false
        override fun setSessionId(id: String?) { sessions.add(id) }
        override fun breadcrumb(kind: BreadcrumbKind, name: String) { crumbs.add(kind to name) }
        override fun wipe() { wiped = true }
    }

    @Test fun attachingCrashContextAdoptsAnAlreadyContinuedSession() {
        val kv = InMemoryKeyValueStore()
        val first = Fixtures.pipeline(kv = kv)
        first.start()
        val session = first.currentSessionId

        val continued = Fixtures.pipeline(kv = kv)
        continued.attach(null)
        assertEquals(session, continued.currentCrashSessionId)
    }

    @Test fun everyEventPassesTheBreadcrumbFunnelAsAName() {
        val sink = FakeSink()
        val p = Fixtures.pipeline()
        p.attach(sink)
        p.start()
        p.screen("Checkout", mapOf("secret" to "value"))
        p.track("order_placed", mapOf("secret" to "value"))
        val names = sink.crumbs.map { it.second }
        assertTrue(names.containsAll(listOf("\$first_open", "\$session_start", "Checkout", "order_placed")))
        assertEquals(BreadcrumbKind.Screen, sink.crumbs.first { it.second == "Checkout" }.first)
        assertFalse("values never reach a breadcrumb", names.any { it.contains("value") })
        assertTrue("the crash reporter follows the session", sink.sessions.last() == p.currentSessionId)
    }

    @Test fun optOutWipesTheCrashFilesToo() {
        val sink = FakeSink()
        val p = Fixtures.pipeline()
        p.attach(sink)
        p.optOut()
        assertTrue(sink.wiped)
    }

    // endregion
}

internal class CountingIdentityStore : IdentityStore {
    private var identity = Identity()
    var loads = 0
    var saves = 0
    @Synchronized override fun load(): Identity { loads++; return identity }
    @Synchronized override fun save(identity: Identity) { saves++; this.identity = identity }
    override fun wipe() = save(Identity())
}
