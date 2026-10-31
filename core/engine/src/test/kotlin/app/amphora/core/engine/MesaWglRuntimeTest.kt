package app.amphora.core.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MesaWglRuntimeTest {
    private fun withLinkedPrefix(block: (cache: File, windows: File) -> Unit) {
        val root = Files.createTempDirectory("mesa-wgl-").toFile()
        try {
            val cache = File(root, "contents/MESAWGL/mesa-wgl-" + "a".repeat(64))
            val windows = File(root, "windows")
            for (relative in MesaWglRuntime.PREFIX_DLLS) {
                val source = File(cache, relative)
                requireNotNull(source.parentFile).mkdirs()
                source.writeBytes(byteArrayOf(0x4d, 0x5a))
                val target = File(windows, relative)
                requireNotNull(target.parentFile).mkdirs()
                Files.createSymbolicLink(target.toPath(), source.toPath())
            }
            block(cache, windows)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun linksAreFreshWhenEveryDllIsALinkToAFile() = withLinkedPrefix { _, windows ->
        assertFalse(MesaWglRuntime.linksNeedRefresh(windows))
    }

    @Test
    fun missingLinkNeedsRefresh() = withLinkedPrefix { _, windows ->
        File(windows, "syswow64/libgallium_wgl.dll").delete()
        assertTrue(MesaWglRuntime.linksNeedRefresh(windows))
    }

    @Test
    fun plainFileNeedsRefresh() = withLinkedPrefix { _, windows ->
        val builtin = File(windows, "system32/opengl32.dll")
        builtin.delete()
        builtin.writeBytes(byteArrayOf(0x4d, 0x5a))
        assertTrue(MesaWglRuntime.linksNeedRefresh(windows))
    }

    @Test
    fun danglingLinkNeedsRefresh() = withLinkedPrefix { cache, windows ->
        File(cache, "syswow64/opengl32.dll").delete()
        assertTrue(MesaWglRuntime.linksNeedRefresh(windows))
    }

    @Test
    fun pruneSiblingsRemovesOnlyOtherMesaWglCaches() = withLinkedPrefix { cache, _ ->
        val typeRoot = requireNotNull(cache.parentFile)
        val old = File(typeRoot, "mesa-wgl-old").apply { mkdirs() }
        File(old, "system32").mkdirs()
        val other = File(typeRoot, "other-x").apply { mkdirs() }

        assertEquals(listOf(old), MesaWglRuntime.pruneSiblings(cache))
        assertFalse(old.exists())
        assertTrue(cache.isDirectory)
        assertTrue(other.isDirectory)
    }

    private val base =
        "csmt=0x3,strict_shader_math=0x1,OffscreenRenderingMode=fbo,VideoMemorySize=4096," +
            "VideoPciDeviceID=1729,VideoPciVendorID=4318,renderer=gl"

    @Test
    fun wined3dKeepsConfiguredRendererWhenZinkCanRun() {
        assertEquals(base, MesaWglRuntime.wined3dConfig(base, emptyList()))
    }

    @Test
    fun wined3dUsesGdiWhenZinkIsBlocked() {
        val config = MesaWglRuntime.wined3dConfig(base, listOf("extension VK_KHR_maintenance5"))
        val fields = config.split(',')
        assertEquals(listOf("renderer=gdi"), fields.filter { it.startsWith("renderer=") })
        assertEquals(base.split(',').filterNot { it.startsWith("renderer=") }, fields.dropLast(1))
    }

    @Test
    fun wined3dGdiIsAddedWhenBaseHasNoRenderer() {
        assertEquals(
            "csmt=0x3,renderer=gdi",
            MesaWglRuntime.wined3dConfig("csmt=0x3", listOf("extension VK_KHR_dynamic_rendering")),
        )
    }
}
