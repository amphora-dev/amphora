package app.amphora.core.engine

import android.content.Context
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.content.ContentManifest
import app.amphora.core.content.VerifiedAssetDownloader
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.rootfs.RootfsInstaller
import app.amphora.core.rootfs.model.RootfsSpec
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.io.TarCompressorUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * amphora's [RootfsInstaller], backed by the ported `com.winlator.cmod` kernel
 * ([ImageFs] + [TarCompressorUtils]). Adapts WinNative's `ImageFsInstaller`
 * (RFC §7 / §11 / D7) into the clean amphora contract.
 *
 * **Scope (imagefs base only):** extracts the zstd-compressed imagefs archive
 * (`imagefs.tzst`, sharded as `imagefs.partNN.tzst`) into [RootfsSpec.targetRoot]
 * and writes the version marker (`.winlator/.img_version`). Wine / box64 / Turnip
 * / DXVK binaries are installed by [WineSessionPreparer] / [ContentSource]; the
 * Steam DLL marker clear (`clearSteamDllMarkers`) and container-version reset
 * (`resetContainerImgVersions`) from WinNative are stripped -- Steam is a
 * non-target (RFC §7) and container state belongs to `:core:container` (P4).
 * Activity / progress UI from WinNative was removed; amphora surfaces progress
 * via Compose instead.
 *
 * **Why this lives in `:core:engine`, not `:core:rootfs`:** the dependency graph
 * is `engine -> rootfs` (RFC §6), so `:core:rootfs` cannot see
 * [TarCompressorUtils] / [ImageFs] (they live in the ported `com.winlator.cmod`
 * kernel under `:core:engine`). The [RootfsInstaller] *contract* stays in
 * `:core:rootfs`; the concretion lives in `:core:engine` next to the kernel it
 * adapts (Dependency Inversion -- the low module owns the abstraction, the high
 * module owns the concretion). `:core:rootfs` therefore needs no Hilt for MVP.
 *
 * **termuxfs rpath (D7, TODO):** Wine `.so` rpath is baked to
 * `/data/data/com.termux/files/usr/lib`; the `winlator-imagefs` build is expected
 * to reproduce that path inside imagefs, but final verification waits on the real
 * asset + termuxfs SHA lock (P2 asset acquisition).
 *
 * **Compile-only:** the extraction path is correct against the restored
 * [TarCompressorUtils] native backend (P2: `native_content_io.cpp` extraction
 * restored with zstd+xz; curl/download stubbed per D4). End-to-end verification
 * waits on the real ~869 MB `imagefs.tzst` asset (`winlator-imagefs` build
 * product, P2 asset item -- currently a 134-byte placeholder in WinNative assets).
 */
@Singleton
class ImageFsRootfsInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val manifest: ContentManifest,
    private val downloader: VerifiedAssetDownloader,
) : RootfsInstaller {

    override suspend fun ensureInstalled(spec: RootfsSpec): Boolean = withContext(dispatchers.io) {
        val rootDir = File(spec.targetRoot)
        val imageFs = ImageFs.find(rootDir)
        val desired = spec.imagefsVersion.toIntOrNull() ?: 0

        if (desired > 0 && imageFs.isValid && imageFs.getVersion() >= desired) {
            return@withContext true // already up to date
        }

        val entry = requireNotNull(manifest.entry(ContentComponent.ROOTFS)) {
            "content manifest does not define rootfs"
        }
        val archive = downloader.acquire(
            root = File(context.cacheDir, "amphora-rootfs"),
            relativePath = entry.assetPath,
            remoteUrl = requireNotNull(entry.remoteUrl) { "rootfs remoteUrl is missing" },
            expectedSha256 = requireNotNull(entry.sha256) { "rootfs SHA-256 is missing" },
            expectedSize = entry.size,
        )
        installAtomically(rootDir, archive, desired)
    }

    override suspend fun currentVersion(): String? = withContext(dispatchers.io) {
        // Conventional root -- matches the kernel's ImageFs.find(context) = filesDir/imagefs.
        val imageFs = ImageFs.find(context)
        if (!imageFs.isValid) null else imageFs.getVersion().toString()
    }

    private fun installAtomically(rootDir: File, archive: File, desired: Int): Boolean {
        val staging = File(rootDir.parentFile, "${rootDir.name}.staging")
        val backup = File(rootDir.parentFile, "${rootDir.name}.backup")
        recoverInterruptedInstall(rootDir, staging, backup)

        FileUtils.delete(staging)
        check(staging.mkdirs()) { "Unable to create rootfs staging directory: $staging" }
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, staging)) {
            FileUtils.delete(staging)
            return false
        }
        val stagedImageFs = ImageFs.find(staging)
        stagedImageFs.createImgVersionFile(desired)
        if (!stagedImageFs.isValid) {
            FileUtils.delete(staging)
            return false
        }

        FileUtils.delete(backup)
        if (rootDir.exists() && !rootDir.renameTo(backup)) {
            FileUtils.delete(staging)
            error("Unable to back up existing rootfs: $rootDir")
        }
        if (!staging.renameTo(rootDir)) {
            if (backup.exists()) backup.renameTo(rootDir)
            FileUtils.delete(staging)
            return false
        }

        val oldHome = File(backup, "home")
        if (oldHome.exists()) {
            val newHome = File(rootDir, "home")
            FileUtils.delete(newHome)
            if (!oldHome.renameTo(newHome)) {
                FileUtils.delete(rootDir)
                check(backup.renameTo(rootDir)) { "Unable to roll back rootfs after home restore failure" }
                return false
            }
        }
        FileUtils.delete(backup)
        return true
    }

    private fun recoverInterruptedInstall(rootDir: File, staging: File, backup: File) {
        FileUtils.delete(staging)
        if (!backup.exists()) return
        if (!rootDir.exists()) {
            check(backup.renameTo(rootDir)) { "Unable to restore interrupted rootfs install" }
            return
        }
        val oldHome = File(backup, "home")
        val newHome = File(rootDir, "home")
        if (oldHome.exists() && !newHome.exists()) {
            check(oldHome.renameTo(newHome)) { "Unable to restore rootfs home directory" }
        }
        if (ImageFs.find(rootDir).isValid) FileUtils.delete(backup)
    }
}
