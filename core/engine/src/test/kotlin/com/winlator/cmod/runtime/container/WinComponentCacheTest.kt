package com.winlator.cmod.runtime.container

import app.amphora.core.content.InstalledContentPin
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WinComponentCacheTest {
    @Test
    fun acceptsCompleteSystem32AndSyswow64Payload() {
        withTempDirectory { cache ->
            cache.resolve("system32/d3dx9_43.dll").writePayload()
            cache.resolve("syswow64/d3dx9_43.dll").writePayload()
            cache.resolve("system32/d3dcompiler_47.dll").writePayload()
            cache.resolve(InstalledContentPin.MARKER_NAME).writeText("0".repeat(64))

            assertTrue(
                WinComponentCache.cachePayloadIsSafe(
                    cache,
                    setOf("d3dx9_43.dll", "d3dcompiler_47.dll"),
                ),
            )
        }
    }

    @Test
    fun rejectsPayloadMissingDeclaredFile() {
        withTempDirectory { cache ->
            cache.resolve("system32/d3dx9_43.dll").writePayload()

            assertFalse(
                WinComponentCache.cachePayloadIsSafe(
                    cache,
                    setOf("d3dx9_43.dll", "d3dcompiler_47.dll"),
                ),
            )
        }
    }

    @Test
    fun rejectsFilesOutsideWindowsSystemDirectories() {
        withTempDirectory { cache ->
            cache.resolve("system32/d3dx9_43.dll").writePayload()
            cache.resolve("unexpected.dll").writePayload()

            assertFalse(
                WinComponentCache.cachePayloadIsSafe(
                    cache,
                    setOf("d3dx9_43.dll"),
                ),
            )
        }
    }

    @Test
    fun rejectsSymbolicLinksInCachePayload() {
        withTempDirectory { cache ->
            val source = Files.createTempFile("wincomponent-source-", ".dll").toFile()
            try {
                source.writePayload()
                val link = cache.resolve("system32/d3dx9_43.dll")
                requireNotNull(link.parentFile).mkdirs()
                Files.createSymbolicLink(link.toPath(), source.toPath())

                assertFalse(
                    WinComponentCache.cachePayloadIsSafe(
                        cache,
                        setOf("d3dx9_43.dll"),
                    ),
                )
            } finally {
                source.delete()
            }
        }
    }

    private fun File.writePayload() {
        requireNotNull(parentFile).mkdirs()
        writeBytes(byteArrayOf(0x4d, 0x5a, 1))
    }

    private inline fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("wincomponent-cache-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
