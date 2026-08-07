package app.amphora.core.engine

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDrawWrapperCacheTest {
    @Test
    fun extractsUpstreamD7vkX32LayoutIntoSyswow64() {
        val root = Files.createTempDirectory("d7vk-cache-").toFile()
        try {
            val archive = File(root, "d7vk.zip")
            val payload = byteArrayOf(0x4d, 0x5a, 1, 2, 3)
            writeZip(
                archive,
                mapOf(
                    "d7vk-v2.0/x32/ddraw.dll" to payload,
                    "../escaped.dll" to byteArrayOf(9),
                    "d7vk-v2.0/x64/ddraw.dll" to byteArrayOf(8),
                ),
            )

            val destination = File(root, "out")
            assertTrue(DirectDrawWrapperCache.extractD7vkZip(archive, destination))
            assertArrayEquals(payload, File(destination, "syswow64/ddraw.dll").readBytes())
            assertFalse(File(root, "escaped.dll").exists())
            assertFalse(File(destination, "system32/ddraw.dll").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsArchiveWithoutX32Ddraw() {
        val root = Files.createTempDirectory("d7vk-cache-").toFile()
        try {
            val archive = File(root, "d7vk.zip")
            writeZip(archive, mapOf("d7vk-v2.0/x64/ddraw.dll" to byteArrayOf(1)))

            assertFalse(DirectDrawWrapperCache.extractD7vkZip(archive, File(root, "out")))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeZip(archive: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(archive.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
