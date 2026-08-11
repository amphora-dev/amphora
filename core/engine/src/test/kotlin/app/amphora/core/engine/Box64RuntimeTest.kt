package app.amphora.core.engine

import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.display.environment.ImageFs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Box64RuntimeTest {
    @Test
    fun applyFailurePersistsInvalidationForNextLaunch() {
        val root = Files.createTempDirectory("box64-invalidation-").toFile()
        try {
            val containerRoot = root.resolve("container").apply { mkdirs() }
            val container = Container(1).apply {
                rootDir = containerRoot
                box64Version = VERSION
            }
            AppliedMarks.markBox64(container, "previous-success")
            assertTrue(container.saveData())

            val profile =
                ContentProfile().apply {
                    type = ContentProfile.ContentType.CONTENT_TYPE_BOX64
                    verName = "1.0"
                    verCode = 1
                    isInstalled = true
                }
            val contentsManager = mockk<ContentsManager>()
            every { contentsManager.getProfileByEntryName(any()) } returns profile
            every { contentsManager.applyContent(profile) } returns false
            val imageFsRoot = root.resolve("imagefs").apply { mkdirs() }
            val imageFs = mockk<ImageFs>()
            every { imageFs.getRootDir() } returns imageFsRoot

            val failure =
                assertThrows(IllegalStateException::class.java) {
                    Box64Runtime.ensureApplied(container, imageFs, contentsManager)
                }

            assertTrue(failure.message.orEmpty().contains("apply failed"))
            verify(exactly = 1) { contentsManager.applyContent(profile) }
            assertTrue(AppliedMarks.needsBox64(container, "previous-success"))
            assertFalse(imageFsRoot.resolve("usr/bin/box64").exists())

            val reloaded = Container(1).apply {
                rootDir = containerRoot
                loadData(JSONObject(containerRoot.resolve(".container").readText()))
            }
            assertEquals("", AppliedMarks.box64(reloaded))
            assertTrue(AppliedMarks.needsBox64(reloaded, "previous-success"))
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val VERSION = "1.0-1"
    }
}
