package app.amphora.feature.settings

import android.content.Context
import android.os.StatFs
import app.amphora.core.content.RuntimeAssetProvisioner
import app.amphora.core.engine.GuestFiles
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.display.environment.ImageFs
import java.io.File

/** One line of the storage breakdown, already resolved to bytes on disk. */
data class StorageEntry(val label: String, val detail: String, val bytes: Long)

data class StorageUsage(
    val entries: List<StorageEntry> = emptyList(),
    val totalBytes: Long = 0,
    val freeBytes: Long = 0,
    val shaderCacheBytes: Long = 0,
)

/**
 * Measures what Amphora occupies in app-private storage.
 *
 * The imagefs tree is reported as three separate lines because they age very
 * differently: the Linux runtime is replaced only when the rootfs pin moves,
 * the Windows prefix grows with installed programs, and the shader cache is
 * disposable and can be cleared from this screen.
 */
object StorageUsageScanner {
    fun scan(context: Context): StorageUsage {
        val imageFs = ImageFs.find(context)
        val rootDir = imageFs.rootDir
        val prefixDir = File(rootDir, ImageFs.WINEPREFIX.trimStart('/'))
        val cacheDir = File(rootDir, ImageFs.CACHE_PATH.trimStart('/'))
        val imagefsTotal = sizeOf(rootDir)
        val prefixBytes = sizeOf(prefixDir)
        val cacheBytes = sizeOf(cacheDir)

        val entries =
            buildList {
                add(
                    StorageEntry(
                        label = "Linux runtime",
                        detail = "ImageFS libraries and filesystem",
                        bytes = (imagefsTotal - prefixBytes - cacheBytes).coerceAtLeast(0),
                    ),
                )
                add(
                    StorageEntry(
                        label = "Windows prefix",
                        detail = "Registry, drive C: and installed programs",
                        bytes = prefixBytes,
                    ),
                )
                addAll(componentEntries(context))
                add(
                    StorageEntry(
                        label = "Runtime assets",
                        detail = "Windows components, wrappers and metadata",
                        bytes = sizeOf(RuntimeAssetProvisioner.runtimeAssetsDir(context)),
                    ),
                )
                add(
                    StorageEntry(
                        label = "Added programs",
                        detail = "Executables copied into Amphora",
                        bytes = sizeOf(GuestFiles.exeDir(context)),
                    ),
                )
                add(
                    StorageEntry(
                        label = "Shader cache",
                        detail = "Rebuilt automatically after clearing",
                        bytes = cacheBytes,
                    ),
                )
            }.filter { it.bytes > 0 }

        return StorageUsage(
            entries = entries.sortedByDescending(StorageEntry::bytes),
            totalBytes = entries.sumOf(StorageEntry::bytes),
            freeBytes = freeBytes(context),
            shaderCacheBytes = cacheBytes,
        )
    }

    /**
     * `.wcp` components live under `contents/<type>/<verName>-<verCode>`, so a
     * type directory covers every installed version of that component.
     */
    private fun componentEntries(context: Context): List<StorageEntry> = COMPONENT_LABELS.mapNotNull { (type, label) ->
        val dir = ContentsManager.getContentTypeDir(context, type)
        val bytes = sizeOf(dir)
        if (bytes <= 0) return@mapNotNull null
        StorageEntry(label = label, detail = installedVersions(dir), bytes = bytes)
    }

    private fun installedVersions(dir: File): String = dir
        .listFiles()
        ?.filter(File::isDirectory)
        ?.map(File::getName)
        ?.sorted()
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString()
        ?: "Installed component"

    private fun freeBytes(context: Context): Long = try {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (_: IllegalArgumentException) {
        0
    }

    /**
     * The guest home is reached through a symlink (`home/xuser -> ./xuser-1`), so the
     * scan root is resolved first — otherwise every entry below it looks like a link.
     * Links found during the walk are still skipped: the prefix symlinks Windows DLLs
     * back into the component store, and following them would bill the same bytes twice.
     */
    private fun sizeOf(file: File): Long {
        val root =
            try {
                file.canonicalFile
            } catch (_: java.io.IOException) {
                file.absoluteFile
            }
        if (!root.exists()) return 0
        if (root.isFile) return root.length()
        return root
            .walkTopDown()
            .onEnter { it == root || !isSymlink(it) }
            .filter { it.isFile && !isSymlink(it) }
            .sumOf(File::length)
    }

    private fun isSymlink(file: File): Boolean = try {
        java.nio.file.Files.isSymbolicLink(file.toPath())
    } catch (_: RuntimeException) {
        false
    }

    private val COMPONENT_LABELS =
        listOf(
            ContentProfile.ContentType.CONTENT_TYPE_PROTON to "Proton",
            ContentProfile.ContentType.CONTENT_TYPE_WINE to "Wine",
            ContentProfile.ContentType.CONTENT_TYPE_DXVK to "DXVK",
            ContentProfile.ContentType.CONTENT_TYPE_VKD3D to "VKD3D",
            ContentProfile.ContentType.CONTENT_TYPE_D7VK to "D7VK",
            ContentProfile.ContentType.CONTENT_TYPE_BOX64 to "Box64",
            ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64 to "WOWBox64",
            ContentProfile.ContentType.CONTENT_TYPE_FEXCORE to "FEXCore",
        )
}

fun formatStorageSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
