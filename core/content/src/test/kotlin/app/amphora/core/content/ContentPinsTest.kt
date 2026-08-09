package app.amphora.core.content

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContentPinsTest {
    @Test
    fun installedContentRequiresMatchingShaMarker() = withTempDirectory { root ->
        val install = File(root, "DXVK/3.0-0").apply { mkdirs() }
        val first = "1".repeat(64)
        val second = "2".repeat(64)

        assertFalse(InstalledContentPin.matches(install, first))
        InstalledContentPin.write(install, first.uppercase())
        assertTrue(InstalledContentPin.matches(install, first))
        assertFalse(InstalledContentPin.matches(install, second))
        assertEquals(first, InstalledContentPin.read(install))
    }

    @Test
    fun appliedAssetTracksSourcePinPerTarget() = withTempDirectory { root ->
        val assets = File(root, "runtime-assets")
        val source = File(assets, "graphics_driver/wrapper.tzst")
        source.parentFile.mkdirs()
        source.writeText("payload")
        AssetDigest.writePin(source, "a".repeat(64))
        val target = File(root, "imagefs").apply { mkdirs() }

        assertTrue(AppliedAssetPin.needsApply(target, source, "graphics_driver/wrapper.tzst"))
        AppliedAssetPin.markApplied(target, source, "graphics_driver/wrapper.tzst")
        assertFalse(AppliedAssetPin.needsApply(target, source, "graphics_driver/wrapper.tzst"))

        AssetDigest.writePin(source, "b".repeat(64))
        assertTrue(AppliedAssetPin.needsApply(target, source, "graphics_driver/wrapper.tzst"))
    }

    @Test
    fun fingerprintIsOrderIndependentAndChangesWithPins() = withTempDirectory { root ->
        val first = File(root, "a.tzst").apply { writeText("a") }
        val second = File(root, "nested/b.tzst").apply {
            parentFile.mkdirs()
            writeText("b")
        }
        AssetDigest.writePin(first, "a".repeat(64))
        AssetDigest.writePin(second, "b".repeat(64))

        val original = AppliedAssetPin.fingerprint(root, listOf("a.tzst", "nested/b.tzst"))
        assertEquals(original, AppliedAssetPin.fingerprint(root, listOf("nested/b.tzst", "a.tzst")))

        AssetDigest.writePin(second, "c".repeat(64))
        assertNotEquals(original, AppliedAssetPin.fingerprint(root, listOf("a.tzst", "nested/b.tzst")))
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("content-pins-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
