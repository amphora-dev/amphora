package app.amphora.core.engine

import android.content.Context
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.rootfs.RootfsInstaller
import app.amphora.core.rootfs.model.RootfsSpec
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.io.TarCompressorUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
 * Activity / progress UI (`DownloadProgressDialog`, `ProgressListener`) is
 * replaced by amphora's Compose UI in P3.
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
) : RootfsInstaller {

    override suspend fun ensureInstalled(spec: RootfsSpec): Boolean = withContext(dispatchers.io) {
        val rootDir = File(spec.targetRoot)
        val imageFs = ImageFs.find(rootDir)
        val desired = spec.imagefsVersion.toIntOrNull() ?: 0

        if (desired > 0 && imageFs.isValid && imageFs.getVersion() >= desired) {
            return@withContext true // already up to date
        }

        clearRootDir(rootDir)
        val extracted = extractImageFs(rootDir)
        if (extracted) {
            imageFs.createImgVersionFile(desired)
        }
        extracted
    }

    override suspend fun currentVersion(): String? = withContext(dispatchers.io) {
        // Conventional root -- matches the kernel's ImageFs.find(context) = filesDir/imagefs.
        val imageFs = ImageFs.find(context)
        if (!imageFs.isValid) null else imageFs.getVersion().toString()
    }

    // --- imagefs extraction (WinNative ImageFsInstaller.extractImageFs pattern) ---

    private fun extractImageFs(rootDir: File): Boolean {
        val shards = listImageFsShards()
        if (shards.isEmpty()) {
            return TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, context, IMAGEFS_ARCHIVE, rootDir,
            )
        }
        // Parallel shard extraction (WinNative: 2..availableProcessors threads).
        val threads = maxOf(2, minOf(shards.size, Runtime.getRuntime().availableProcessors()))
        val pool: ExecutorService = Executors.newFixedThreadPool(threads)
        return try {
            val futures: List<Future<Boolean>> = shards.map { shard ->
                pool.submit(
                    Callable {
                        TarCompressorUtils.extract(
                            TarCompressorUtils.Type.ZSTD, context, shard, rootDir,
                        )
                    }
                )
            }
            futures.all { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun listImageFsShards(): List<String> =
        try {
            context.assets.list("").orEmpty().filter { name ->
                name.startsWith("imagefs.part") && name.endsWith(".tzst")
            }
        } catch (e: Exception) {
            emptyList()
        }

    /**
     * Clear the root dir before extraction, preserving `home/` (WinNative
     * `clearRootDir` -- user data survives an imagefs reinstall).
     */
    private fun clearRootDir(rootDir: File) {
        if (rootDir.isDirectory) {
            rootDir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name == "home") return@forEach
                FileUtils.delete(file)
            }
        } else {
            rootDir.mkdirs()
        }
    }

    private companion object {
        const val IMAGEFS_ARCHIVE = "imagefs.tzst"
    }
}
