package app.amphora.core.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import app.amphora.core.content.ContentAssetInstaller
import app.amphora.core.content.InstalledContentPin
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
 * The manifest SHA, not the display version, is the authoritative installed
 * identity. Every successful extraction records [InstalledContentPin] inside
 * the install directory. A same-version package with a new SHA is replaced
 * atomically. Once a replacement is published, superseded installs of the same
 * content type are removed so downloaded runtime updates do not accumulate.
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
        ManifestEntry.Kind.ARCHIVE,
        ManifestEntry.Kind.WCP,
        -> InstalledContentPin.matches(resolvedPath(entry), entry.sha256)
        ManifestEntry.Kind.ROOTFS -> false
    }

    override suspend fun install(entry: ManifestEntry, archiveFile: File): File {
        if (isInstalled(entry)) return resolvedPath(entry)
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
        val parent = requireNotNull(dest.parentFile)
        val staging = File(parent, "${dest.name}.staging")
        val backup = File(parent, "${dest.name}.backup")
        recoverReplacement(dest, staging, backup)
        FileUtils.delete(staging)
        check(staging.mkdirs()) { "Cannot create ARCHIVE staging directory: $staging" }
        val type =
            when (entry.compression) {
                ManifestEntry.Compression.ZSTD -> TarCompressorUtils.Type.ZSTD
                ManifestEntry.Compression.XZ -> TarCompressorUtils.Type.XZ
            }
        val ok = TarCompressorUtils.extract(type, archiveFile, staging)
        if (!ok) {
            FileUtils.delete(staging)
            error("Archive extract failed for ${entry.component.id.value} (${entry.assetPath})")
        }
        InstalledContentPin.write(
            staging,
            requireNotNull(entry.sha256) { "ARCHIVE pin is missing for ${entry.assetPath}" },
        )
        publishReplacement(dest, staging, backup)
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
        val expectedProfile = requireNotNull(profileFor(entry)) {
            "WCP entry ${entry.component.id.value} is missing its content profile"
        }
        val dest = ContentsManager.getInstallDir(context, expectedProfile)
        val parent = requireNotNull(dest.parentFile)
        val backup = File(parent, "${dest.name}.backup")
        recoverReplacement(dest, staging = null, backup = backup)

        if (dest.exists()) {
            FileUtils.delete(backup)
            check(dest.renameTo(backup)) { "Cannot back up stale WCP install: $dest" }
        }

        val cm = ContentsManager(context)
        return try {
            val profile = awaitExtraContentInstall(cm, archiveFile, expectedProfile)
            val installed = ContentsManager.getInstallDir(context, profile)
            check(installed.canonicalFile == dest.canonicalFile && installed.isDirectory) {
                "WCP installed to unexpected directory: $installed"
            }
            InstalledContentPin.write(
                installed,
                requireNotNull(entry.sha256) { "WCP pin is missing for ${entry.assetPath}" },
            )
            FileUtils.delete(backup)
            installed
        } catch (failure: Throwable) {
            FileUtils.delete(dest)
            if (backup.exists()) {
                check(backup.renameTo(dest)) { "Cannot restore previous WCP install: $backup" }
            }
            throw failure
        }
    }

    private fun sameProfile(expected: ContentProfile, actual: ContentProfile): Boolean = expected.type == actual.type &&
        expected.verName == actual.verName &&
        expected.verCode == actual.verCode

    private fun recoverReplacement(dest: File, staging: File?, backup: File) {
        staging?.let { FileUtils.delete(it) }
        if (!backup.exists()) return
        if (dest.exists()) {
            FileUtils.delete(backup)
        } else {
            check(backup.renameTo(dest)) { "Cannot restore interrupted content install: $backup" }
        }
    }

    private fun publishReplacement(dest: File, staging: File, backup: File) {
        FileUtils.delete(backup)
        if (dest.exists()) {
            check(dest.renameTo(backup)) { "Cannot back up stale content install: $dest" }
        }
        if (!staging.renameTo(dest)) {
            if (backup.exists()) check(backup.renameTo(dest)) { "Cannot roll back content install: $backup" }
            FileUtils.delete(staging)
            error("Cannot publish content install: $staging -> $dest")
        }
        FileUtils.delete(backup)
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

    private fun profileFromInstallDir(type: ContentProfile.ContentType, installDir: File): ContentProfile? {
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
    private suspend fun awaitExtraContentInstall(
        cm: ContentsManager,
        wcp: File,
        expectedProfile: ContentProfile,
    ): ContentProfile {
        val result = CompletableDeferred<ContentProfile>()
        cm.extraContentFile(
            Uri.fromFile(wcp),
            object : ContentsManager.OnInstallFinishedCallback {
                override fun onSucceed(profile: ContentProfile) {
                    if (!sameProfile(expectedProfile, profile)) {
                        result.completeExceptionally(
                            IllegalArgumentException(
                                "WCP profile does not match manifest: " +
                                    "expected=${ContentsManager.getEntryName(expectedProfile)} " +
                                    "actual=${ContentsManager.getEntryName(profile)}",
                            ),
                        )
                        return
                    }
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
