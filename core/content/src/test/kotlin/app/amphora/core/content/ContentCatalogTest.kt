package app.amphora.core.content

import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.content.model.ContentComponent
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContentCatalogTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun requireUsesValidDiskCacheBeforeFetching() = runBlocking {
        val cacheFile = cacheFile().apply { writeText(ContentManifestTest.SAMPLE) }
        val fetches = AtomicInteger()
        val catalog =
            catalog(cacheFile) {
                fetches.incrementAndGet()
                error("disk cache should prevent a fetch")
            }

        val manifest = catalog.require()

        assertEquals(0, fetches.get())
        assertSame(manifest, catalog.peek())
        val status = catalog.status.value as ContentCatalog.Status.Ready
        assertSame(manifest, status.manifest)
        assertEquals(cacheFile.toURI().toString(), status.sourceUrl)
    }

    @Test
    fun concurrentRequiresShareOneFetch() = runBlocking {
        val fetches = AtomicInteger()
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val catalog =
            catalog(cacheFile()) {
                fetches.incrementAndGet()
                fetchStarted.complete(Unit)
                releaseFetch.await()
                ContentManifestTest.SAMPLE
            }

        val first = async(Dispatchers.Default) { catalog.require() }
        fetchStarted.await()
        val second = async(Dispatchers.Default) { catalog.require() }
        releaseFetch.complete(Unit)

        assertSame(first.await(), second.await())
        assertEquals(1, fetches.get())
        assertTrue(catalog.status.value is ContentCatalog.Status.Ready)
    }

    @Test
    fun malformedRefreshPreservesLastKnownGoodManifestAndCache() = runBlocking {
        val cacheFile = cacheFile().apply { writeText(ContentManifestTest.SAMPLE) }
        val catalog = catalog(cacheFile) { "{not valid json" }
        val cached = catalog.require()
        val cachedStatus = catalog.status.value

        val refreshed = catalog.refresh()

        assertSame(cached, refreshed)
        assertSame(cachedStatus, catalog.status.value)
        assertEquals(ContentManifestTest.SAMPLE, cacheFile.readText())
    }

    @Test
    fun fetchFailureWithoutCachePublishesFailedStatus() = runBlocking {
        val expected = IOException("offline")
        val catalog = catalog(cacheFile()) { throw expected }

        val thrown =
            try {
                catalog.require()
                null
            } catch (failure: IOException) {
                failure
            }

        assertSame(expected, thrown)
        assertEquals(ContentCatalog.Status.Failed("offline"), catalog.status.value)
        assertEquals(null, catalog.peek())
    }

    @Test
    fun requireAppliesDevPinOverlayToDiskCache() = runBlocking {
        val cacheFile = cacheFile().apply { writeText(ContentManifestTest.SAMPLE) }
        val contentDir = cacheFile.parentFile!!
        val newSha = "9".repeat(64)
        DevPinOverlay.write(
            contentDir,
            DevPins(
                components =
                mapOf("box64" to ComponentPinPatch(sha256 = newSha, size = 123L)),
            ),
        )
        val catalog = catalog(cacheFile) { error("should not fetch") }

        val manifest = catalog.require()

        assertEquals(newSha, manifest.entry(ContentComponent.BOX64)!!.sha256)
        assertTrue(catalog.isComponentOverridden(ContentComponent.BOX64))
        assertFalse(catalog.isComponentOverridden(ContentComponent.WINE))
        val status = catalog.status.value as ContentCatalog.Status.Ready
        assertEquals(setOf(ContentComponent.BOX64), status.overriddenComponents)
    }

    @Test
    fun clearOverlayRestoresRemotePinOnNextRequire() = runBlocking {
        val cacheFile = cacheFile().apply { writeText(ContentManifestTest.SAMPLE) }
        val contentDir = cacheFile.parentFile!!
        DevPinOverlay.write(
            contentDir,
            DevPins(components = mapOf("box64" to ComponentPinPatch(sha256 = "8".repeat(64)))),
        )
        val catalog = catalog(cacheFile) { error("should not fetch") }
        assertEquals("8".repeat(64), catalog.require().entry(ContentComponent.BOX64)!!.sha256)

        DevPinOverlay.clear(contentDir)
        // Force reload: drop Ready by constructing a fresh catalog on the same cache.
        val catalog2 = catalog(cacheFile) { error("should not fetch") }
        val restored = catalog2.require()
        assertEquals(
            ContentManifest.parse(ContentManifestTest.SAMPLE).entry(ContentComponent.BOX64)!!.sha256,
            restored.entry(ContentComponent.BOX64)!!.sha256,
        )
        assertFalse(catalog2.isComponentOverridden(ContentComponent.BOX64))
    }

    @Test
    fun malformedOverlayIsIgnored() = runBlocking {
        val cacheFile = cacheFile().apply { writeText(ContentManifestTest.SAMPLE) }
        DevPinOverlay.file(cacheFile.parentFile!!).writeText("{broken")
        val catalog = catalog(cacheFile) { error("should not fetch") }

        val manifest = catalog.require()
        assertEquals(
            ContentManifest.parse(ContentManifestTest.SAMPLE).entry(ContentComponent.BOX64)!!.sha256,
            manifest.entry(ContentComponent.BOX64)!!.sha256,
        )
        assertTrue(catalog.overriddenComponents.isEmpty())
    }

    @Test
    fun refreshStillAppliesOverlayAfterRemoteFetch() = runBlocking {
        val cacheFile = cacheFile()
        val contentDir = cacheFile.parentFile!!
        val overlaySha = "7".repeat(64)
        DevPinOverlay.write(
            contentDir,
            DevPins(components = mapOf("box64" to ComponentPinPatch(sha256 = overlaySha))),
        )
        val remoteSha = "d".repeat(64) // SAMPLE box64 sha
        val catalog = catalog(cacheFile) { ContentManifestTest.SAMPLE }

        val refreshed = catalog.refresh()

        assertEquals(overlaySha, refreshed.entry(ContentComponent.BOX64)!!.sha256)
        assertTrue(catalog.isComponentOverridden(ContentComponent.BOX64))
        // Disk cache still stores the remote (unpatched) JSON.
        assertTrue(cacheFile.readText().contains(remoteSha))
    }

    private fun cacheFile(): File = File(temporaryFolder.root, "content/content_manifest.json").also {
        requireNotNull(it.parentFile).mkdirs()
    }

    private fun catalog(cacheFile: File, fetchManifest: suspend (String) -> String): ContentCatalog = ContentCatalog(
        cacheFile = cacheFile,
        dispatchers = ImmediateDispatchers,
        sourceUrl = { REMOTE_URL },
        fetchManifest = fetchManifest,
    )

    private object ImmediateDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private companion object {
        const val REMOTE_URL = "https://example.test/content_manifest.json"
    }
}
