package app.amphora.core.content

import android.content.Context
import app.amphora.core.content.model.ComponentId
import app.amphora.core.content.model.ContentArtifact
import app.amphora.core.content.model.ManifestEntry
import app.amphora.core.content.model.id
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Device-side content source for SHA-pinned WCP/archive components.
 *
 * Installed components are returned without network access. Cache misses use a
 * resumable verified download and the same kernel installer as bundled assets.
 */
class RemoteContentSource(
    private val context: Context,
    private val manifest: ContentManifest,
    private val installer: BundledAssetInstaller,
    private val downloader: VerifiedAssetDownloader,
    private val urlResolver: RemoteUrlResolver,
) : ContentSource {
    private val locks = ConcurrentHashMap<ComponentId, Mutex>()

    override suspend fun resolve(component: ComponentId): ContentArtifact {
        val entry = manifest.entry(component)
            ?: throw NoSuchElementException("No manifest entry for '${component.value}'")
        require(entry.kind != ManifestEntry.Kind.ROOTFS) {
            "ROOTFS is managed by RootfsInstaller"
        }
        if (installer.isInstalled(entry)) return resolved(entry)

        return locks.getOrPut(component) { Mutex() }.withLock {
            if (installer.isInstalled(entry)) return@withLock resolved(entry)
            val sha = requireNotNull(entry.sha256) {
                "Remote component ${entry.assetPath} must have a pinned SHA-256"
            }
            val archive = downloader.acquire(
                root = File(context.cacheDir, "amphora-packages"),
                relativePath = entry.assetPath,
                remoteUrl = urlResolver.resolve(entry),
                expectedSha256 = sha,
                expectedSize = entry.size,
            )
            val installed = installer.install(entry, archive)
            ContentArtifact.Resolved(entry.component, installed, entry.version)
        }
    }

    private fun resolved(entry: ManifestEntry): ContentArtifact.Resolved =
        ContentArtifact.Resolved(
            component = entry.component,
            path = installer.resolvedPath(entry),
            version = entry.version,
        )
}
