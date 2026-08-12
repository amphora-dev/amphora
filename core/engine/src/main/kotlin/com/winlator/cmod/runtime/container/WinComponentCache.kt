package com.winlator.cmod.runtime.container

import android.content.Context
import android.util.Log
import app.amphora.core.content.AssetDigest
import app.amphora.core.content.InstalledContentPin
import app.amphora.core.content.RuntimeAssetProvisioner
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.content.SharedDllLinker
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.io.TarCompressorUtils
import com.winlator.cmod.shared.util.OnExtractFileListener
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/**
 * Immutable, content-addressed extraction cache for native WinComponents.
 *
 * Runtime assets remain compressed under `runtime-assets/wincomponents`. Each
 * verified archive is expanded once below `contents/WINCOMPONENTS`, then every
 * container links the selected DLL/EXE files into its prefix. Wine may unlink a
 * prefix link and create a private replacement without mutating the shared cache.
 */
internal object WinComponentCache {
    private const val TAG = "WinComponentCache"
    private const val CACHE_TYPE = "WINCOMPONENTS"

    @Synchronized
    fun linkComponent(
        context: Context,
        identifier: String,
        windowsDir: File,
        expectedFiles: Collection<String>,
        onExtractFileListener: OnExtractFileListener?,
    ): File {
        val archive =
            File(
                RuntimeAssetProvisioner.runtimeAssetsDir(context),
                "wincomponents/$identifier.tzst",
            )
        val sha = requireNotNull(AssetDigest.pinnedSha(archive)) {
            "Verified WinComponent asset pin is missing: $archive"
        }
        val normalizedExpected = expectedFiles.map { it.lowercase() }.toSet()
        require(normalizedExpected.isNotEmpty()) {
            "WinComponent '$identifier' has no declared files"
        }
        val contentsRoot = ContentsManager.getContentDir(context)
        val typeRoot = File(contentsRoot, CACHE_TYPE)
        val cacheDir = File(typeRoot, "$identifier-$sha")

        if (!cacheIsValid(cacheDir, sha, normalizedExpected)) {
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
            check(
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    archive,
                    staging,
                ),
            ) {
                "Cannot extract WinComponent asset: $archive"
            }
            check(cachePayloadIsSafe(staging, normalizedExpected)) {
                "WinComponent asset has incomplete or unsafe payload: $archive"
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
        payloadFiles(cacheDir, normalizedExpected).forEach { source ->
            val target = File(windowsDir, source.relativeTo(cacheDir).path)
            val selectedTarget =
                if (onExtractFileListener != null) {
                    onExtractFileListener.onExtractFile(target, source.length())
                } else {
                    target
                }
            if (selectedTarget == null) return@forEach
            check(SharedDllLinker.link(contentsRoot, source, selectedTarget)) {
                "Cannot link WinComponent file: $source -> $selectedTarget"
            }
            onExtractFileListener?.onExtractFileProgress(selectedTarget, source.length())
            linked++
        }
        check(linked > 0) { "WinComponent cache has no linkable files: $cacheDir" }
        pruneSupersededCaches(typeRoot, identifier, cacheDir)
        Log.i(TAG, "Linked $linked file(s) from $identifier cache $cacheDir")
        return cacheDir
    }

    private fun cacheIsValid(cacheDir: File, sha: String, expectedFiles: Set<String>): Boolean =
        InstalledContentPin.matches(cacheDir, sha) && cachePayloadIsSafe(cacheDir, expectedFiles)

    internal fun cachePayloadIsSafe(directory: File, expectedFiles: Set<String>): Boolean {
        if (!directory.isDirectory || expectedFiles.isEmpty()) return false
        val root = directory.toPath().normalize()
        val found = mutableSetOf<String>()
        directory.walkTopDown().forEach { file ->
            val path = file.toPath()
            if (Files.isSymbolicLink(path)) return false
            if (!path.normalize().startsWith(root)) return false
            if (!file.isFile) return@forEach
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false
            val relative = file.relativeTo(directory).invariantSeparatorsPath
            if (relative == InstalledContentPin.MARKER_NAME) return@forEach
            val segments = relative.split('/')
            if (segments.size != 2 ||
                segments[0] !in setOf("system32", "syswow64") ||
                file.name.lowercase() !in expectedFiles
            ) {
                return false
            }
            found += file.name.lowercase()
        }
        return found.containsAll(expectedFiles)
    }

    private fun payloadFiles(directory: File, expectedFiles: Set<String>): Sequence<File> =
        directory
            .walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.name.lowercase() in expectedFiles &&
                    file.relativeTo(directory).invariantSeparatorsPath.substringBefore('/') in
                    setOf("system32", "syswow64")
            }

    private fun pruneSupersededCaches(typeRoot: File, identifier: String, keep: File) {
        typeRoot
            .listFiles()
            .orEmpty()
            .filter {
                it.isDirectory &&
                    it != keep &&
                    it.name.startsWith("$identifier-")
            }.forEach {
                if (!FileUtils.delete(it)) {
                    Log.w(TAG, "Failed to remove stale WinComponent cache ${it.absolutePath}")
                }
            }
    }
}
