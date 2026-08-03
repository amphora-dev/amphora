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

/**
 * Versioned immutable extraction cache for DirectDraw/Glide runtime assets.
 *
 * Archives are downloaded once under runtime-assets and extracted once per SHA
 * under contents/DDRAW. Prefix DLLs are atomic symlinks into that cache; mutable
 * INI files and shaders remain container-private.
 */
internal object DirectDrawWrapperCache {
    private const val TAG = "DirectDrawCache"

    @Synchronized
    fun linkDlls(context: Context, assetId: String, windowsDir: File): File {
        val archive =
            File(
                RuntimeAssetProvisioner.runtimeAssetsDir(context),
                "ddrawrapper/$assetId.tzst",
            )
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
            check(TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, staging)) {
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
}
