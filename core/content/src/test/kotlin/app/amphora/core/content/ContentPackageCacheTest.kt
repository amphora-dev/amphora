package app.amphora.core.content

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContentPackageCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun pruneToPinsKeepsCurrentAssetAndSidecars() {
        val root = temporaryFolder.newFolder("amphora-packages")
        File(root, "Proton-11.0-amphora-x86_64.wcp").writeText("keep")
        File(root, "Proton-11.0-amphora-x86_64.wcp.sha256").writeText("a".repeat(64))
        File(root, "Proton-11.0-amphora-x86_64.wcp.part").writeText("part")
        File(root, "Proton-10.0-4-x86_64.wcp").writeText("stale")
        File(root, "Proton-10.0-4-x86_64.wcp.sha256").writeText("b".repeat(64))
        File(root, "Box64-0.4.5-e0ae94d74.wcp").writeText("keep-box")

        val removed =
            ContentPackageCache.pruneToPins(
                root,
                setOf(
                    "Proton-11.0-amphora-x86_64.wcp",
                    "Box64-0.4.5-e0ae94d74.wcp",
                ),
            )

        assertEquals(2, removed)
        assertTrue(File(root, "Proton-11.0-amphora-x86_64.wcp").isFile)
        assertTrue(File(root, "Proton-11.0-amphora-x86_64.wcp.sha256").isFile)
        assertTrue(File(root, "Proton-11.0-amphora-x86_64.wcp.part").isFile)
        assertTrue(File(root, "Box64-0.4.5-e0ae94d74.wcp").isFile)
        assertFalse(File(root, "Proton-10.0-4-x86_64.wcp").exists())
        assertFalse(File(root, "Proton-10.0-4-x86_64.wcp.sha256").exists())
    }
}
