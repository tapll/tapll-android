package dev.useunion.internal

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import dev.useunion.Environment
import dev.useunion.PrivacyMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.net.ConnectException

class TestClock(start: Long = 1_700_000_000_000) : Clock {
    @Volatile private var now = start
    override fun nowMs(): Long = now
    fun advanceSeconds(seconds: Double) {
        now += (seconds * 1000).toLong()
    }
}

/** Scripted transport: pops responses in order (202 when the script runs out); records every body. */
internal class StubTransport(responses: List<TransportResponse> = emptyList()) : Transport {
    private val responses = ArrayDeque(responses)
    val bodies = ArrayList<ByteArray>()
    var failWithNetworkError = false

    /** Every sent batch, parsed. */
    val sent: List<Map<String, Any?>> @Synchronized get() = bodies.map { Json.parseObjectOrNull(it.toString(Charsets.UTF_8))!! }

    @Synchronized fun enqueue(r: TransportResponse) {
        responses.addLast(r)
    }

    @Synchronized
    fun send0(body: ByteArray): TransportResponse {
        bodies.add(body)
        if (failWithNetworkError) throw ConnectException("no network")
        return responses.removeFirstOrNull() ?: Responses.accepted()
    }

    override suspend fun send(body: ByteArray, writeKey: String): TransportResponse = send0(body)
}

/** Holds the first `send` until [release], so a test can act while a flush is suspended in it. */
internal class GatedTransport : Transport {
    private val gate = CompletableDeferred<Unit>()
    private val held = CompletableDeferred<Unit>()
    private var first = true
    val bodies = ArrayList<ByteArray>()
    val sent: List<Map<String, Any?>> @Synchronized get() = bodies.map { Json.parseObjectOrNull(it.toString(Charsets.UTF_8))!! }

    override suspend fun send(body: ByteArray, writeKey: String): TransportResponse {
        val isFirst = synchronized(this) { first.also { first = false } }
        if (isFirst) {
            held.complete(Unit)
            gate.await()
        }
        synchronized(this) { bodies.add(body) }
        return Responses.accepted()
    }

    suspend fun waitUntilHeld() = held.await()
    fun release() {
        gate.complete(Unit)
    }
}

internal object Responses {
    fun accepted(rejected: Int = 0) = TransportResponse(202, """{"accepted":1,"rejected":$rejected,"batch_id":"b"}""".toByteArray())
    fun error(status: Int, json: String, retryAfter: Double? = null) = TransportResponse(status, json.toByteArray(), retryAfter)
    fun status(status: Int, retryAfter: Double? = null) = TransportResponse(status, "{}".toByteArray(), retryAfter)
}

/** The contract's JSON Schemas, copied into `src/test/resources`, validated with a real validator. */
internal object Schemas {
    private val mapper = ObjectMapper()
    private val config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()

    private fun load(name: String): JsonSchema {
        val stream = Schemas::class.java.classLoader!!.getResourceAsStream(name) ?: error("missing test resource $name")
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(stream, config)
    }

    val event: JsonSchema by lazy { load("event-batch.v1.json") }
    val crash: JsonSchema by lazy { load("crash-batch.v2.json") }

    val eventJson: Map<String, Any?> by lazy { raw("event-batch.v1.json") }
    val crashJson: Map<String, Any?> by lazy { raw("crash-batch.v2.json") }

    private fun raw(name: String): Map<String, Any?> =
        Json.parseObjectOrNull(Schemas::class.java.classLoader!!.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8))!!

    fun errors(schema: JsonSchema, json: String): List<String> =
        schema.validate(mapper.readTree(json)).map { it.message }.sorted()

    fun eventErrors(body: ByteArray) = errors(event, body.toString(Charsets.UTF_8))
    fun crashErrors(json: String) = errors(crash, json)
}

internal object Fixtures {
    val device = DeviceContext("2.4.0", "240", SdkInfo.version, null, "15", "Google Pixel 8", "pl-PL", "Europe/Warsaw")

    fun pipeline(
        clock: TestClock = TestClock(),
        transport: Transport = StubTransport(),
        privacy: PrivacyMode = PrivacyMode.ProductAnalytics,
        flushAt: Int = 100,
        kv: KeyValueStore = InMemoryKeyValueStore(),
        identity: IdentityStore = InMemoryIdentityStore(),
        store: EventStore = InMemoryEventStore(),
        device: DeviceContext = this.device,
        maxQueued: Int = 50,
        flushIntervalMs: Long = 3_600_000,
    ) = EventPipeline(
        PipelineConfig("test", Environment.Production, privacy, flushAt, flushIntervalMs, maxQueued),
        store, transport, IdentityCoordinator(identity, privacy == PrivacyMode.StrictAnonymous), kv, clock, SdkLogger.silent, device,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.events(): List<Map<String, Any?>> = this["events"] as List<Map<String, Any?>>
internal fun Map<String, Any?>.names(): List<String> = events().map { it["name"] as String }

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.obj(key: String): Map<String, Any?> = this[key] as Map<String, Any?>
