package app.amphora.core.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import app.amphora.core.content.ContentAssetInstaller
import app.amphora.core.content.model.ManifestEntry
import app.amphora.core.content.model.id
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.io.TarCompressorUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred

/**
 * `:core:engine` concretion of [ContentAssetInstaller]: installs downloaded
 * content using the ported `com.winlator.cmod` kernel.
 *
 * - [ManifestEntry.Kind.ARCHIVE]: `TarCompressorUtils.extract` the staged archive
 *   into `filesDir/amphora-content/<component>/<version>/`.
 * - [ManifestEntry.Kind.WCP]: hand the staged `.wcp` to
 *   `ContentsManager.extraContentFile` + `finishInstallContent`, installing under
 *   `filesDir/contents/<type>/<verName>-<verCode>/`.
 *
 * Pin bumps that change `version` / `verName-verCode` used to leave sibling
 * directories orphaned. [reconcileToPin] deletes those siblings once the current
 * pin is installed — update replaces, not accumulates.
 */
@Singleton
class WinlatorContentAssetInstaller
@Inject
constructor(@ApplicationContext private val context: Context) :
    ContentAssetInstaller {
    override fun resolvedPath(entry: ManifestEntry): File = when (entry.kind) {
        ManifestEntry.Kind.ARCHIVE ->
            File(context.filesDir, "amphora-content/${entry.component.id.value}/${entry.version}")
        ManifestEntry.Kind.WCP ->
            ContentsManager.getInstallDir(context, profileFor(entry) ?: noProfile(entry))
        ManifestEntry.Kind.ROOTFS -> ImageFs.find(context).rootDir
    }

    override fun isInstalled(entry: ManifestEntry): Boolean = when (entry.kind) {
        ManifestEntry.Kind.ARCHIVE ->
            resolvedPath(entry).isDirectory &&
                (resolvedPath(entry).list()?.isNotEmpty() == true)
        ManifestEntry.Kind.WCP -> resolvedPath(entry).isDirectory
        ManifestEntry.Kind.ROOTFS -> false
    }

    override suspend fun install(entry: ManifestEntry, archiveFile: File): File {
        val installed =
            when (entry.kind) {
                ManifestEntry.Kind.ARCHIVE -> installArchive(entry, archiveFile)
                ManifestEntry.Kind.WCP -> installWcp(entry, archiveFile)
                ManifestEntry.Kind.ROOTFS ->
                    throw UnsupportedOperationException("ROOTFS is managed by RootfsInstaller")
            }
        reconcileToPin(entry)
        return installed
    }

    override fun reconcileToPin(entry: ManifestEntry): Int {
        if (!isInstalled(entry)) return 0
        return when (entry.kind) {
            ManifestEntry.Kind.WCP -> pruneWcpSiblings(entry)
            ManifestEntry.Kind.ARCHIVE -> pruneArchiveSiblings(entry)
            ManifestEntry.Kind.ROOTFS -> 0
        }
    }

    // --- ARCHIVE -------------------------------------------------------------

    private fun installArchive(entry: ManifestEntry, archiveFile: File): File {
        val dest = resolvedPath(entry)
        if (dest.isDirectory) return dest
        dest.mkdirs()
        val type =
            when (entry.compression) {
                ManifestEntry.Compression.ZSTD -> TarCompressorUtils.Type.ZSTD
                ManifestEntry.Compression.XZ -> TarCompressorUtils.Type.XZ
            }
        val ok = TarCompressorUtils.extract(type, archiveFile, dest)
        check(ok) { "Archive extract failed for ${entry.component.id.value} (${entry.assetPath})" }
        return dest
    }

    private fun pruneArchiveSiblings(entry: ManifestEntry): Int {
        val keep = resolvedPath(entry)
        val parent = keep.parentFile ?: return 0
        if (!parent.isDirectory) return 0
        var removed = 0
        for (child in parent.listFiles().orEmpty()) {
            if (!child.isDirectory) continue
            if (child.canonicalFile == keep.canonicalFile) continue
            if (FileUtils.delete(child)) {
                removed++
                Log.i(TAG, "Removed stale ARCHIVE install ${child.name} (keep=${keep.name})")
            } else {
                Log.w(TAG, "Failed to delete stale ARCHIVE install ${child.absolutePath}")
            }
        }
        return removed
    }

    // --- WCP -----------------------------------------------------------------

    private suspend fun installWcp(entry: ManifestEntry, archiveFile: File): File {
        val cm = ContentsManager(context)
        val profile = awaitExtraContentInstall(cm, archiveFile)
        return ContentsManager.getInstallDir(context, profile)
    }

    private fun pruneWcpSiblings(entry: ManifestEntry): Int {
        val profile = profileFor(entry) ?: return 0
        val keep = ContentsManager.getInstallDir(context, profile)
        val typeDir = ContentsManager.getContentTypeDir(context, profile.type)
        if (!typeDir.isDirectory) return 0
        var removed = 0
        val cm = ContentsManager(context)
        for (child in typeDir.listFiles().orEmpty()) {
            if (!child.isDirectory) continue
            if (child.canonicalFile == keep.canonicalFile) continue
            val stale = profileFromInstallDir(profile.type, child) ?: continue
            try {
                cm.removeContent(stale)
                removed++
                Log.i(
                    TAG,
                    "Removed stale ${profile.type} install ${child.name} (keep=${keep.name})",
                )
            } catch (failure: Throwable) {
                Log.w(TAG, "Failed to remove stale WCP ${child.absolutePath}", failure)
            }
        }
        return removed
    }

    private fun profileFromInstallDir(
        type: ContentProfile.ContentType,
        installDir: File,
    ): ContentProfile? {
        val split = installDir.name.lastIndexOf('-')
        if (split <= 0 || split >= installDir.name.length - 1) {
            Log.w(TAG, "Skipping unreadable WCP dir ${installDir.name}")
            return null
        }
        val code = installDir.name.substring(split + 1).toIntOrNull()
        if (code == null) {
            Log.w(TAG, "Skipping WCP dir with non-int verCode ${installDir.name}")
            return null
        }
        return ContentProfile().apply {
            this.type = type
            verName = installDir.name.substring(0, split)
            verCode = code
        }
    }

    /**
     * `extraContentFile` extracts the `.wcp` to a tmp dir and validates its
     * `profile.json`; `finishInstallContent` moves it to the install dir.
     * `ERROR_EXIST` (already installed) is treated as success.
     */
    private suspend fun awaitExtraContentInstall(cm: ContentsManager, wcp: File): ContentProfile {
        val result = CompletableDeferred<ContentProfile>()
        cm.extraContentFile(
            Uri.fromFile(wcp),
            object : ContentsManager.OnInstallFinishedCallback {
                override fun onSucceed(profile: ContentProfile) {
                    cm.finishInstallContent(
                        profile,
                        object : ContentsManager.OnInstallFinishedCallback {
                            override fun onSucceed(p: ContentProfile) {
                                result.complete(p)
                            }

                            override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                                if (reason == ContentsManager.InstallFailedReason.ERROR_EXIST) {
                                    result.complete(profile)
                                } else {
                                    result.completeExceptionally(
                                        RuntimeException("finishInstallContent failed: $reason", e),
                                    )
                                }
                            }
                        },
                    )
                }

                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                    result.completeExceptionally(
                        RuntimeException("extraContentFile failed: $reason", e),
                    )
                }
            },
        )
        return result.await()
    }

    private fun profileFor(entry: ManifestEntry): ContentProfile? {
        val type =
            ContentProfile.ContentType.getTypeByName(entry.contentType ?: return null)
                ?: return null
        val verName = entry.verName ?: return null
        return ContentProfile().apply {
            this.type = type
            this.verName = verName
            this.verCode = entry.verCode ?: 0
        }
    }

    private fun noProfile(entry: ManifestEntry): ContentProfile {
        error(
            "WCP entry ${entry.component.id.value} missing contentType/verName " +
                "(needed for getInstallDir); manifest is malformed.",
        )
    }

    private companion object {
        const val TAG = "WinlatorContentAssetInstaller"
    }
}
