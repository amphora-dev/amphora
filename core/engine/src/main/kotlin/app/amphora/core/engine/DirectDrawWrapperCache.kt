package app.amphora.core.engine

import android.content.Context
import android.util.Log
import app.amphora.core.content.AssetDigest
import app.amphora.core.content.RuntimeAssetProvisioner
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.content.SharedDllLinker
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.io.TarCompressorUtils
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Versioned immutable extraction cache for DirectDraw/Glide runtime assets.
 *
 * Archives are downloaded once under runtime-assets and extracted once per SHA
 * under contents/DDRAW. Prefix DLLs are atomic symlinks into that cache; mutable
 * INI files and shaders remain container-private. Most assets are tar+zstd;
 * d7vk uses its upstream release ZIP directly.
 */
internal object DirectDrawWrapperCache {
    private const val TAG = "DirectDrawCache"

    @Synchronized
    fun linkDlls(context: Context, assetId: String, windowsDir: File): File {
        val extension = if (assetId == DirectDrawWrapperIds.D7VK) "zip" else "tzst"
        val archive =
            File(RuntimeAssetProvisioner.runtimeAssetsDir(context), "ddrawrapper/$assetId.$extension")
        val sha = requireNotNull(AssetDigest.pinnedSha(archive)) {
            "Verified DirectDraw asset pin is missing: $archive"
        }
        val contentsRoot = ContentsManager.getContentDir(context)
        val typeRoot = File(contentsRoot, "DDRAW")
        val cacheDir = File(typeRoot, "$assetId-$sha")
        val marker = File(cacheDir, ".installed.sha256")

        if (!cacheIsValid(cacheDir, marker, sha)) {
            FileUtils.delete(cacheDir)
            check(typeRoot.mkdirs() || typeRoot.isDirectory) {
                "Cannot create DirectDraw cache root: $typeRoot"
            }
            val staging =
                File(
                    File(context.filesDir, "tmp/ddraw-cache"),
                    "$assetId-${UUID.randomUUID()}",
                )
            FileUtils.delete(staging)
            check(staging.mkdirs()) { "Cannot create DirectDraw cache staging: $staging" }
            check(extractArchive(assetId, archive, staging)) {
                "Cannot extract DirectDraw asset: $archive"
            }
            check(cachePayloadIsSafe(staging)) {
                "DirectDraw asset contains no safe DLL payload: $archive"
            }
            markerFor(staging).writeText(sha)
            if (!cacheDir.exists()) {
                check(staging.renameTo(cacheDir)) {
                    "Cannot publish DirectDraw cache: $staging -> $cacheDir"
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
                "Cannot link DirectDraw DLL: $source -> $target"
            }
            linked++
        }
        check(linked > 0) { "DirectDraw cache has no DLLs: $cacheDir" }
        Log.i(TAG, "Linked $linked DLL(s) from $assetId cache $cacheDir")
        return cacheDir
    }

    private fun extractArchive(assetId: String, archive: File, staging: File): Boolean =
        if (assetId == DirectDrawWrapperIds.D7VK) {
            extractD7vkZip(archive, staging)
        } else {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, staging)
        }

    /**
     * Extracts only the 32-bit ddraw DLL from d7vk's upstream release layout:
     * `d7vk-vX.Y/x32/ddraw.dll`. Ignoring every other entry keeps the cache
     * layout stable across release directory renames and prevents ZIP traversal.
     */
    internal fun extractD7vkZip(archive: File, destination: File): Boolean {
        val target = File(destination, "syswow64/ddraw.dll")
        var extracted = false
        return try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val normalized = entry.name.replace('\\', '/').trimStart('/')
                    if (!entry.isDirectory &&
                        normalized.endsWith("/x32/ddraw.dll", ignoreCase = true)
                    ) {
                        if (extracted) return false
                        check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
                        target.outputStream().buffered().use { output ->
                            zip.copyTo(output)
                        }
                        extracted = target.isFile && target.length() in 1..MAX_D7VK_DLL_BYTES
                    }
                    zip.closeEntry()
                }
            }
            extracted
        } catch (e: Exception) {
            Log.e(TAG, "Cannot extract d7vk release ZIP: $archive", e)
            target.delete()
            false
        }
    }

    private fun cacheIsValid(cacheDir: File, marker: File, sha: String): Boolean =
        marker.isFile && marker.readText().trim() == sha && cachePayloadIsSafe(cacheDir)

    private fun markerFor(directory: File): File = File(directory, ".installed.sha256")

    private fun cachePayloadIsSafe(directory: File): Boolean {
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

    private const val MAX_D7VK_DLL_BYTES = 64L * 1024L * 1024L
}
