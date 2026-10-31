package app.amphora.core.engine

import android.content.Context
import android.util.Log
import app.amphora.core.content.AssetDigest
import app.amphora.core.content.InstalledContentPin
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.content.SharedDllLinker
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.io.TarCompressorUtils
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/**
 * Versioned immutable extraction cache for DLL-only runtime assets
 * (DirectDraw/Glide wrappers, Mesa WGL).
 *
 * The archive is downloaded once under runtime-assets and extracted once per
 * pinned SHA under `contents/<typeDir>/<cacheId>-<sha>`. Every `*.dll` in the
 * cache is linked into the prefix by its relative path (`system32/…`,
 * `syswow64/…`) as an atomic symlink; nothing mutable lives in the cache.
 */
internal object RuntimeAssetDllCache {
    private const val TAG = "RuntimeAssetDllCache"

    @Synchronized
    fun link(
        context: Context,
        archive: File,
        typeDir: String,
        cacheId: String,
        windowsDir: File,
        requiredFiles: List<String> = emptyList(),
        extract: (File, File) -> Boolean = { source, destination ->
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, source, destination)
        },
    ): File {
        val sha = requireNotNull(AssetDigest.pinnedSha(archive)) {
            "Verified runtime asset pin is missing: $archive"
        }
        val contentsRoot = ContentsManager.getContentDir(context)
        val typeRoot = File(contentsRoot, typeDir)
        val cacheDir = File(typeRoot, "$cacheId-$sha")

        if (!cacheIsValid(cacheDir, sha, requiredFiles)) {
            FileUtils.delete(cacheDir)
            check(typeRoot.mkdirs() || typeRoot.isDirectory) {
                "Cannot create $typeDir cache root: $typeRoot"
            }
            val staging =
                File(
                    File(context.filesDir, "tmp/${typeDir.lowercase()}-cache"),
                    "$cacheId-${UUID.randomUUID()}",
                )
            FileUtils.delete(staging)
            check(staging.mkdirs()) { "Cannot create $typeDir cache staging: $staging" }
            check(extract(archive, staging)) { "Cannot extract runtime asset: $archive" }
            check(cachePayloadIsSafe(staging)) {
                "Runtime asset contains no safe DLL payload: $archive"
            }
            val missing = missingFiles(staging, requiredFiles)
            check(missing.isEmpty()) { "Runtime asset $archive lacks $missing" }
            InstalledContentPin.write(staging, sha)
            if (!cacheDir.exists()) {
                check(staging.renameTo(cacheDir)) {
                    "Cannot publish $typeDir cache: $staging -> $cacheDir"
                }
            } else {
                FileUtils.delete(staging)
            }
        }

        var linked = 0
        cacheDir.walkTopDown().filter { file ->
            file.isFile && file.extension.equals("dll", ignoreCase = true)
        }.forEach { source ->
            val relative = source.relativeTo(cacheDir).path
            val target = File(windowsDir, relative)
            check(SharedDllLinker.link(contentsRoot, source, target)) {
                "Cannot link $typeDir DLL: $source -> $target"
            }
            linked++
        }
        check(linked > 0) { "$typeDir cache has no DLLs: $cacheDir" }
        Log.i(TAG, "Linked $linked DLL(s) from $cacheId cache $cacheDir")
        return cacheDir
    }

    private fun cacheIsValid(cacheDir: File, sha: String, requiredFiles: List<String>): Boolean =
        InstalledContentPin.matches(cacheDir, sha) &&
            cachePayloadIsSafe(cacheDir) &&
            missingFiles(cacheDir, requiredFiles).isEmpty()

    /** True when [directory] holds at least one DLL and only regular files inside itself. */
    internal fun cachePayloadIsSafe(directory: File): Boolean {
        val root = directory.toPath()
        var dlls = 0
        directory.walkTopDown().forEach { file ->
            val path = file.toPath()
            if (Files.isSymbolicLink(path)) return false
            if (!path.normalize().startsWith(root.normalize())) return false
            if (file.isFile) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false
                if (file.extension.equals("dll", ignoreCase = true)) dlls++
            }
        }
        return dlls > 0
    }

    /** The entries of [required] (paths relative to [dir]) that are not regular files. */
    internal fun missingFiles(dir: File, required: List<String>): List<String> =
        required.filterNot { File(dir, it).isFile }
}
