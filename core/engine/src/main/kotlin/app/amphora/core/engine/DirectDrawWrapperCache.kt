package app.amphora.core.engine

import android.content.Context
import android.util.Log
import app.amphora.core.content.RuntimeAssetProvisioner
import java.io.File
import java.util.zip.ZipInputStream

/**
 * DirectDraw/Glide runtime assets on top of [RuntimeAssetDllCache].
 *
 * Archives live under runtime-assets/ddrawrapper and are extracted once per SHA
 * under contents/DDRAW. Prefix DLLs are atomic symlinks into that cache; mutable
 * INI files and shaders remain container-private. Most assets are tar+zstd;
 * d7vk uses its upstream release ZIP directly.
 */
internal object DirectDrawWrapperCache {
    private const val TAG = "DirectDrawCache"

    fun linkDlls(context: Context, assetId: String, windowsDir: File): File {
        val d7vk = assetId == DirectDrawWrapperIds.D7VK
        val extension = if (d7vk) "zip" else "tzst"
        val archive =
            File(RuntimeAssetProvisioner.runtimeAssetsDir(context), "ddrawrapper/$assetId.$extension")
        return if (d7vk) {
            RuntimeAssetDllCache.link(context, archive, "DDRAW", assetId, windowsDir, extract = ::extractD7vkZip)
        } else {
            RuntimeAssetDllCache.link(context, archive, "DDRAW", assetId, windowsDir)
        }
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

    private const val MAX_D7VK_DLL_BYTES = 64L * 1024L * 1024L
}
