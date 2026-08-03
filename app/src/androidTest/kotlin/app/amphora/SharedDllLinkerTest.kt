package app.amphora

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.winlator.cmod.runtime.content.SharedDllLinker
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedDllLinkerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val testId = UUID.randomUUID().toString()
    private val contentsRoot = File(context.filesDir, "contents")
    private val sourceDir = File(contentsRoot, "LINK_TEST/$testId")
    private val targetDir = File(context.filesDir, "tmp/link-test/$testId")

    @After
    fun clean() {
        targetDir.deleteRecursively()
        sourceDir.deleteRecursively()
    }

    @Test
    fun componentDllIsAtomicallyLinkedAndReadable() {
        val source = File(sourceDir, "source.dll")
        val target = File(targetDir, "system32/source.dll")
        val payload = byteArrayOf(0x4d, 0x5a, 1, 2, 3)
        source.parentFile!!.mkdirs()
        source.writeBytes(payload)
        target.parentFile!!.mkdirs()
        target.writeText("stale")

        assertTrue(SharedDllLinker.link(contentsRoot, source, target))
        assertTrue(Files.isSymbolicLink(target.toPath()))
        assertArrayEquals(payload, target.readBytes())
        assertFalse("shared source must be read-only", source.canWrite())
    }

    @Test
    fun sourceOutsideContentsIsRejected() {
        val source = File(targetDir, "outside.dll")
        val target = File(targetDir, "system32/outside.dll")
        source.parentFile!!.mkdirs()
        source.writeText("MZ")

        assertFalse(SharedDllLinker.link(contentsRoot, source, target))
        assertFalse(Files.isSymbolicLink(target.toPath()))
    }
}
