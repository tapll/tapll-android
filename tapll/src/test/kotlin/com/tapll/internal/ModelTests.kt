package com.tapll.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ModelTests {
    // region UUIDv7

    @Test fun uuidV7HasTheRightVersionAndVariantAndEmbedsTheTimestamp() {
        val ms = 1_700_000_000_123L
        val id = UUIDv7.generate(ms)
        assertTrue(id, Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id))
        assertEquals(ms, id.replace("-", "").substring(0, 12).toLong(16))
        assertEquals(id, UUID.fromString(id).toString())
    }

    @Test fun uuidV7IsTimeOrderedMonotonicWithinAMillisecondAndUnique() {
        val ids = (0 until 2000).map { UUIDv7.generate(1_700_000_000_000L) }
        assertEquals(ids.size, ids.toSet().size)
        val later = UUIDv7.generate(1_700_000_000_001L)
        assertTrue(ids.all { it.substring(0, 13) < later.substring(0, 13) })
        // Within one millisecond the 12-bit counter increases (wrapping at 4096), so consecutive ids are ordered.
        val a = ids[10].substring(14, 18).toInt(16)
        val b = ids[11].substring(14, 18).toInt(16)
        assertEquals((a + 1) and 0x0FFF, b and 0x0FFF)
    }

    // endregion

    // region Validation

    @Test fun customNamesMustBeLowercaseSnakeCaseAndNeverDollarPrefixed() {
        Validation.customName("workout_started")
        Validation.customName("a")
        Validation.customName("a".repeat(64))
        for (bad in listOf("", "Workout", "1abc", "has-dash", "has space", "\$first_open", "a".repeat(65), "_leading", "émoji")) {
            assertThrows(bad, ValidationException::class.java) { Validation.customName(bad) }
        }
    }

    @Test fun propertyLimits() {
        Validation.properties(mapOf("a" to "x".repeat(256), "b" to 1, "c" to 1.5, "d" to true, "e" to 2L))
        assertThrows(ValidationException::class.java) { Validation.properties(mapOf("a" to "x".repeat(257))) }
        assertThrows(ValidationException::class.java) { Validation.properties(mapOf("" to 1)) }
        assertThrows(ValidationException::class.java) { Validation.properties(mapOf("k".repeat(65) to 1)) }
        assertThrows(ValidationException::class.java) { Validation.properties(mapOf("a" to Double.POSITIVE_INFINITY)) }
        assertThrows(ValidationException::class.java) { Validation.properties(mapOf("a" to Float.NaN)) }
        assertThrows(ValidationException::class.java) { Validation.properties(mapOf("a" to listOf(1))) }
        assertThrows(ValidationException::class.java) { Validation.properties(mapOf("a" to Any())) }
        Validation.properties((0 until 32).associate { "k$it" to 1 })
        assertThrows(ValidationException::class.java) { Validation.properties((0 until 33).associate { "k$it" to 1 }) }
    }

    @Test fun screenFeatureTraitAndUserIdLimits() {
        Validation.screen(null)
        Validation.screen("s".repeat(128))
        assertThrows(ValidationException::class.java) { Validation.screen("s".repeat(129)) }
        Validation.featureKey(null)
        Validation.featureKey("checkout_v2")
        assertThrows(ValidationException::class.java) { Validation.featureKey("Checkout") }
        assertThrows(ValidationException::class.java) { Validation.featureKey("") }
        assertThrows(ValidationException::class.java) { Validation.featureKey("a".repeat(65)) }
        Validation.traits((0 until 8).associate { "k$it" to "v" })
        assertThrows(ValidationException::class.java) { Validation.traits((0 until 9).associate { "k$it" to "v" }) }
        assertThrows(ValidationException::class.java) { Validation.traits(mapOf("k".repeat(41) to "v")) }
        assertThrows(ValidationException::class.java) { Validation.traits(mapOf("k" to "v".repeat(257))) }
        Validation.userId("u".repeat(128))
        assertThrows(ValidationException::class.java) { Validation.userId("u".repeat(129)) }
        assertThrows(ValidationException::class.java) { Validation.userId("") }
    }

    /** `Limits` is a hand-kept mirror of `limits.ts`: it must agree with the schema copy the server publishes. */
    @Suppress("UNCHECKED_CAST")
    @Test fun limitsMatchTheEventSchema() {
        fun Map<String, Any?>.m(k: String) = this[k] as Map<String, Any?>
        val batch = Schemas.eventJson.m("definitions").m("EventBatchV1")
        val props = batch.m("properties")
        val events = props.m("events")
        assertEquals(Limits.BATCH_MAX_EVENTS.toLong(), events["maxItems"])
        val event = events.m("items").m("properties")
        val custom = (event.m("name")["anyOf"] as List<Map<String, Any?>>).first { it["pattern"] != null }
        assertEquals(Limits.NAME_PATTERN.pattern, custom["pattern"])
        assertEquals(Limits.EVENT_NAME_MAX.toLong(), custom["maxLength"])
        assertEquals(Limits.SCREEN_NAME_MAX.toLong(), event.m("screen")["maxLength"])
        assertEquals(Limits.FEATURE_KEY_MAX.toLong(), event.m("feature")["maxLength"])
        assertEquals(Limits.NAME_PATTERN.pattern, event.m("feature")["pattern"])
        val properties = event.m("properties")
        assertEquals(Limits.PROPERTY_KEY_MAX.toLong(), properties.m("propertyNames")["maxLength"])
        val stringBranch = (properties.m("additionalProperties")["anyOf"] as List<Map<String, Any?>>).first { it["type"] == "string" }
        assertEquals(Limits.PROPERTY_STRING_MAX.toLong(), stringBranch["maxLength"])

        val identity = props.m("identity").m("properties")
        assertEquals(Limits.USER_ID_MAX.toLong(), identity.m("user_id")["maxLength"])
        val traits = identity.m("traits")
        assertEquals(Limits.MAX_TRAITS.toLong(), traits["maxProperties"])
        assertEquals(Limits.TRAIT_KEY_MAX.toLong(), traits.m("propertyNames")["maxLength"])
        assertEquals(Limits.TRAIT_VALUE_MAX.toLong(), traits.m("additionalProperties")["maxLength"])

        val device = props.m("device").m("properties")
        assertEquals(Limits.WRAPPER_MAX.toLong(), device.m("sdk_wrapper")["maxLength"])
        assertEquals(64L, device.m("device_model")["maxLength"])
        assertEquals(32L, device.m("os_version")["maxLength"])
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun crashLimitsMatchTheCrashSchema() {
        fun Map<String, Any?>.m(k: String) = this[k] as Map<String, Any?>
        val defs = Schemas.crashJson.m("definitions")
        val batch = defs.entries.first { (it.value as Map<String, Any?>).m("properties").containsKey("reports") }.value as Map<String, Any?>
        val report = batch.m("properties").m("reports").let { it.m("items") }.m("properties")
        assertEquals(CrashLimitsRef.MAX_REPORTS, batch.m("properties").m("reports")["maxItems"])
        assertEquals(com.tapll.internal.crash.CrashLimits.MAX_THREADS.toLong(), report.m("threads")["maxItems"])
        assertEquals(com.tapll.internal.crash.CrashLimits.MAX_BREADCRUMBS.toLong(), report.m("breadcrumbs")["maxItems"])
        assertEquals(com.tapll.internal.crash.CrashLimits.REASON_MAX.toLong(), report.m("exception").m("properties").m("reason")["maxLength"])
        assertEquals(com.tapll.internal.crash.CrashLimits.TYPE_MAX.toLong(), report.m("exception").m("properties").m("type")["maxLength"])
        val customKeys = report.m("custom_keys")
        assertEquals(com.tapll.internal.crash.CrashLimits.MAX_CUSTOM_KEYS.toLong(), customKeys["maxProperties"])
        assertEquals(com.tapll.internal.crash.CrashLimits.CUSTOM_KEY_MAX.toLong(), customKeys.m("propertyNames")["maxLength"])
        assertEquals(com.tapll.internal.crash.CrashLimits.CUSTOM_VALUE_MAX.toLong(), customKeys.m("additionalProperties")["maxLength"])
        val thread = report.m("threads").m("items").m("properties")
        assertEquals(com.tapll.internal.crash.CrashLimits.MAX_FRAMES_PER_THREAD.toLong(), thread.m("frames")["maxItems"])
        assertEquals(com.tapll.internal.crash.CrashLimits.BREADCRUMB_NAME_MAX.toLong(), report.m("breadcrumbs").m("items").m("properties").m("name")["maxLength"])
    }

    private object CrashLimitsRef { const val MAX_REPORTS = 8L }

    @Test fun theSchemaValidatorActuallyRejectsABadBatch() {
        // A validator that accepts everything would make every conformance test in this suite pass for free.
        val good = EventBatch("b", com.tapll.Environment.Production, com.tapll.PrivacyMode.ProductAnalytics, 1L, Fixtures.device, Identity(), listOf(Event("e", "s", "e_one", 1L)))
        assertTrue(Schemas.eventErrors(good.encode()).isNotEmpty()) // ids are not uuids
        val bad = Json.write(linkedMapOf("contract_version" to 2))
        assertTrue(Schemas.errors(Schemas.event, bad).isNotEmpty())
        val strictWithIdentity = good.copy(
            batchId = UUIDv7.generate(), events = listOf(Event(UUIDv7.generate(), UUIDv7.generate(), "\$bogus", 1L)),
        )
        assertTrue("an unknown \$ name is not in AutoEventName", Schemas.eventErrors(strictWithIdentity.encode()).isNotEmpty())
    }

    // endregion

    // region Json

    @Test fun jsonRoundTripsEscapesAndNumbers() {
        val value = linkedMapOf<String, Any?>(
            "s" to "quote\" backslash\\ newline\n tab\t unicode é \u0001 emoji 😀",
            "n" to 30, "f" to 0.5, "l" to (1L shl 40), "t" to true, "z" to null, "list" to listOf(1, "a", null), "obj" to mapOf("k" to "v"),
        )
        val text = Json.write(value)
        val back = Json.parseObjectOrNull(text)!!
        assertEquals(value["s"], back["s"])
        assertEquals(30L, back["n"])
        assertEquals(0.5, back["f"])
        assertEquals(1L shl 40, back["l"])
        assertEquals(listOf(1L, "a", null), back["list"])
        assertTrue(text, text.contains("\"n\":30,"))
        assertFalse("no space padding", text.contains(": "))
    }

    @Test fun jsonRejectsMalformedInputWithoutThrowingFromTheSafeEntryPoint() {
        for (bad in listOf("", "{", "{\"a\":}", "[1,]x", "nul", "{\"a\":1} trailing", "\"unterminated")) {
            assertNull(bad, Json.parseOrNull(bad))
        }
        assertThrows(IllegalArgumentException::class.java) { Json.write(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { Json.write(Any()) }
    }

    // endregion

    // region Sessions, install state

    @Test fun sessionContinuesWithin30MinutesAndRotatesAfter() {
        val clock = TestClock()
        val s = SessionManager(clock, InMemoryKeyValueStore())
        val first = s.touch() as SessionManager.Transition.Rotated
        assertNull(first.ended)
        clock.advanceSeconds(29 * 60.0)
        val cont = s.touch() as SessionManager.Transition.Continued
        assertEquals(first.started.sessionId, cont.sessionId)
        // The window slides: 29 more minutes after the last activity is still the same session.
        clock.advanceSeconds(29 * 60.0)
        assertTrue(s.touch() is SessionManager.Transition.Continued)
        clock.advanceSeconds(30 * 60.0)
        val rotated = s.touch() as SessionManager.Transition.Rotated
        assertEquals(first.started.sessionId, rotated.ended?.sessionId)
        assertTrue(rotated.started.sessionId != first.started.sessionId)
    }

    @Test fun sessionSnapshotSurvivesARelaunch() {
        val clock = TestClock()
        val kv = InMemoryKeyValueStore()
        val first = SessionManager(clock, kv)
        val started = (first.touch() as SessionManager.Transition.Rotated).started
        clock.advanceSeconds(60.0)
        val relaunched = SessionManager(clock, kv)
        assertEquals(started.sessionId, (relaunched.touch() as SessionManager.Transition.Continued).sessionId)
        relaunched.clear()
        assertNull(SessionManager(clock, kv).current)
    }

    @Test fun installDetection() {
        val kv = InMemoryKeyValueStore()
        val v1 = Fixtures.device
        assertEquals(InstallState.Outcome.FirstOpen, InstallState.evaluate(kv, v1))
        assertEquals(InstallState.Outcome.Unchanged, InstallState.evaluate(kv, v1))
        assertEquals(InstallState.Outcome.Updated("2.4.0", "240"), InstallState.evaluate(kv, v1.copy(appVersion = "2.5.0")))
        assertEquals(InstallState.Outcome.Updated("2.5.0", "240"), InstallState.evaluate(kv, v1.copy(appVersion = "2.5.0", appBuild = "241").copy(appVersion = "2.5.0")))
    }

    // endregion

    // region Device context

    @Test fun deviceModelIsManufacturerCapitalisedThenModelAtMost64Characters() {
        assertEquals("Google Pixel 8", DeviceContextProvider.deviceModel("Google", "Pixel 8"))
        assertEquals("Samsung SM-S918B", DeviceContextProvider.deviceModel("samsung", "SM-S918B"))
        assertEquals("HTC One", DeviceContextProvider.deviceModel("HTC", "HTC One"))
        assertEquals("Xiaomi 2201123G", DeviceContextProvider.deviceModel("Xiaomi", "2201123G"))
        assertEquals("Pixel", DeviceContextProvider.deviceModel("", "Pixel"))
        assertEquals("Android", DeviceContextProvider.deviceModel(null, null))
        assertEquals(64, DeviceContextProvider.deviceModel("Acme", "M".repeat(100)).length)
    }

    @Test fun wrapperValuesAreValidatedAgainstTheContractLimit() {
        assertEquals("flutter/0.1.0", WrapperInfo.value("flutter", "0.1.0"))
        assertEquals("react-native/1.2.3-beta.1", WrapperInfo.value("react-native", "1.2.3-beta.1"))
        for ((n, v) in listOf("" to "1", "f" to "", "a b" to "1", "a" to "1 0", "a/b" to "1", "a" to "1/2", "né" to "1", "a" to "tab\t")) {
            assertNull("$n/$v", WrapperInfo.value(n, v))
        }
        assertNotNull(WrapperInfo.value("a".repeat(15), "b".repeat(16))) // 32 exactly
        assertNull("33 characters would be clipped to a release that does not exist", WrapperInfo.value("a".repeat(16), "b".repeat(16)))
    }

    @Test fun userAgentGainsTheWrapperSuffixOnlyWhenWrapped() {
        assertEquals("tapll-android/0.1.0", SdkInfo.userAgent(null, "0.1.0"))
        assertEquals("tapll-android/0.1.0 flutter/0.2.0", SdkInfo.userAgent("flutter/0.2.0", "0.1.0"))
    }

    @Test fun theSdkVersionIsTheOneInGradleProperties() {
        assertEquals("0.2.0", SdkInfo.version)
    }

    @Test fun crashEndpointIsDerivedFromTheEventEndpoint() {
        assertEquals("https://in.tapll.com/v1/crash", crashEndpointFrom("https://in.tapll.com/v1/batch"))
        assertEquals("http://localhost:8789/v1/crash", crashEndpointFrom("http://localhost:8789/v1/batch/"))
        assertEquals("https://staging.example.com/ingest/crash", crashEndpointFrom("https://staging.example.com/ingest/batch"))
    }

    @Test fun clientDirectoryHashIsStableFnv1a() {
        // Known FNV-1a 64 of "" and "a", base 36: the directory name must not change between launches or versions.
        assertEquals(0xcbf29ce484222325UL.toString(36), Client.stableHash(""))
        assertEquals(0xaf63dc4c8601ec8cUL.toString(36), Client.stableHash("a"))
        assertEquals(Client.stableHash("av_key"), Client.stableHash("av_key"))
        assertTrue(Client.stableHash("av_key") != Client.stableHash("av_key2"))
    }

    // endregion
}
