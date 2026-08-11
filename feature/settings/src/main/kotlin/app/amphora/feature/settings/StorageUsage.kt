package app.amphora.feature.settings

import android.content.Context
import android.os.StatFs
import app.amphora.core.content.RuntimeAssetProvisioner
import app.amphora.core.engine.GuestFiles
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.display.environment.ImageFs
import java.io.File
import java.util.UUID

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

data class StorageCleanupResult(
    val bytesFreed: Long = 0,
    val failedPaths: List<String> = emptyList(),
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
        val preserved = preservedGuestData(homeDir, activeHome)
        val managedTemporary = managedTemporaryData(context.cacheDir)

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
                if (preserved.isNotEmpty()) {
                    add(
                        StorageEntry(
                            label = "Other containers and recovery backups",
                            detail = "Preserved; manage containers explicitly",
                            bytes = preserved.sumOf(StorageEntry::bytes),
                            children = preserved,
                        ),
                    )
                }
                if (managedTemporary.isNotEmpty()) {
                    add(
                        StorageEntry(
                            label = "Managed temporary files",
                            detail = "Old interrupted-operation files · safe to remove",
                            bytes = managedTemporary.sumOf(StorageEntry::bytes),
                            children = managedTemporary,
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
            reclaimableBytes = managedTemporary.sumOf(StorageEntry::bytes) + cacheBytes,
        )
    }

    /** Guest data outside the active prefix is recovery/user data, never generic cleanup. */
    private fun preservedGuestData(homeDir: File, activeHome: File): List<StorageEntry> {
        val containers = homeDir.listFiles().orEmpty().filter { it.isDirectory && !isSymlink(it) }
        return containers
            .flatMap { container ->
                if (container != activeHome) {
                    listOf(
                        StorageEntry(
                            label = container.name,
                            detail =
                                if (container.name.startsWith("${ImageFs.USER}.legacy-backup-")) {
                                    "Legacy home recovery backup · preserved"
                                } else {
                                    "Inactive container · preserved"
                                },
                            bytes = sizeOf(container),
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
                                detail = "Prefix recovery backup · preserved",
                                bytes = sizeOf(it),
                            )
                        }
                }
            }.filter { it.bytes > 0 }
            .sortedByDescending(StorageEntry::bytes)
    }

    internal fun managedTemporaryData(
        cacheDir: File,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<StorageEntry> {
        val cleanupDir = File(cacheDir, CLEANUP_DIR)
        val archiveStage = File(cacheDir, NATIVE_ARCHIVE_STAGE_DIR)
        val candidates =
            buildList {
                addAll(
                    cacheDir
                        .listFiles()
                        .orEmpty()
                        .filter { it.name.matches(WINEPREFIX_REPAIR) || it.name.matches(RESTORE_TEMP) },
                )
                addAll(archiveStage.listFiles().orEmpty().filter { it.name.matches(ARCHIVE_TEMP) })
                addAll(cleanupDir.listFiles().orEmpty())
            }
        return candidates
            .filter { isManagedTemporary(it, cacheDir, nowMillis) }
            .map {
                StorageEntry(
                    label = it.name,
                    detail = "Managed temporary item",
                    bytes = sizeOf(it),
                    removablePath = it.absolutePath,
                )
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
     * Deletes only old, app-managed temporary data and returns the actual bytes freed.
     *
     * The caller passes paths measured earlier, so every one is re-validated here:
     * arbitrary cache files, containers and Wine prefix backups are never eligible.
     * A target is first atomically moved into a managed quarantine, preventing a
     * recursive failure from leaving a half-deleted item at its original path.
     */
    fun deleteUnusedGuestData(context: Context, paths: List<String>): StorageCleanupResult {
        val cacheDir = resolve(context.cacheDir)
        val cleanupDir = File(cacheDir, CLEANUP_DIR)
        val failed = mutableListOf<String>()
        var freed = 0L
        paths.forEach { path ->
            val requested = File(path).absoluteFile
            if (!isManagedTemporary(requested, cacheDir)) {
                failed += path
                return@forEach
            }
            val target = resolve(requested)
            val before = sizeOf(target)
            val quarantined =
                if (target.parentFile == cleanupDir) {
                    target
                } else {
                    if (!cleanupDir.isDirectory && !cleanupDir.mkdirs()) {
                        failed += path
                        return@forEach
                    }
                    val destination = File(cleanupDir, "${target.name}-${UUID.randomUUID()}.deleting")
                    if (!target.renameTo(destination)) {
                        failed += path
                        return@forEach
                    }
                    destination
                }
            val deleted = deleteTree(quarantined)
            val remaining = sizeOf(quarantined)
            freed += (before - remaining).coerceAtLeast(0)
            if (!deleted) failed += path
        }
        return StorageCleanupResult(bytesFreed = freed, failedPaths = failed)
    }

    internal fun isManagedTemporary(
        requested: File,
        cacheDir: File,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!requested.exists() || isSymlink(requested)) return false
        val cache = resolve(cacheDir)
        val target = resolve(requested)
        if (!target.toPath().startsWith(cache.toPath())) return false
        val parent = target.parentFile ?: return false
        if (parent == resolve(File(cache, CLEANUP_DIR))) return true
        if (nowMillis - target.lastModified() < MIN_TEMP_AGE_MS) return false
        return when (parent) {
            cache ->
                target.name.matches(WINEPREFIX_REPAIR) ||
                    target.name.matches(RESTORE_TEMP)
            resolve(File(cache, NATIVE_ARCHIVE_STAGE_DIR)) -> target.name.matches(ARCHIVE_TEMP)
            else -> false
        }
    }

    /** A renamed prefix such as `.wine.broken-backup`, never the live `.wine`. */
    private fun isOldPrefix(name: String): Boolean = name.startsWith(".wine") && name != ".wine"

    /**
     * Deletes a tree without ever following a symlink.
     *
     * `File.deleteRecursively()` descends into linked directories, and a Wine prefix
     * maps whole volumes that way — `dosdevices/z:` points at `/`, so recursing
     * through it would walk far outside the directory the user asked to remove.
     */
    private fun deleteTree(target: File): Boolean {
        return try {
            if (isSymlink(target)) return target.delete()
            var complete = true
            if (target.isDirectory) {
                val children = target.listFiles() ?: return false
                children.forEach {
                    if (!deleteTree(it)) complete = false
                }
            }
            target.delete() && complete
        } catch (_: SecurityException) {
            false
        }
    }

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

    private const val MIN_TEMP_AGE_MS = 24L * 60L * 60L * 1_000L
    private const val CLEANUP_DIR = ".amphora-cleanup"
    private const val NATIVE_ARCHIVE_STAGE_DIR = "native-archive-stage"
    private val WINEPREFIX_REPAIR = Regex("""wineprefix-repair-[a-fA-F0-9]+\.tmp""")
    private val RESTORE_TEMP = Regex("""restore_.+\.tmp""")
    private val ARCHIVE_TEMP = Regex("""archive-[a-z0-9]+-[a-fA-F0-9]+\.tmp""")
}

fun formatStorageSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
