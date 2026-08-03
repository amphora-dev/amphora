package app.amphora

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.runtime.audio.AlsaRuntimeSupport
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlsaRuntimeSupportTest {
    @Test
    fun ensureImageFsLayout_bridgesSonameAndPluginDir() {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "alsa-layout-${System.nanoTime()}",
        )
        val libDir = File(root, "usr/lib").apply { mkdirs() }
        File(libDir, "libasound.so").writeBytes(byteArrayOf(1, 2, 3))
        File(libDir, "asound_module_pcm_android_aserver.so").writeBytes(byteArrayOf(4, 5, 6))

        AlsaRuntimeSupport.ensureImageFsLayout(root)

        val asound2 = File(libDir, "libasound.so.2")
        assertTrue(Files.isSymbolicLink(asound2.toPath()))
        assertEquals("libasound.so", Files.readSymbolicLink(asound2.toPath()).toString())

        val stdPlugin = File(libDir, "alsa-lib/libasound_module_pcm_android_aserver.so")
        assertTrue(Files.isSymbolicLink(stdPlugin.toPath()))
        assertEquals(
            "../asound_module_pcm_android_aserver.so",
            Files.readSymbolicLink(stdPlugin.toPath()).toString(),
        )
        assertTrue(stdPlugin.isFile)

        // Idempotent.
        AlsaRuntimeSupport.ensureImageFsLayout(root)
        assertEquals("libasound.so", Files.readSymbolicLink(asound2.toPath()).toString())
    }
}
