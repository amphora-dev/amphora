package app.amphora.core.engine

import android.content.Context
import app.amphora.core.content.AssetDigest
import app.amphora.core.content.ContentAssetInstaller
import app.amphora.core.content.ContentManifest
import app.amphora.core.content.RuntimeAssetLocalOverride
import app.amphora.core.content.RuntimeAssetProvisioner
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.ManifestEntry
import app.amphora.core.engine.model.ContentComponentHealth
import app.amphora.core.engine.model.ContentHealthSnapshot
import app.amphora.core.engine.model.RuntimeAssetHealth
import app.amphora.core.rootfs.RootfsInstaller
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the local health of every manifest-managed component and runtime asset.
 *
 * The internal constructor keeps JVM tests on real temporary directories while
 * making every Android path decision explicit. Production adapts those paths to
 * Amphora and Winlator's canonical storage locations in the injected constructor.
 */
@Singleton
class ContentHealthScanner
internal constructor(
    private val runtimeAssetsDirectory: File,
    private val imageFsResidue: File,
    private val contentTypeDirectoryResolver: ContentTypeDirectoryResolver,
    private val currentRootfsVersion: suspend () -> String?,
    private val isComponentInstalled: (ManifestEntry) -> Boolean,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        rootfsInstaller: RootfsInstaller,
        assetInstaller: ContentAssetInstaller,
    ) : this(
        runtimeAssetsDirectory = RuntimeAssetProvisioner.runtimeAssetsDir(context),
        imageFsResidue = File(context.filesDir, IMAGE_FS_RESIDUE_NAME),
        contentTypeDirectoryResolver =
        ContentTypeDirectoryResolver { contentTypeName ->
            ContentProfile.ContentType
                .getTypeByName(contentTypeName)
                ?.let { ContentsManager.getContentTypeDir(context, it) }
        },
        currentRootfsVersion = rootfsInstaller::currentVersion,
        isComponentInstalled = assetInstaller::isInstalled,
    )

    suspend fun scan(manifest: ContentManifest): ContentHealthSnapshot = ContentHealthSnapshot(
        components = scanComponents(manifest),
        runtimeAssets = scanRuntimeAssets(manifest),
        imageFsResidue = imageFsResidue.exists(),
    )

    private suspend fun scanComponents(manifest: ContentManifest): List<ContentComponentHealth> =
        ContentComponent.entries.map { component ->
            val entry = manifest.entry(component)
            val pinned = entry?.pinLabel()
            val installedAtPin =
                entry != null &&
                    component != ContentComponent.ROOTFS &&
                    isComponentInstalled(entry)
            val installed =
                if (component == ContentComponent.ROOTFS) {
                    currentRootfsVersion()
                } else {
                    installedLabel(entry, installedAtPin)
                }
            val state =
                when {
                    entry == null -> ContentComponentHealth.State.NO_PIN
                    installed == null -> ContentComponentHealth.State.MISSING
                    component == ContentComponent.ROOTFS && installed != pinned ->
                        ContentComponentHealth.State.UPDATE
                    component != ContentComponent.ROOTFS && !installedAtPin ->
                        ContentComponentHealth.State.UPDATE
                    else -> ContentComponentHealth.State.READY
                }
            ContentComponentHealth(
                component = component,
                pinned = pinned,
                installed = installed,
                state = state,
            )
        }

    private fun scanRuntimeAssets(manifest: ContentManifest): List<RuntimeAssetHealth> =
        manifest.runtimeAssets().map { entry ->
            val file = File(runtimeAssetsDirectory, entry.assetPath)
            val localOverrideSha =
                if (RuntimeAssetLocalOverride.isActive(file)) {
                    RuntimeAssetLocalOverride.markerFile(file).readText().trim().lowercase()
                } else {
                    null
                }
            val installedSha = AssetDigest.pinnedSha(file)
            val state =
                when {
                    localOverrideSha != null -> RuntimeAssetHealth.State.LOCAL_OVERRIDE
                    !file.isFile -> RuntimeAssetHealth.State.MISSING
                    installedSha == null -> RuntimeAssetHealth.State.UNVERIFIED
                    installedSha != entry.sha256.lowercase() -> RuntimeAssetHealth.State.MISMATCH
                    entry.size != null && file.length() != entry.size ->
                        RuntimeAssetHealth.State.MISMATCH
                    else -> RuntimeAssetHealth.State.READY
                }
            RuntimeAssetHealth(
                assetPath = entry.assetPath,
                pinnedSha = entry.sha256.lowercase(),
                installedSha = localOverrideSha ?: installedSha,
                sizeBytes = entry.size,
                state = state,
            )
        }

    private fun installedLabel(entry: ManifestEntry?, installedAtPin: Boolean): String? {
        if (entry == null) return null
        if (installedAtPin) return entry.pinLabel()
        if (entry.kind != ManifestEntry.Kind.WCP) return null
        val contentType = entry.contentType ?: return null
        return contentTypeDirectoryResolver
            .resolve(contentType)
            ?.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
    }

    fun interface ContentTypeDirectoryResolver {
        fun resolve(contentTypeName: String): File?
    }

    private companion object {
        const val IMAGE_FS_RESIDUE_NAME = "imagefs.olddead"
    }
}

private fun ManifestEntry.pinLabel(): String = verName ?: version
