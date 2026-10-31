package app.amphora.core.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAssetDllCacheTest {
    @Test
    fun payloadWithDllsAndLicenseTextIsSafe() {
        val root = Files.createTempDirectory("runtime-dll-cache-").toFile()
        try {
            File(root, "system32").mkdirs()
            File(root, "licenses").mkdirs()
            File(root, "system32/opengl32.dll").writeBytes(byteArrayOf(0x4d, 0x5a))
            File(root, "licenses/MIT").writeText("license")
            File(root, ".amphora-source.sha256").writeText("a".repeat(64))

            assertTrue(RuntimeAssetDllCache.cachePayloadIsSafe(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun payloadWithSymlinkIsUnsafe() {
        val root = Files.createTempDirectory("runtime-dll-link-").toFile()
        try {
            val dll = File(root, "system32/opengl32.dll")
            requireNotNull(dll.parentFile).mkdirs()
            dll.writeBytes(byteArrayOf(0x4d, 0x5a))
            val link = File(root, "syswow64/opengl32.dll")
            requireNotNull(link.parentFile).mkdirs()
            Files.createSymbolicLink(link.toPath(), dll.toPath())

            assertFalse(RuntimeAssetDllCache.cachePayloadIsSafe(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun payloadWithoutDllIsUnsafe() {
        val root = Files.createTempDirectory("runtime-dll-empty-").toFile()
        try {
            File(root, "licenses").mkdirs()
            File(root, "licenses/MIT").writeText("license")
            assertFalse(RuntimeAssetDllCache.cachePayloadIsSafe(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingFilesReportsExactlyTheAbsentPaths() {
        val root = Files.createTempDirectory("runtime-dll-missing-").toFile()
        try {
            File(root, "system32").mkdirs()
            File(root, "syswow64/opengl32.dll").mkdirs()
            File(root, "system32/opengl32.dll").writeBytes(byteArrayOf(0x4d, 0x5a))
            val required =
                listOf("system32/opengl32.dll", "system32/libgallium_wgl.dll", "syswow64/opengl32.dll")

            assertEquals(
                listOf("system32/libgallium_wgl.dll", "syswow64/opengl32.dll"),
                RuntimeAssetDllCache.missingFiles(root, required),
            )
            assertEquals(emptyList<String>(), RuntimeAssetDllCache.missingFiles(root, emptyList()))
        } finally {
            root.deleteRecursively()
        }
    }
}
