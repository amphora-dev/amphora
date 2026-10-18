package app.amphora.core.engine

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the generic capability-probe store: named fingerprint components,
 * multi-slot LRU hits (A→B→A), change reasons, failure retry bound,
 * atomic/corrupt handling, and single-flight.
 */
class CapabilityProbeStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store(maxEntries: Int = CapabilityProbeStore.MAX_ENTRIES) =
        CapabilityProbeStore(File(tmp.root, "probes.json"), maxEntries)

    private fun fp(driver: String = "wrapper", wine: String = "w1", env: Map<String, String> = emptyMap()) =
        CapabilityFingerprint(
            buildMap {
                put("driver", driver)
                put("wine", wine)
                env.forEach { (k, v) -> put("env.$k", v) }
            },
        )

    private fun payload(tag: String) = JSONObject().put("tag", tag)

    private fun decode(json: JSONObject) = json.getString("tag")

    private suspend fun run(
        store: CapabilityProbeStore,
        fp: CapabilityFingerprint,
        tag: String = "ok",
        failure: String? = null,
    ) = store.awaitOutcome(
        kind = "k",
        fingerprint = fp,
        decode = { decode(it) },
    ) {
        CapabilityProbeRun(if (failure == null) tag else null, if (failure == null) payload(tag) else null, failure, 5)
    }

    @Test
    fun fingerprintKeyCoversEveryComponent() {
        val base = fp()
        assertNotEquals(base.key, fp(driver = "turnip").key)
        assertNotEquals(base.key, fp(wine = "w2").key)
        assertNotEquals(base.key, fp(env = mapOf("VK_X" to "1")).key)
        assertEquals(base.key, fp().key)
    }

    @Test
    fun storedComponentsSurviveRoundTrip() = runBlocking {
        val s = store()
        val f = fp(env = mapOf("VK_ICD_FILENAMES" to "/x.json"))
        run(s, f)
        val entry = CapabilityProbeStore(File(tmp.root, "probes.json")).peek(f.key)
        assertNotNull(entry)
        assertEquals("wrapper", entry!!.components["driver"])
        assertEquals("/x.json", entry.components["env.VK_ICD_FILENAMES"])
        assertEquals("ok", decode(entry.payload!!))
    }

    @Test
    fun multiSlotHitsAfterDriverSwitchABA() = runBlocking {
        val s = store()
        val a = fp(driver = "wrapper")
        val b = fp(driver = "turnip")
        run(s, a, tag = "A")
        run(s, b, tag = "B")
        // B→A: must hit the stored A entry, not re-probe.
        assertFalse(s.needsRun(a))
        val hit = s.awaitOutcome("k", a, { decode(it) }) { error("must not run") }
        assertTrue(hit.fromCache)
        assertEquals("A", hit.result)
        // And B still hits after A was touched.
        assertFalse(s.needsRun(b))
    }

    @Test
    fun lruEvictsOldestPastBound() = runBlocking {
        val s = store(maxEntries = 2)
        val a = fp(driver = "a")
        val b = fp(driver = "b")
        val c = fp(driver = "c")
        run(s, a)
        run(s, b)
        run(s, c)
        assertNull(s.peek(a.key))
        assertNotNull(s.peek(b.key))
        assertNotNull(s.peek(c.key))
    }

    @Test
    fun missExplainsChangedComponents() = runBlocking {
        val s = store()
        run(s, fp(driver = "wrapper", wine = "w1"))
        assertTrue("graphics driver" in s.explainMiss(fp(driver = "turnip")))
        assertTrue("wine" in s.explainMiss(fp(driver = "turnip", wine = "w2")))
    }

    @Test
    fun coldStoreMissSaysSo() {
        assertEquals(listOf("no cached result"), store().explainMiss(fp()))
    }

    @Test
    fun failureRetriesThenGoesTerminal() = runBlocking {
        val s = store()
        val f = fp()
        repeat(CapabilityProbeStore.MAX_FAILURE_ATTEMPTS) { i ->
            assertTrue("attempt ${i + 1} should run", s.needsRun(f))
            run(s, f, failure = "boom ${i + 1}")
        }
        val entry = s.peek(f.key)
        assertEquals(CapabilityProbeStore.MAX_FAILURE_ATTEMPTS, entry!!.failureCount)
        assertFalse("terminal failure must not re-run", s.needsRun(f))
        // Terminal failure still serves its reason from cache.
        val outcome = s.awaitOutcome("k", f, { decode(it) }) { error("must not run") }
        assertEquals("boom 3", outcome.failure)
        assertTrue(outcome.fromCache)
    }

    @Test
    fun successResetsFailureCount() = runBlocking {
        val s = store()
        val f = fp()
        run(s, f, failure = "boom")
        run(s, f)
        val entry = s.peek(f.key)
        assertTrue(entry!!.succeeded)
        assertEquals(0, entry.failureCount)
        assertFalse(s.needsRun(f))
    }

    @Test
    fun invalidateForcesReprobe() = runBlocking {
        val s = store()
        val f = fp()
        repeat(CapabilityProbeStore.MAX_FAILURE_ATTEMPTS) { run(s, f, failure = "boom") }
        assertFalse(s.needsRun(f))
        s.invalidate(f)
        assertTrue(s.needsRun(f))
    }

    @Test
    fun corruptFileReadsAsMiss() = runBlocking {
        val s = store()
        run(s, fp())
        File(tmp.root, "probes.json").writeText("{ not json !!!")
        assertTrue(s.needsRun(fp()))
        assertNull(s.mru())
    }

    @Test
    fun unknownSchemaReadsAsMiss() = runBlocking {
        val s = store()
        run(s, fp())
        File(
            tmp.root,
            "probes.json",
        ).writeText(JSONObject().put("store", 99).put("entries", org.json.JSONArray()).toString())
        assertTrue(s.needsRun(fp()))
    }

    @Test
    fun singleFlightSharesOneRun() = runBlocking {
        val s = store()
        val f = fp()
        val runs = AtomicInteger(0)
        val first = async {
            s.awaitOutcome("k", f, { decode(it) }) {
                runs.incrementAndGet()
                delay(50)
                CapabilityProbeRun("r", payload("r"), null, 50)
            }
        }
        yield()
        val second = async {
            s.awaitOutcome("k", f, { decode(it) }) {
                runs.incrementAndGet()
                CapabilityProbeRun("r2", payload("r2"), null, 1)
            }
        }
        val a = first.await()
        val b = second.await()
        assertEquals(1, runs.get())
        assertEquals("r", a.result)
        assertEquals("r", b.result)
    }
}
