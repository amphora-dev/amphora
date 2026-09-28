package app.amphora.core.content

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeAssetProvisionerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun File.touch(relative: String): File = File(this, relative).apply {
        parentFile?.mkdirs()
        writeText(relative)
    }

    @Test
    fun pruneRemovesAssetsWhosePathIsNoLongerPinned() {
        val root = temporaryFolder.newFolder("runtime-assets")
        root.touch("old/Graphics-Test-32bit.exe")
        root.touch("old/Graphics-Test-32bit.exe.sha256")
        root.touch("new/Graphics-Test-32bit.exe")
        root.touch("new/Graphics-Test-32bit.exe.sha256")

        val removed = RuntimeAssetProvisioner.pruneUnpinned(root, setOf("new/Graphics-Test-32bit.exe"))

        assertEquals(listOf("old/Graphics-Test-32bit.exe", "old/Graphics-Test-32bit.exe.sha256"), removed)
        assertFalse(File(root, "old").exists())
        assertTrue(File(root, "new/Graphics-Test-32bit.exe").isFile)
        assertTrue(File(root, "new/Graphics-Test-32bit.exe.sha256").isFile)
    }

    @Test
    fun pruneKeepsInFlightFilesOfPinnedAssets() {
        val root = temporaryFolder.newFolder("in-flight")
        root.touch("fonts.tzst.part")
        root.touch("fonts.tzst.sha256.tmp")
        root.touch("stale.tzst.part")

        val removed = RuntimeAssetProvisioner.pruneUnpinned(root, setOf("fonts.tzst"))

        assertEquals(listOf("stale.tzst.part"), removed)
        assertTrue(File(root, "fonts.tzst.part").isFile)
        assertTrue(File(root, "fonts.tzst.sha256.tmp").isFile)
    }

    @Test
    fun pruneOfMissingRootIsANoOp() {
        val root = File(temporaryFolder.root, "absent")

        assertEquals(emptyList<String>(), RuntimeAssetProvisioner.pruneUnpinned(root, emptySet()))
        assertFalse(root.exists())
    }
}
