package app.amphora.core.engine

import android.content.Context
import android.net.Uri
import app.amphora.core.content.BundledAssetInstaller
import app.amphora.core.content.model.ManifestEntry
import app.amphora.core.content.model.id
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.shared.io.TarCompressorUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `:core:engine` concretion of [BundledAssetInstaller]: provisions bundled
 * content using the ported `com.winlator.cmod` kernel.
 *
 * - [ManifestEntry.Kind.ARCHIVE]: `TarCompressorUtils.extract` the staged archive
 *   into `filesDir/amphora-content/<component>/<version>/`.
 * - [ManifestEntry.Kind.WCP]: hand the staged `.wcp` to
 *   `ContentsManager.extraContentFile` (local `nativeExtractArchive`, NOT the D4
 *   `nativeDownloadFile` stub) + `finishInstallContent`, installing it under
 *   `filesDir/contents/<type>/<verName>-<verCode>/` -- the same path the test's
 *   `curl`+`adb push` workaround populated, so `syncContents` / `createContainer`
 *   find it identically.
 *
 * Lives in `:core:engine` (not `:core:content`) per the DIP pattern: it needs
 * `TarCompressorUtils` / `ContentsManager`, which the dep graph keeps in
 * `:core:engine` (`engine -> content`, never the reverse). The contract
 * [BundledAssetInstaller] stays in `:core:content`.
 */
@Singleton
class WinlatorBundledAssetInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) : BundledAssetInstaller {

    override fun resolvedPath(entry: ManifestEntry): File = when (entry.kind) {
        ManifestEntry.Kind.ARCHIVE ->
            File(context.filesDir, "amphora-content/${entry.component.id.value}/${entry.version}")
        ManifestEntry.Kind.WCP ->
            ContentsManager.getInstallDir(context, profileFor(entry) ?: noProfile(entry))
        ManifestEntry.Kind.ROOTFS ->
            File(context.filesDir, "imagefs") // informational only; resolve() rejects ROOTFS.
    }

    override fun isInstalled(entry: ManifestEntry): Boolean = when (entry.kind) {
        ManifestEntry.Kind.ARCHIVE -> resolvedPath(entry).isDirectory &&
            (resolvedPath(entry).list()?.isNotEmpty() == true)
        ManifestEntry.Kind.WCP -> resolvedPath(entry).isDirectory
        ManifestEntry.Kind.ROOTFS -> false
    }

    override suspend fun install(entry: ManifestEntry, archiveFile: File): File = when (entry.kind) {
        ManifestEntry.Kind.ARCHIVE -> installArchive(entry, archiveFile)
        ManifestEntry.Kind.WCP -> installWcp(entry, archiveFile)
        ManifestEntry.Kind.ROOTFS ->
            throw UnsupportedOperationException("ROOTFS is managed by RootfsInstaller")
    }

    // --- ARCHIVE -------------------------------------------------------------

    private fun installArchive(entry: ManifestEntry, archiveFile: File): File {
        val dest = resolvedPath(entry)
        if (dest.isDirectory) return dest // idempotent
        dest.mkdirs()
        val type = when (entry.compression) {
            ManifestEntry.Compression.ZSTD -> TarCompressorUtils.Type.ZSTD
            ManifestEntry.Compression.XZ -> TarCompressorUtils.Type.XZ
        }
        val ok = TarCompressorUtils.extract(type, archiveFile, dest)
        check(ok) { "Archive extract failed for ${entry.component.id.value} (${entry.assetPath})" }
        return dest
    }

    // --- WCP -----------------------------------------------------------------

    private suspend fun installWcp(entry: ManifestEntry, archiveFile: File): File {
        val cm = ContentsManager(context)
        val profile = awaitExtraContentInstall(cm, archiveFile)
        return ContentsManager.getInstallDir(context, profile)
    }

    /**
     * `extraContentFile` extracts the `.wcp` to a tmp dir and validates its
     * `profile.json`; `finishInstallContent` moves it to the install dir. Both
     * invoke their callbacks synchronously (blocking native extract), but a
     * [CompletableDeferred] guards completion and survives a synchronous resume.
     * `ERROR_EXIST` (already installed from a prior run / another path) is treated
     * as success -- the install dir is already populated.
     */
    private suspend fun awaitExtraContentInstall(
        cm: ContentsManager,
        wcp: File,
    ): ContentProfile {
        val result = CompletableDeferred<ContentProfile>()
        cm.extraContentFile(Uri.fromFile(wcp), object : ContentsManager.OnInstallFinishedCallback {
            override fun onSucceed(profile: ContentProfile) {
                cm.finishInstallContent(profile, object : ContentsManager.OnInstallFinishedCallback {
                    override fun onSucceed(p: ContentProfile) { result.complete(p) }

                    override fun onFailed(
                        reason: ContentsManager.InstallFailedReason,
                        e: Exception?,
                    ) {
                        if (reason == ContentsManager.InstallFailedReason.ERROR_EXIST) {
                            result.complete(profile) // already installed; dir is populated
                        } else {
                            result.completeExceptionally(
                                RuntimeException("finishInstallContent failed: $reason", e)
                            )
                        }
                    }
                })
            }

            override fun onFailed(
                reason: ContentsManager.InstallFailedReason,
                e: Exception?,
            ) {
                result.completeExceptionally(
                    RuntimeException("extraContentFile failed: $reason", e)
                )
            }
        })
        return result.await()
    }

    /** Minimal profile for [ContentsManager.getInstallDir] path computation. */
    private fun profileFor(entry: ManifestEntry): ContentProfile? {
        val type = ContentProfile.ContentType.getTypeByName(entry.contentType ?: return null)
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
}
