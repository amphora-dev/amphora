package app.amphora.core.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeAssetLocalOverrideTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun inactiveWithoutMarker() {
        val asset = tmp.newFile("wrapper.tzst")
        asset.writeText("blob")
        File(asset.absolutePath + ".sha256").writeText("a".repeat(64))
        assertFalse(RuntimeAssetLocalOverride.isActive(asset))
    }

    @Test
    fun activeWhenOverrideMatchesShaMarker() {
        val asset = tmp.newFile("wrapper.tzst")
        asset.writeText("blob")
        val sha = "b".repeat(64)
        RuntimeAssetLocalOverride.write(asset, sha)
        assertTrue(RuntimeAssetLocalOverride.isActive(asset))
    }

    @Test
    fun inactiveWhenOverrideShaDiverges() {
        val asset = tmp.newFile("wrapper.tzst")
        asset.writeText("blob")
        RuntimeAssetLocalOverride.write(asset, "c".repeat(64))
        File(asset.absolutePath + ".sha256").writeText("d".repeat(64))
        assertFalse(RuntimeAssetLocalOverride.isActive(asset))
    }

    @Test
    fun clearRemovesOverrideOnly() {
        val asset = tmp.newFile("wrapper.tzst")
        asset.writeText("blob")
        RuntimeAssetLocalOverride.write(asset, "e".repeat(64))
        RuntimeAssetLocalOverride.clear(asset)
        assertFalse(RuntimeAssetLocalOverride.markerFile(asset).isFile)
        assertTrue(RuntimeAssetLocalOverride.shaMarkerFile(asset).isFile)
        assertFalse(RuntimeAssetLocalOverride.isActive(asset))
    }
}
