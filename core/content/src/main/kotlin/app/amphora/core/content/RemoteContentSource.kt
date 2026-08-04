package app.amphora.core.content

import android.content.Context
import app.amphora.core.content.model.ComponentId
import app.amphora.core.content.model.ContentArtifact
import app.amphora.core.content.model.ManifestEntry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Device-side content source for SHA-pinned WCP/archive components.
 *
 * Installed components are returned without network access. Cache misses use a
 * resumable verified download, then hand the archive to [ContentAssetInstaller].
 * After a hit or install, [ContentAssetInstaller.reconcileToPin] drops sibling
 * installs so pin bumps replace rather than accumulate.
 */
class RemoteContentSource(
    private val context: Context,
    private val catalog: ContentCatalog,
    private val installer: ContentAssetInstaller,
    private val downloader: VerifiedAssetDownloader,
    private val urlResolver: RemoteUrlResolver,
    private val progressBus: ProvisionProgressBus? = null,
) : ContentSource {
    private val locks = ConcurrentHashMap<ComponentId, Mutex>()

    override suspend fun resolve(component: ComponentId): ContentArtifact {
        val manifest = catalog.require()
        val entry =
            manifest.entry(component)
                ?: throw NoSuchElementException("No manifest entry for '${component.value}'")
        require(entry.kind != ManifestEntry.Kind.ROOTFS) {
            "ROOTFS is managed by RootfsInstaller"
        }
        if (installer.isInstalled(entry)) {
            installer.reconcileToPin(entry)
            return resolved(entry)
        }

        return locks.getOrPut(component) { Mutex() }.withLock {
            if (installer.isInstalled(entry)) {
                installer.reconcileToPin(entry)
                return@withLock resolved(entry)
            }
            val sha =
                requireNotNull(entry.sha256) {
                    "Remote component ${entry.assetPath} must have a pinned SHA-256"
                }
            progressBus?.update(
                ProvisionProgress(
                    stage = "package",
                    detail = entry.assetPath,
                    bytesDownloaded = 0,
                    totalBytes = entry.size,
                ),
            )
            val archive =
                downloader.acquire(
                    root = ContentPackageCache.root(context),
                    relativePath = entry.assetPath,
                    remoteUrl = urlResolver.resolve(entry, manifest.wcpCatalogUrl),
                    expectedSha256 = sha,
                    expectedSize = entry.size,
                    label = entry.assetPath,
                )
            progressBus?.update(
                ProvisionProgress(stage = "install", detail = entry.assetPath),
            )
            val installed = installer.install(entry, archive)
            ContentArtifact.Resolved(entry.component, installed, entry.version)
        }
    }

    private fun resolved(entry: ManifestEntry): ContentArtifact.Resolved = ContentArtifact.Resolved(
        component = entry.component,
        path = installer.resolvedPath(entry),
        version = entry.version,
    )
}
