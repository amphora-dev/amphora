package app.amphora.core.engine

import android.util.Log
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Named-component fingerprint for a capability probe. Each component is a
 * `name → value` pair (driver, driver package, wine/box64 build, layer, app
 * build, probe binary hash, one `env.<NAME>` entry per guest-view env var).
 * The values are persisted next to the result so a later miss can say *why*
 * ("driver changed"), not just that the hash differs.
 */
data class CapabilityFingerprint(val components: Map<String, String>) {
    /** Stable digest over the schema tag + sorted components. */
    val key: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        fun feed(s: String) = digest.update(s.toByteArray(Charsets.UTF_8))
        feed("fp$FINGERPRINT_SCHEMA|")
        components.toSortedMap().forEach { (k, v) ->
            feed(k)
            feed("=")
            feed(v)
            feed("\n")
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Labels of components that differ from [other]; for "why re-probe" logs. */
    fun diffLabels(other: CapabilityFingerprint): List<String> {
        val names = components.keys + other.components.keys
        return names.filter { components[it] != other.components[it] }
            .map { COMPONENT_LABELS[it] ?: it }
            .sorted()
    }

    companion object {
        const val FINGERPRINT_SCHEMA = 1

        /** Human labels for well-known component names (settings/log display). */
        private val COMPONENT_LABELS = mapOf(
            "driver" to "graphics driver",
            "driverPackage" to "driver package",
            "wine" to "wine",
            "box64" to "box64",
            "layer" to "vulkan layer",
            "appVersionCode" to "app build",
            "probeExe" to "probe binary",
        )
    }
}

/**
 * One stored probe outcome. A failure is stored like a success — [failure]
 * carries the reason and [failureCount] the number of consecutive failed
 * runs under this fingerprint (reset by the first success).
 */
data class CapabilityProbeEntry(
    val kind: String,
    val key: String,
    val components: Map<String, String>,
    /** Probe-typed payload (e.g. the vkprobe report JSON); null on failure. */
    val payload: JSONObject?,
    val failure: String?,
    val failureCount: Int,
    val probedAtMs: Long,
    val durationMs: Long,
) {
    val succeeded: Boolean get() = failure == null
    val fingerprint: CapabilityFingerprint get() = CapabilityFingerprint(components)
}

/** What a fresh [CapabilityProbeStore.awaitOutcome] run produced. */
data class CapabilityProbeRun<T>(
    val result: T?,
    /** Serialized form of [result] for the store; null when nothing ran/failed. */
    val payload: JSONObject?,
    val failure: String?,
    val durationMs: Long,
)

/** The typed outcome consumers read — no consumer touches JSON. */
data class CapabilityProbeOutcome<T>(
    val key: String,
    val components: Map<String, String>,
    val result: T?,
    val failure: String?,
    val fromCache: Boolean,
    val probedAtMs: Long,
    val durationMs: Long,
)

/**
 * File-backed, multi-fingerprint LRU cache for "detect only when the
 * environment changed" probes.
 *
 * - **Multi-slot**: results are keyed by [CapabilityFingerprint.key]; switching
 *   between known configurations hits the existing entry instead of
 *   re-probing. Bounded to [maxEntries] (LRU eviction past it).
 * - **Atomic**: writes go through a temp file + rename; a torn/corrupt file
 *   or unknown `"store"` schema reads as empty (a miss), never a crash.
 * - **Failure policy**: a failed run is cached with its reason. A fingerprint
 *   with `failureCount < [MAX_FAILURE_ATTEMPTS]` still counts as "needs run"
 *   (retried on the next launches); at the limit it is terminal — only a
 *   fingerprint change or [invalidate] (the manual "Re-detect") probes again.
 * - **Single-flight**: [awaitOutcome] serializes same-key callers on a Mutex;
 *   the second caller sees the first's fresh result instead of re-running.
 *   The map is per-process — cross-process dedup is the caller's job (the
 *   hidden-probe path is guarded against an active game session).
 * - **Re-reads the file per call** so a verdict written by the `:session`
 *   process is visible to the settings process immediately.
 */
class CapabilityProbeStore(private val file: File, private val maxEntries: Int = MAX_ENTRIES) {
    private val lock = Any()
    private val flights = ConcurrentHashMap<String, Mutex>()

    /** The most recently used entry, or null — what `cached()` reports. */
    fun mru(): CapabilityProbeEntry? = synchronized(lock) {
        readEntries().lastOrNull()
    }

    /** Exact-key hit, or null. Touches LRU order. */
    fun lookup(key: String): CapabilityProbeEntry? = synchronized(lock) {
        val entries = readEntries()
        val hit = entries.firstOrNull { it.key == key } ?: return null
        entries.remove(hit)
        entries.add(hit)
        writeEntries(entries)
        hit
    }

    /**
     * True when [fingerprint] needs a run: no entry, or the entry is a
     * failure that still has retries left. A terminal failure (>=
     * [MAX_FAILURE_ATTEMPTS]) does not re-run.
     */
    fun needsRun(fingerprint: CapabilityFingerprint): Boolean {
        val entry = peek(fingerprint.key) ?: return true
        return !entry.succeeded && entry.failureCount < MAX_FAILURE_ATTEMPTS
    }

    /** The stored entry for [fingerprint] without touching LRU order. */
    fun peek(key: String): CapabilityProbeEntry? = synchronized(lock) {
        readEntries().firstOrNull { it.key == key }
    }

    /** All stored entries, LRU first. */
    fun entries(): List<CapabilityProbeEntry> = synchronized(lock) { readEntries() }

    /**
     * Why a run for [fingerprint] would not reuse the most recent entry —
     * the changed component labels ("graphics driver", "wine", …) or
     * `["no cached result"]` on a cold store.
     */
    fun explainMiss(fingerprint: CapabilityFingerprint): List<String> {
        val current = peek(fingerprint.key)
        if (current != null && current.succeeded) return emptyList()
        if (current != null && !current.succeeded) {
            return listOf(
                if (current.failureCount < MAX_FAILURE_ATTEMPTS) {
                    "retry after failure: ${current.failure}"
                } else {
                    current.failure ?: "previous failure"
                },
            )
        }
        val mru = mru() ?: return listOf("no cached result")
        return fingerprint.diffLabels(mru.fingerprint).ifEmpty { listOf("environment changed") }
    }

    /**
     * Record an outcome for [fingerprint]. Upserts the entry, marks it MRU,
     * evicts the LRU past [maxEntries]. Consecutive failures accumulate
     * [CapabilityProbeEntry.failureCount]; a success resets it.
     */
    fun record(
        fingerprint: CapabilityFingerprint,
        kind: String,
        payload: JSONObject?,
        failure: String?,
        durationMs: Long,
    ): CapabilityProbeEntry = synchronized(lock) {
        val entries = readEntries()
        val previous = entries.firstOrNull { it.key == fingerprint.key }
        val entry =
            CapabilityProbeEntry(
                kind = kind,
                key = fingerprint.key,
                components = fingerprint.components,
                payload = payload,
                failure = failure,
                failureCount =
                if (failure == null) 0 else (previous?.takeIf { !it.succeeded }?.failureCount ?: 0) + 1,
                probedAtMs = System.currentTimeMillis(),
                durationMs = durationMs,
            )
        entries.removeAll { it.key == entry.key }
        entries.add(entry)
        while (entries.size > maxEntries) entries.removeAt(0)
        writeEntries(entries)
        entry
    }

    /** Drop [fingerprint]'s entry — the manual "Re-detect" path. */
    fun invalidate(fingerprint: CapabilityFingerprint) = synchronized(lock) {
        val entries = readEntries()
        if (entries.removeAll { it.key == fingerprint.key }) writeEntries(entries)
    }

    /**
     * Single-flight runner: serializes same-key callers, re-checks the store
     * once the lock is held (the first caller's result then serves the rest),
     * runs [run] on a miss, and records whatever it produced.
     */
    suspend fun <T> awaitOutcome(
        kind: String,
        fingerprint: CapabilityFingerprint,
        decode: (JSONObject) -> T,
        run: suspend () -> CapabilityProbeRun<T>,
    ): CapabilityProbeOutcome<T> {
        peek(fingerprint.key)?.let { stored ->
            if (!needsRun(fingerprint)) {
                return stored.toOutcome(decode, fromCache = true)
            }
        }
        val mutex = flights.getOrPut("$kind:${fingerprint.key}") { Mutex() }
        if (flights.size > MAX_FLIGHTS) flights.clear()
        mutex.withLock {
            val stored = peek(fingerprint.key)
            if (stored != null && !needsRun(fingerprint)) {
                return stored.toOutcome(decode, fromCache = true)
            }
            val ran = run()
            val entry =
                record(fingerprint, kind, ran.payload, ran.failure, ran.durationMs)
            return entry.toOutcome(decode, fromCache = false)
        }
    }

    private fun <T> CapabilityProbeEntry.toOutcome(
        decode: (JSONObject) -> T,
        fromCache: Boolean,
    ): CapabilityProbeOutcome<T> = CapabilityProbeOutcome(
        key = key,
        components = components,
        result = payload?.let { runCatching { decode(it) }.getOrNull() },
        failure = failure,
        fromCache = fromCache,
        probedAtMs = probedAtMs,
        durationMs = durationMs,
    )

    /** Entries LRU-first. Corrupt root / unknown schema → empty list. */
    private fun readEntries(): MutableList<CapabilityProbeEntry> {
        if (!file.isFile) return mutableListOf()
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return mutableListOf()
        if (root.optInt("store") != STORE_SCHEMA) return mutableListOf()
        val arr = root.optJSONArray("entries") ?: return mutableListOf()
        val out = mutableListOf<CapabilityProbeEntry>()
        for (i in 0 until arr.length()) {
            runCatching { parseEntry(arr.getJSONObject(i)) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    private fun parseEntry(o: JSONObject): CapabilityProbeEntry {
        require(o.optInt("entry") == ENTRY_SCHEMA) { "unknown entry schema" }
        val componentsJson = o.getJSONObject("components")
        val components = LinkedHashMap<String, String>()
        componentsJson.keys().forEach { components[it] = componentsJson.getString(it) }
        return CapabilityProbeEntry(
            kind = o.getString("kind"),
            key = o.getString("key"),
            components = components,
            payload = if (o.isNull("payload")) null else o.getJSONObject("payload"),
            failure = if (o.isNull("failure")) null else o.getString("failure"),
            failureCount = o.optInt("failures", 0),
            probedAtMs = o.optLong("probedAtMs", 0),
            durationMs = o.optLong("durationMs", 0),
        )
    }

    private fun writeEntries(entries: List<CapabilityProbeEntry>) {
        runCatching {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("entry", ENTRY_SCHEMA)
                        .put("kind", e.kind)
                        .put("key", e.key)
                        .put("components", JSONObject(e.components))
                        .put("payload", e.payload)
                        .put("failure", e.failure)
                        .put("failures", e.failureCount)
                        .put("probedAtMs", e.probedAtMs)
                        .put("durationMs", e.durationMs),
                )
            }
            val root = JSONObject().put("store", STORE_SCHEMA).put("entries", arr)
            writeAtomic(root.toString())
        }.onFailure { Log.w(TAG, "probe store write failed", it) }
    }

    /** Temp file + rename so a crash mid-write never leaves a torn entry. */
    private fun writeAtomic(text: String) {
        val parent = file.parentFile ?: return
        parent.mkdirs()
        val tmp = File.createTempFile(".${file.name}.", ".tmp", parent)
        try {
            tmp.writeText(text)
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "CapabilityProbeStore"
        const val STORE_SCHEMA = 1
        const val ENTRY_SCHEMA = 1

        /**
         * A failed fingerprint gets at most this many probe runs total
         * (first run + retries on the following launches). After that it is
         * treated as terminal: re-probe only on fingerprint change or a
         * manual invalidate. Three gives a transient wineserver/boot hiccup
         * two retries without every launch paying the probe cost forever.
         */
        const val MAX_FAILURE_ATTEMPTS = 3

        /** Stored fingerprints — comfortably above #drivers × recent builds. */
        const val MAX_ENTRIES = 8

        /** Bounded single-flight map; probes run rarely so this never grows. */
        private const val MAX_FLIGHTS = 64
    }
}
