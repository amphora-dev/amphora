package app.amphora.core.engine

import android.content.Context
import android.util.Log
import app.amphora.core.content.AssetDigest
import app.amphora.core.content.InstalledContentPin
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
 * Versioned immutable extraction cache for native Windows component archives.
 *
 * Archives are downloaded once under runtime-assets and extracted once per SHA
 * under contents/WINCOMPONENTS. Prefix DLLs and EXEs are atomic symlinks into
 * that cache; Wine DllOverrides and COM registration stay with WinComponentSetup.
 */
internal object WinComponentCache {
    private const val TAG = "WinComponentCache"
    const val TYPE_DIR = "WINCOMPONENTS"

    @Synchronized
    fun linkComponent(context: Context, identifier: String, windowsDir: File): File {
        val archive =
            File(RuntimeAssetProvisioner.runtimeAssetsDir(context), "wincomponents/$identifier.tzst")
        val sha = requireNotNull(AssetDigest.pinnedSha(archive)) {
            "Verified WinComponent asset pin is missing: $archive"
        }
        val contentsRoot = ContentsManager.getContentDir(context)
        val typeRoot = File(contentsRoot, TYPE_DIR)
        val cacheDir = File(typeRoot, "$identifier-$sha")

        if (!cacheIsValid(cacheDir, sha)) {
            FileUtils.delete(cacheDir)
            check(typeRoot.mkdirs() || typeRoot.isDirectory) {
                "Cannot create WinComponent cache root: $typeRoot"
            }
            val staging =
                File(
                    File(context.filesDir, "tmp/wincomponent-cache"),
                    "$identifier-${UUID.randomUUID()}",
                )
            FileUtils.delete(staging)
            check(staging.mkdirs()) { "Cannot create WinComponent cache staging: $staging" }
            check(TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, staging)) {
                "Cannot extract native WinComponent: $identifier"
            }
            check(cachePayloadIsSafe(staging)) {
                "WinComponent asset contains no safe DLL/EXE payload: $archive"
            }
            InstalledContentPin.write(staging, sha)
            if (!cacheDir.exists()) {
                check(staging.renameTo(cacheDir)) {
                    "Cannot publish WinComponent cache: $staging -> $cacheDir"
                }
            } else {
                FileUtils.delete(staging)
            }
        }

        var linked = 0
        payloadFiles(cacheDir).forEach { source ->
            val relative = source.relativeTo(cacheDir).path
            val target = File(windowsDir, relative)
            check(SharedDllLinker.link(contentsRoot, source, target)) {
                "Cannot link WinComponent file: $source -> $target"
            }
            linked++
        }
        check(linked > 0) { "WinComponent cache has no DLLs or EXEs: $cacheDir" }
        Log.i(TAG, "Linked $linked file(s) from $identifier cache $cacheDir")
        return cacheDir
    }

    internal fun cachePayloadIsSafe(directory: File): Boolean {
        val root = directory.toPath()
        var payloads = 0
        directory.walkTopDown().forEach { file ->
            val path = file.toPath()
            if (Files.isSymbolicLink(path)) return false
            if (!path.normalize().startsWith(root.normalize())) return false
            if (file.isFile) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false
                if (isLinkablePayload(file)) payloads++
            }
        }
        return payloads > 0
    }

    internal fun payloadFiles(directory: File): Sequence<File> {
        val root = directory.toPath()
        return directory.walkTopDown().filter { file ->
            val path = file.toPath()
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path) &&
                isLinkablePayload(file) &&
                path.normalize().startsWith(root.normalize())
        }
    }

    private fun isLinkablePayload(file: File): Boolean {
        val extension = file.extension.lowercase()
        return extension == "dll" || extension == "exe"
    }

    private fun cacheIsValid(cacheDir: File, sha: String): Boolean =
        InstalledContentPin.matches(cacheDir, sha) && cachePayloadIsSafe(cacheDir)
}
