package app.amphora.feature.settings

import android.content.Context
import app.amphora.core.content.InstalledContentPin
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageUsageScannerTest {
    @Test
    fun onlyOldManagedTemporaryNamesAreRemovable() {
        val root = Files.createTempDirectory("storage-boundary-").toFile()
        try {
            val cache = root.resolve("cache").apply { mkdirs() }
            val oldManaged = cache.resolve("wineprefix-repair-abcdef.tmp").apply {
                writeText("temporary")
                setLastModified(System.currentTimeMillis() - TWO_DAYS_MS)
            }
            val recentManaged = cache.resolve("restore_123.tmp").apply { writeText("in use") }
            val prefixBackup = cache.resolve(".wine.broken-backup").apply { mkdirs() }
            val inactiveContainer = root.resolve("xuser-2").apply { mkdirs() }

            assertTrue(StorageUsageScanner.isManagedTemporary(oldManaged, cache))
            assertFalse(StorageUsageScanner.isManagedTemporary(recentManaged, cache))
            assertFalse(StorageUsageScanner.isManagedTemporary(prefixBackup, cache))
            assertFalse(StorageUsageScanner.isManagedTemporary(inactiveContainer, cache))

            val listed = StorageUsageScanner.managedTemporaryData(cache)
            assertEquals(listOf(oldManaged.absolutePath), listed.mapNotNull(StorageEntry::removablePath))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupRejectsContainerAndPrefixBackupPaths() {
        val root = Files.createTempDirectory("storage-reject-").toFile()
        try {
            val cache = root.resolve("cache").apply { mkdirs() }
            val container = root.resolve("xuser-2").apply { mkdirs() }
            val backup = container.resolve(".wine.broken-backup").apply { mkdirs() }
            backup.resolve("save.dat").writeText("save")
            val context = mockk<Context>()
            every { context.cacheDir } returns cache

            val result =
                StorageUsageScanner.deleteUnusedGuestData(
                    context,
                    listOf(container.absolutePath, backup.absolutePath),
                )

            assertEquals(0, result.bytesFreed)
            assertEquals(2, result.failedPaths.size)
            assertTrue(backup.resolve("save.dat").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupDeletesValidatedManagedTemporaryItem() {
        val root = Files.createTempDirectory("storage-cleanup-").toFile()
        try {
            val cache = root.resolve("cache").apply { mkdirs() }
            val temporary = cache.resolve("wineprefix-repair-012345.tmp").apply {
                mkdirs()
                resolve("payload").writeText("123456")
                setLastModified(System.currentTimeMillis() - TWO_DAYS_MS)
            }
            val context = mockk<Context>()
            every { context.cacheDir } returns cache

            val result =
                StorageUsageScanner.deleteUnusedGuestData(
                    context,
                    listOf(temporary.absolutePath),
                )

            assertEquals(6, result.bytesFreed)
            assertTrue(result.failedPaths.isEmpty())
            assertFalse(temporary.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupDoesNotFollowLinksInsideManagedTemporaryTree() {
        val root = Files.createTempDirectory("storage-symlink-").toFile()
        try {
            val cache = root.resolve("cache").apply { mkdirs() }
            val external = root.resolve("user-data").apply {
                mkdirs()
                resolve("save.dat").writeText("keep")
            }
            val temporary = cache.resolve("restore_interrupted.tmp").apply {
                mkdirs()
                resolve("payload").writeText("remove")
            }
            Files.createSymbolicLink(temporary.resolve("external").toPath(), external.toPath())
            temporary.setLastModified(System.currentTimeMillis() - TWO_DAYS_MS)
            val context = mockk<Context>()
            every { context.cacheDir } returns cache

            val result =
                StorageUsageScanner.deleteUnusedGuestData(
                    context,
                    listOf(temporary.absolutePath),
                )

            assertTrue(result.failedPaths.isEmpty())
            assertFalse(temporary.exists())
            assertEquals("keep", external.resolve("save.dat").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupRejectsManagedLookingSymlink() {
        val root = Files.createTempDirectory("storage-symlink-target-").toFile()
        try {
            val cache = root.resolve("cache").apply { mkdirs() }
            val external = root.resolve("user-data").apply {
                mkdirs()
                resolve("save.dat").writeText("keep")
            }
            val link = cache.resolve("wineprefix-repair-deadbeef.tmp")
            Files.createSymbolicLink(link.toPath(), external.toPath())
            val context = mockk<Context>()
            every { context.cacheDir } returns cache

            val result =
                StorageUsageScanner.deleteUnusedGuestData(
                    context,
                    listOf(link.absolutePath),
                )

            assertEquals(listOf(link.absolutePath), result.failedPaths)
            assertTrue(link.exists())
            assertEquals("keep", external.resolve("save.dat").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun managedTemporaryDataIncludesOnlyValidArchiveStageNames() {
        val root = Files.createTempDirectory("storage-archive-stage-").toFile()
        try {
            val cache = root.resolve("cache").apply { mkdirs() }
            val stage = cache.resolve("native-archive-stage").apply { mkdirs() }
            val old = System.currentTimeMillis() - TWO_DAYS_MS
            val valid = stage.resolve("archive-fonts-deadbeef.tmp").apply {
                writeText("valid")
                setLastModified(old)
            }
            stage.resolve("fonts-deadbeef.tmp").apply {
                writeText("lookalike")
                setLastModified(old)
            }
            stage.resolve("archive-fonts-not-hex.tmp").apply {
                writeText("lookalike")
                setLastModified(old)
            }

            val listed = StorageUsageScanner.managedTemporaryData(cache)

            assertEquals(listOf(valid.absolutePath), listed.mapNotNull(StorageEntry::removablePath))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun supersededWcpIsListedAndDeletedWhilePinnedInstallIsProtected() {
        val root = Files.createTempDirectory("storage-wcp-").toFile()
        try {
            val cache = root.resolve("cache").apply { mkdirs() }
            val context = mockk<Context>()
            every { context.filesDir } returns root
            every { context.cacheDir } returns cache
            val type = ContentProfile.ContentType.CONTENT_TYPE_PROTON
            val typeDir = ContentsManager.getContentTypeDir(context, type).apply { mkdirs() }
            val current = typeDir.resolve("11.0-current-1").apply {
                mkdirs()
                resolve("payload").writeText("current")
            }
            InstalledContentPin.write(current, PIN_SHA)
            val stale = typeDir.resolve("10.0-old-0").apply {
                mkdirs()
                resolve("payload").writeText("old")
            }
            val pins = mapOf(type to PinnedWcpInstall(current.name, PIN_SHA))

            val usage = StorageUsageScanner.scan(context, pins)
            val proton = usage.entries.first { it.label == "Proton" }
            assertEquals(stale.absolutePath, proton.children.first { it.label == stale.name }.removablePath)
            assertEquals(null, proton.children.first { it.label == current.name }.removablePath)

            val result =
                StorageUsageScanner.deleteUnusedGuestData(
                    context,
                    listOf(stale.absolutePath, current.absolutePath),
                    pins,
                )

            assertEquals(3L, result.bytesFreed)
            assertEquals(listOf(current.absolutePath), result.failedPaths)
            assertFalse(stale.exists())
            assertTrue(current.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val TWO_DAYS_MS = 2L * 24L * 60L * 60L * 1_000L
        val PIN_SHA = "b".repeat(64)
    }
}
