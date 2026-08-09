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
data class StorageEntry(
    val label: String,
    val detail: String,
    val bytes: Long,
    val children: List<StorageEntry> = emptyList(),
    /** Set only for entries the user is allowed to delete from this screen. */
    val removablePath: String? = null,
)

data class StorageUsage(
    val entries: List<StorageEntry> = emptyList(),
    val totalBytes: Long = 0,
    val freeBytes: Long = 0,
    val shaderCacheBytes: Long = 0,
    val reclaimableBytes: Long = 0,
)

/**
 * Measures what Amphora occupies in app-private storage.
 *
 * The imagefs tree is not reported as one number because most of it is not the
 * Linux runtime at all: the guest home holds the active Windows prefix plus any
 * container or prefix left behind by earlier runs, and those are usually the
 * largest — and the only reclaimable — items on the device.
 */
object StorageUsageScanner {
    private const val MAX_CHILDREN = 8

    fun scan(context: Context): StorageUsage {
        val rootDir = resolve(ImageFs.find(context).rootDir)
        val homeDir = File(rootDir, "home")
        // `home/xuser` is a symlink to the active container (`xuser-1`, `xuser-2`, …).
        val activeHome = resolve(File(homeDir, ImageFs.USER))
        val prefixDir = File(activeHome, ".wine")
        val cacheDir = File(activeHome, ".cache")
        val cacheBytes = sizeOf(cacheDir)
        val stale = staleGuestData(homeDir, activeHome)

        val entries =
            buildList {
                add(
                    StorageEntry(
                        label = "Windows prefix",
                        detail = "Drive C:, registry and installed programs",
                        bytes = sizeOf(prefixDir),
                        children = childrenOf(prefixDir),
                    ),
                )
                if (stale.isNotEmpty()) {
                    add(
                        StorageEntry(
                            label = "Unused containers and backups",
                            detail = "Left by earlier runs · safe to delete",
                            bytes = stale.sumOf(StorageEntry::bytes),
                            children = stale,
                        ),
                    )
                }
                addAll(componentEntries(context))
                val assetsDir = RuntimeAssetProvisioner.runtimeAssetsDir(context)
                add(
                    StorageEntry(
                        label = "Runtime assets",
                        detail = "Windows components, wrappers and graphics drivers",
                        bytes = sizeOf(assetsDir),
                        children = childrenOf(assetsDir),
                    ),
                )
                add(linuxRuntimeEntry(rootDir, homeDir))
                val exeDir = GuestFiles.exeDir(context)
                add(
                    StorageEntry(
                        label = "Added programs",
                        detail = "Executables copied into Amphora",
                        bytes = sizeOf(exeDir),
                        children = childrenOf(exeDir),
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
            reclaimableBytes = stale.sumOf(StorageEntry::bytes) + cacheBytes,
        )
    }

    /** Everything under `home` that the active container does not use. */
    private fun staleGuestData(homeDir: File, activeHome: File): List<StorageEntry> {
        val containers = homeDir.listFiles().orEmpty().filter { it.isDirectory && !isSymlink(it) }
        return containers
            .flatMap { container ->
                if (container != activeHome) {
                    listOf(
                        StorageEntry(
                            label = container.name,
                            detail = "Inactive container",
                            bytes = sizeOf(container),
                            removablePath = container.absolutePath,
                        ),
                    )
                } else {
                    container
                        .listFiles()
                        .orEmpty()
                        .filter { it.isDirectory && !isSymlink(it) && isOldPrefix(it.name) }
                        .map {
                            StorageEntry(
                                label = it.name,
                                detail = "Prefix backup",
                                bytes = sizeOf(it),
                                removablePath = it.absolutePath,
                            )
                        }
                }
            }.filter { it.bytes > 0 }
            .sortedByDescending(StorageEntry::bytes)
    }

    /** imagefs without the guest home: the ported Linux userland Wine runs on. */
    private fun linuxRuntimeEntry(rootDir: File, homeDir: File): StorageEntry {
        val children =
            rootDir
                .listFiles()
                .orEmpty()
                .filter { it != homeDir && !isSymlink(it) }
                .map { StorageEntry(label = it.name, detail = pathKind(it), bytes = sizeOf(it)) }
                .filter { it.bytes > 0 }
                .sortedByDescending(StorageEntry::bytes)
        return StorageEntry(
            label = "Linux runtime",
            detail = "ImageFS libraries and system directories",
            bytes = children.sumOf(StorageEntry::bytes),
            children = children.take(MAX_CHILDREN),
        )
    }

    /**
     * `.wcp` components live under `contents/<type>/<verName>-<verCode>` and are
     * stored extracted — the downloaded archive is not kept after installation.
     */
    private fun componentEntries(context: Context): List<StorageEntry> = COMPONENT_LABELS.mapNotNull { (type, label) ->
        val dir = ContentsManager.getContentTypeDir(context, type)
        val versions =
            dir
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory }
                .map { StorageEntry(label = it.name, detail = "Extracted component", bytes = sizeOf(it)) }
                .filter { it.bytes > 0 }
                .sortedByDescending(StorageEntry::bytes)
        if (versions.isEmpty()) return@mapNotNull null
        StorageEntry(
            label = label,
            detail = versions.joinToString { it.label },
            bytes = versions.sumOf(StorageEntry::bytes),
            children = if (versions.size > 1) versions else childrenOf(File(dir, versions.first().label)),
        )
    }

    private fun childrenOf(dir: File): List<StorageEntry> {
        val entries =
            dir
                .listFiles()
                .orEmpty()
                .filter { !isSymlink(it) }
                .map { StorageEntry(label = it.name, detail = pathKind(it), bytes = sizeOf(it)) }
                .filter { it.bytes > 0 }
                .sortedByDescending(StorageEntry::bytes)
        if (entries.size <= MAX_CHILDREN) return entries
        val shown = entries.take(MAX_CHILDREN)
        val rest = entries.drop(MAX_CHILDREN)
        return shown +
            StorageEntry(
                label = "${rest.size} more items",
                detail = "Smaller files and folders",
                bytes = rest.sumOf(StorageEntry::bytes),
            )
    }

    private fun pathKind(file: File): String = if (file.isDirectory) "Folder" else "File"

    /**
     * Deletes leftover guest data and returns the bytes freed.
     *
     * The caller passes paths measured earlier, so every one is re-validated here:
     * a container can become the active one between the scan and the tap, and only
     * directories directly under the guest home are ever eligible.
     */
    fun deleteUnusedGuestData(context: Context, paths: List<String>): Long {
        val rootDir = resolve(ImageFs.find(context).rootDir)
        val homeDir = resolve(File(rootDir, "home"))
        val activeHome = resolve(File(rootDir, "home/${ImageFs.USER}"))
        var freed = 0L
        paths.forEach { path ->
            val target = resolve(File(path))
            if (!isRemovable(target, homeDir, activeHome)) return@forEach
            val size = sizeOf(target)
            if (target.deleteRecursively()) freed += size
        }
        return freed
    }

    private fun isRemovable(target: File, homeDir: File, activeHome: File): Boolean {
        if (!target.isDirectory) return false
        if (target == activeHome || target == homeDir) return false
        // Either a sibling container of the active one, or a superseded prefix inside
        // it. The live prefix, the shader cache and the container's own runtime data
        // (`.config`, `.local`, …) are never eligible.
        val parent = target.parentFile ?: return false
        if (parent == homeDir) return true
        return parent == activeHome && isOldPrefix(target.name)
    }

    /** A renamed prefix such as `.wine.broken-backup`, never the live `.wine`. */
    private fun isOldPrefix(name: String): Boolean = name.startsWith(".wine") && name != ".wine"

    private fun freeBytes(context: Context): Long = try {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (_: IllegalArgumentException) {
        0
    }

    /**
     * The scan root is resolved first because the guest home is reached through a
     * symlink; without that every entry below it would look like a link. Links
     * found during the walk are still skipped — the prefix symlinks Windows DLLs
     * back into the component store, and following them would double-count bytes.
     */
    private fun sizeOf(file: File): Long {
        val root = resolve(file)
        if (!root.exists()) return 0
        if (root.isFile) return root.length()
        return root
            .walkTopDown()
            .onEnter { it == root || !isSymlink(it) }
            .filter { it.isFile && !isSymlink(it) }
            .sumOf(File::length)
    }

    private fun resolve(file: File): File = try {
        file.canonicalFile
    } catch (_: java.io.IOException) {
        file.absoluteFile
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
