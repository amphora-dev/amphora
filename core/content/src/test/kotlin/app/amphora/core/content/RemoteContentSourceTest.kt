package app.amphora.core.content

import app.amphora.core.content.model.ContentArtifact
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.ManifestEntry
import app.amphora.core.content.model.id
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteContentSourceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installedComponentIsReturnedAndReconciled() = runBlocking {
        val packageCacheRoot = temporaryFolder.newFolder("packages")
        val installedPath = temporaryFolder.newFolder("installed")
        val entry = entry()
        val catalog = mockk<ContentCatalog>()
        val manifest = mockk<ContentManifest>()
        val installer = mockk<ContentAssetInstaller>()
        val downloader = mockk<VerifiedAssetDownloader>()
        val urlResolver = mockk<RemoteUrlResolver>()
        coEvery { catalog.require() } returns manifest
        every { manifest.entry(entry.component.id) } returns entry
        every { manifest.all() } returns listOf(entry)
        every { installer.isInstalled(entry) } returns true
        every { installer.reconcileToPin(entry, listOf(entry)) } returns 1
        every { installer.resolvedPath(entry) } returns installedPath

        val resolved =
            source(packageCacheRoot, catalog, installer, downloader, urlResolver)
                .resolve(entry.component.id)

        assertEquals(ContentArtifact.Resolved(entry.component, installedPath, entry.version), resolved)
        verify(exactly = 1) { installer.reconcileToPin(entry, listOf(entry)) }
        coVerify(exactly = 0) { installer.install(any(), any()) }
        coVerify(exactly = 0) {
            downloader.acquire(any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { urlResolver.resolve(any(), any()) }
    }

    @Test
    fun missingComponentDownloadsToExplicitCacheRootAndInstallsArchive() = runBlocking {
        val packageCacheRoot = temporaryFolder.newFolder("packages")
        val archive = temporaryFolder.newFile("downloaded.wcp")
        val installedPath = temporaryFolder.newFolder("installed")
        val entry = entry()
        val remoteUrl = "https://example.invalid/${entry.assetPath}"
        val catalog = mockk<ContentCatalog>()
        val manifest = mockk<ContentManifest>()
        val installer = mockk<ContentAssetInstaller>()
        val downloader = mockk<VerifiedAssetDownloader>()
        val urlResolver = mockk<RemoteUrlResolver>()
        coEvery { catalog.require() } returns manifest
        every { manifest.entry(entry.component.id) } returns entry
        every { manifest.all() } returns listOf(entry)
        every { manifest.wcpCatalogUrl } returns WCP_CATALOG_URL
        every { installer.isInstalled(entry) } returns false
        every { installer.reconcileToPin(entry, listOf(entry)) } returns 0
        every { urlResolver.resolve(entry, WCP_CATALOG_URL) } returns remoteUrl
        coEvery {
            downloader.acquire(
                root = packageCacheRoot,
                relativePath = entry.assetPath,
                remoteUrl = remoteUrl,
                expectedSha256 = requireNotNull(entry.sha256),
                expectedSize = entry.size,
                label = entry.assetPath,
            )
        } returns archive
        coEvery { installer.install(entry, archive) } returns installedPath

        val resolved =
            source(packageCacheRoot, catalog, installer, downloader, urlResolver)
                .resolve(entry.component.id)

        assertEquals(ContentArtifact.Resolved(entry.component, installedPath, entry.version), resolved)
        coVerify(exactly = 1) {
            downloader.acquire(
                root = packageCacheRoot,
                relativePath = entry.assetPath,
                remoteUrl = remoteUrl,
                expectedSha256 = requireNotNull(entry.sha256),
                expectedSize = entry.size,
                label = entry.assetPath,
            )
        }
        coVerify(exactly = 1) { installer.install(entry, archive) }
    }

    @Test
    fun rootfsIsRejectedBeforeInstallChecks() {
        val packageCacheRoot = temporaryFolder.newFolder("packages")
        val entry = entry(component = ContentComponent.ROOTFS, kind = ManifestEntry.Kind.ROOTFS)
        val catalog = mockk<ContentCatalog>()
        val manifest = mockk<ContentManifest>()
        val installer = mockk<ContentAssetInstaller>()
        val downloader = mockk<VerifiedAssetDownloader>()
        val urlResolver = mockk<RemoteUrlResolver>()
        coEvery { catalog.require() } returns manifest
        every { manifest.entry(entry.component.id) } returns entry

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    source(packageCacheRoot, catalog, installer, downloader, urlResolver)
                        .resolve(entry.component.id)
                }
            }

        assertEquals("ROOTFS is managed by RootfsInstaller", failure.message)
        verify(exactly = 0) { installer.isInstalled(any()) }
        coVerify(exactly = 0) { installer.install(any(), any()) }
    }

    @Test
    fun concurrentResolvesOfSameComponentInstallOnlyOnce() = runBlocking {
        val packageCacheRoot = temporaryFolder.newFolder("packages")
        val archive = temporaryFolder.newFile("downloaded.wcp")
        val installedPath = temporaryFolder.newFolder("installed")
        val entry = entry()
        val catalog = mockk<ContentCatalog>()
        val manifest = mockk<ContentManifest>()
        val installer = mockk<ContentAssetInstaller>()
        val downloader = mockk<VerifiedAssetDownloader>()
        val urlResolver = mockk<RemoteUrlResolver>()
        val installed = AtomicBoolean(false)
        val installChecks = AtomicInteger()
        val installStarted = CompletableDeferred<Unit>()
        val secondResolveSawMiss = CompletableDeferred<Unit>()
        val releaseInstall = CompletableDeferred<Unit>()
        coEvery { catalog.require() } returns manifest
        every { manifest.entry(entry.component.id) } returns entry
        every { manifest.all() } returns listOf(entry)
        every { manifest.wcpCatalogUrl } returns WCP_CATALOG_URL
        every { installer.isInstalled(entry) } answers {
            if (!installed.get() && installChecks.incrementAndGet() >= 3) {
                secondResolveSawMiss.complete(Unit)
            }
            installed.get()
        }
        every { installer.resolvedPath(entry) } returns installedPath
        every { installer.reconcileToPin(entry, listOf(entry)) } returns 0
        every { urlResolver.resolve(entry, WCP_CATALOG_URL) } returns REMOTE_URL
        coEvery { downloader.acquire(any(), any(), any(), any(), any(), any()) } returns archive
        coEvery { installer.install(entry, archive) } coAnswers {
            installStarted.complete(Unit)
            releaseInstall.await()
            installed.set(true)
            installedPath
        }
        val source = source(packageCacheRoot, catalog, installer, downloader, urlResolver)

        val first = async(Dispatchers.Default) { source.resolve(entry.component.id) }
        installStarted.await()
        val second = async(Dispatchers.Default) { source.resolve(entry.component.id) }
        try {
            withTimeout(5_000) { secondResolveSawMiss.await() }
        } finally {
            releaseInstall.complete(Unit)
        }
        val results = awaitAll(first, second)

        assertEquals(
            listOf(
                ContentArtifact.Resolved(entry.component, installedPath, entry.version),
                ContentArtifact.Resolved(entry.component, installedPath, entry.version),
            ),
            results,
        )
        coVerify(exactly = 1) { downloader.acquire(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { installer.install(entry, archive) }
        // Once for the install, once for the second resolve's cache hit.
        verify(exactly = 2) { installer.reconcileToPin(entry, listOf(entry)) }
    }

    private fun source(
        packageCacheRoot: java.io.File,
        catalog: ContentCatalog,
        installer: ContentAssetInstaller,
        downloader: VerifiedAssetDownloader,
        urlResolver: RemoteUrlResolver,
    ) = RemoteContentSource(
        packageCacheRoot = packageCacheRoot,
        catalog = catalog,
        installer = installer,
        downloader = downloader,
        urlResolver = urlResolver,
    )

    private fun entry(
        component: ContentComponent = ContentComponent.WINE,
        kind: ManifestEntry.Kind = ManifestEntry.Kind.WCP,
    ) = ManifestEntry(
        component = component,
        assetPath = "Proton-test.wcp",
        sha256 = "a".repeat(64),
        version = "proton-test-1",
        kind = kind,
        contentType = "proton",
        verName = "test",
        verCode = 1,
        size = 1234,
    )

    private companion object {
        const val WCP_CATALOG_URL = "https://example.invalid/default.json"
        const val REMOTE_URL = "https://example.invalid/Proton-test.wcp"
    }
}
