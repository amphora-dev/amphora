package app.amphora.core.content

import app.amphora.core.common.dispatcher.DispatcherProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    private fun cacheFile(): File =
        File(temporaryFolder.root, "content/content_manifest.json").also {
            it.parentFile.mkdirs()
        }

    private fun catalog(
        cacheFile: File,
        fetchManifest: suspend (String) -> String,
    ): ContentCatalog =
        ContentCatalog(
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
