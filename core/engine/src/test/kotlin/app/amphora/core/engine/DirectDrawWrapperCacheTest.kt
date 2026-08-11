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

    @Test
    fun cncDdrawCompletenessRequiresIniAndEveryShaderSidecar() {
        val syswow64 = Files.createTempDirectory("cnc-ddraw-integrity-").toFile()
        try {
            syswow64.resolve("ddraw.dll").writeText("dll")
            syswow64.resolve("ddraw.ini").writeText("ini")
            CNC_DDRAW_SHADER_SIDECARS.forEach {
                syswow64.resolve(it).apply {
                    requireNotNull(parentFile).mkdirs()
                    writeText("shader")
                }
            }
            assertTrue(directDrawInstallComplete(DirectDrawWrapperIds.CNC_DDRAW, syswow64))

            syswow64.resolve(CNC_DDRAW_SHADER_SIDECARS.first()).delete()
            assertFalse(directDrawInstallComplete(DirectDrawWrapperIds.CNC_DDRAW, syswow64))
        } finally {
            syswow64.deleteRecursively()
        }
    }

    @Test
    fun dd7to9CompletenessRequiresPrivateIni() {
        val syswow64 = Files.createTempDirectory("dd7to9-integrity-").toFile()
        try {
            syswow64.resolve("ddraw.dll").writeText("dll")
            syswow64.resolve("dxwrapper.dll").writeText("dll")
            assertFalse(
                directDrawInstallComplete(
                    DirectDrawWrapperIds.DXWRAPPER_DD7TO9,
                    syswow64,
                ),
            )
            syswow64.resolve("dxwrapper.ini").writeText("ini")
            assertTrue(
                directDrawInstallComplete(
                    DirectDrawWrapperIds.DXWRAPPER_DD7TO9,
                    syswow64,
                ),
            )
        } finally {
            syswow64.deleteRecursively()
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
