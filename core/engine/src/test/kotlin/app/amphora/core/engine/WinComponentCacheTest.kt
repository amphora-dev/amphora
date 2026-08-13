package app.amphora.core.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WinComponentCacheTest {
    @Test
    fun payloadFilesIncludeDllAndExeButSkipPinMarker() {
        val root = Files.createTempDirectory("wincomponent-cache-").toFile()
        try {
            File(root, "system32").mkdirs()
            File(root, "syswow64").mkdirs()
            File(root, "system32/msvcr100.dll").writeBytes(byteArrayOf(0x4d, 0x5a))
            File(root, "syswow64/dpnsvr.exe").writeBytes(byteArrayOf(0x4d, 0x5a, 1))
            File(root, ".amphora-source.sha256").writeText("a".repeat(64))
            File(root, "readme.txt").writeText("skip")

            val names = WinComponentCache.payloadFiles(root).map { it.name }.toSet()
            assertEquals(setOf("msvcr100.dll", "dpnsvr.exe"), names)
            assertTrue(WinComponentCache.cachePayloadIsSafe(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun payloadSafetyRejectsSymlinks() {
        val root = Files.createTempDirectory("wincomponent-link-").toFile()
        try {
            val dll = File(root, "system32/msvcr100.dll")
            requireNotNull(dll.parentFile).mkdirs()
            dll.writeBytes(byteArrayOf(0x4d, 0x5a))
            val link = File(root, "syswow64/msvcr100.dll")
            requireNotNull(link.parentFile).mkdirs()
            Files.createSymbolicLink(link.toPath(), dll.toPath())

            assertFalse(WinComponentCache.cachePayloadIsSafe(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptyCacheIsUnsafe() {
        val root = Files.createTempDirectory("wincomponent-empty-").toFile()
        try {
            File(root, "system32").mkdirs()
            File(root, ".amphora-source.sha256").writeText("a".repeat(64))
            assertFalse(WinComponentCache.cachePayloadIsSafe(root))
            assertTrue(WinComponentCache.payloadFiles(root).none())
        } finally {
            root.deleteRecursively()
        }
    }
}
