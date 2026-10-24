package dev.useunion.internal

import java.io.File
import java.io.FileOutputStream

/** Small key-value persistence for non-identifying SDK state (session snapshot, last version, opt-out). */
internal interface KeyValueStore {
    fun string(key: String): String?
    fun set(key: String, value: String?)
}

internal class InMemoryKeyValueStore : KeyValueStore {
    private val map = HashMap<String, String>()
    @Synchronized override fun string(key: String): String? = map[key]
    @Synchronized override fun set(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/**
 * A JSON file in `noBackupFilesDir`, not SharedPreferences. SharedPreferences are part of Auto Backup,
 * so a restore onto a new phone would bring the session snapshot, the "last seen version" and the
 * opt-out flag along — a new device would read as an `$app_update` of an old install. Files in
 * `noBackupFilesDir` die with the install, which is exactly the Android contract (see CHECKLIST).
 */
internal class FileKeyValueStore(private val file: File) : KeyValueStore {
    private var cache: MutableMap<String, String>? = null

    @Synchronized
    private fun map(): MutableMap<String, String> {
        cache?.let { return it }
        val loaded = LinkedHashMap<String, String>()
        runCatching {
            if (file.exists()) {
                Json.parseObjectOrNull(file.readText())?.forEach { (k, v) -> if (v is String) loaded[k] = v }
            }
        }
        cache = loaded
        return loaded
    }

    @Synchronized override fun string(key: String): String? = map()[key]

    @Synchronized
    override fun set(key: String, value: String?) {
        val m = map()
        if (value == null) m.remove(key) else m[key] = value
        runCatching { AtomicFile.write(file, Json.writeBytes(m)) }
    }
}

internal object AtomicFile {
    /** Write to a sibling and rename: a crash mid-write leaves the old file, never half of the new one. */
    fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("could not move ${tmp.name} to ${target.name}")
        }
    }
}

/** Durable queue of not-yet-acknowledged events. */
internal interface EventStore {
    fun load(): List<Event>
    fun append(event: Event)
    fun replaceAll(events: List<Event>)
}

internal class InMemoryEventStore : EventStore {
    private val events = ArrayList<Event>()
    @Synchronized override fun load(): List<Event> = events.toList()
    @Synchronized override fun append(event: Event) { events.add(event) }
    @Synchronized override fun replaceAll(events: List<Event>) { this.events.clear(); this.events.addAll(events) }
}

/**
 * NDJSON append log at `noBackupFilesDir/Union/<keyHash>/queue.ndjson`. Append is O(1); the file is
 * rewritten (compacted) only after a batch is acknowledged or evicted.
 */
internal class FileEventStore(directory: File) : EventStore {
    private val file = File(directory, "queue.ndjson").also { it.parentFile?.mkdirs() }

    @Synchronized
    override fun load(): List<Event> {
        if (!file.exists()) return emptyList()
        return file.readLines(Charsets.UTF_8).mapNotNull { line ->
            if (line.isBlank()) null else Json.parseObjectOrNull(line)?.let(Event::fromJson)
        }
    }

    @Synchronized
    override fun append(event: Event) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { it.write((Json.write(event.toJson()) + "\n").toByteArray(Charsets.UTF_8)) }
    }

    @Synchronized
    override fun replaceAll(events: List<Event>) {
        val sb = StringBuilder()
        for (e in events) sb.append(Json.write(e.toJson())).append('\n')
        AtomicFile.write(file, sb.toString().toByteArray(Charsets.UTF_8))
    }
}

/** Persists the pseudonymous install id and the optional customer user id. */
internal interface IdentityStore {
    fun load(): Identity
    fun save(identity: Identity)
    fun wipe()
}

/** strict_anonymous: nothing is ever written; batches carry `identity: {}`. */
internal object NoopIdentityStore : IdentityStore {
    override fun load() = Identity.ANONYMOUS
    override fun save(identity: Identity) = Unit
    override fun wipe() = Unit
}

internal class InMemoryIdentityStore : IdentityStore {
    private var identity = Identity()
    @Synchronized override fun load() = identity
    @Synchronized override fun save(identity: Identity) { this.identity = identity }
    override fun wipe() = save(Identity())
}

/** Identity in `noBackupFilesDir`, so it never rides Auto Backup to another device. Traits can name a real person. */
internal class FileIdentityStore(private val file: File) : IdentityStore {
    @Synchronized
    override fun load(): Identity {
        val m = runCatching { if (file.exists()) Json.parseObjectOrNull(file.readText()) else null }.getOrNull() ?: return Identity()
        @Suppress("UNCHECKED_CAST")
        val traits = (m["traits"] as? Map<String, Any?>)?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }?.toMap()
        return Identity(m["install_id"] as? String, m["user_id"] as? String, traits)
    }

    @Synchronized
    override fun save(identity: Identity) {
        runCatching { AtomicFile.write(file, Json.writeBytes(identity.toJson())) }
    }

    @Synchronized
    override fun wipe() {
        runCatching { file.delete() }
    }
}

/**
 * One reader of the identity store: load-or-mint **once, on whoever asks first**, and cache. In
 * practice that is the pipeline thread; an app reading `Union.installId` right after `configure`
 * pays the file read on its own thread, exactly once.
 */
internal class IdentityCoordinator(private val store: IdentityStore, private val strict: Boolean) {
    private var cached: Identity? = null

    init {
        // "strict_anonymous stores no identity" is a promise about what is on the device, so anything
        // an earlier mode left goes now rather than on the first event.
        if (strict) {
            store.wipe()
            cached = Identity.ANONYMOUS
        }
    }

    @Synchronized
    fun ensure(nowMs: Long): Identity {
        cached?.let { return it }
        var id = store.load()
        if (id.installId == null) {
            id = id.copy(installId = UUIDv7.generate(nowMs))
            store.save(id)
        }
        cached = id
        return id
    }

    @Synchronized
    fun save(identity: Identity) {
        cached = identity
        store.save(identity)
    }

    @Synchronized
    fun wipe() {
        cached = Identity.ANONYMOUS
        store.wipe()
    }

    /** Drops the cache so the next [ensure] mints again: `optIn` after `optOut` is the one caller. */
    @Synchronized
    fun forget() {
        cached = null
    }
}
