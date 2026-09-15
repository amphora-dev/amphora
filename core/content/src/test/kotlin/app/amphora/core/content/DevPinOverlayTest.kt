package app.amphora.core.content

import app.amphora.core.content.model.ContentComponent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DevPinOverlayTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readReturnsEmptyWhenMissing() {
        val dir = temporaryFolder.newFolder("content")
        assertTrue(DevPinOverlay.read(dir).isEmpty)
    }

    @Test
    fun readIgnoresMalformedOverlay() {
        val dir = temporaryFolder.newFolder("content-bad")
        DevPinOverlay.file(dir).writeText("{not json")
        assertTrue(DevPinOverlay.read(dir).isEmpty)
    }

    @Test
    fun applyPatchesComponentShaAndTracksOverride() {
        val remote = ContentManifest.parse(ContentManifestTest.SAMPLE)
        val newSha = "1".repeat(64)
        val pins =
            DevPins(
                components =
                mapOf(
                    "box64" to
                        ComponentPinPatch(
                            sha256 = newSha,
                            size = 2702324L,
                            assetPath = "Box64-dev.wcp",
                        ),
                ),
            )

        val (effective, application) = DevPinOverlay.apply(remote, pins)

        assertEquals(newSha, effective.entry(ContentComponent.BOX64)!!.sha256)
        assertEquals(2702324L, effective.entry(ContentComponent.BOX64)!!.size)
        assertEquals("Box64-dev.wcp", effective.entry(ContentComponent.BOX64)!!.assetPath)
        // Unpatched fields preserved (including remoteUrl / version metadata).
        assertEquals(
            remote.entry(ContentComponent.BOX64)!!.verName,
            effective.entry(ContentComponent.BOX64)!!.verName,
        )
        assertEquals(setOf(ContentComponent.BOX64), application.overriddenComponents)
        assertTrue(application.overriddenRuntimeAssets.isEmpty())
        // Other components unchanged.
        assertEquals(
            remote.entry(ContentComponent.WINE)!!.sha256,
            effective.entry(ContentComponent.WINE)!!.sha256,
        )
    }

    @Test
    fun applyPatchesRuntimeAssetAndKeepsRemoteUrlWhenNull() {
        val remote = ContentManifest.parse(ContentManifestTest.SAMPLE)
        val newSha = "2".repeat(64)
        val pins =
            DevPins(
                runtimeAssets =
                mapOf(
                    "graphics_driver/wrapper.tzst" to
                        RuntimeAssetPinPatch(sha256 = newSha, size = 99L, remoteUrl = null),
                ),
            )

        val (effective, application) = DevPinOverlay.apply(remote, pins)
        val wrapper = effective.runtimeAssets().single { it.assetPath == "graphics_driver/wrapper.tzst" }

        assertEquals(newSha, wrapper.sha256)
        assertEquals(99L, wrapper.size)
        assertEquals("https://cdn.example/wrapper.tzst", wrapper.remoteUrl)
        assertEquals(setOf("graphics_driver/wrapper.tzst"), application.overriddenRuntimeAssets)
    }

    @Test
    fun applyIgnoresUnknownComponentKeys() {
        val remote = ContentManifest.parse(ContentManifestTest.SAMPLE)
        val pins =
            DevPins(
                components =
                mapOf(
                    "notARealComponent" to ComponentPinPatch(sha256 = "3".repeat(64)),
                ),
            )

        val (effective, application) = DevPinOverlay.apply(remote, pins)
        assertEquals(remote.entry(ContentComponent.BOX64)!!.sha256, effective.entry(ContentComponent.BOX64)!!.sha256)
        assertTrue(application.overriddenComponents.isEmpty())
    }

    @Test
    fun writeRoundTripAndClear() {
        val dir = temporaryFolder.newFolder("content-write")
        val pins =
            DevPins(
                components =
                mapOf(
                    "box64" to ComponentPinPatch(sha256 = "a".repeat(64), size = 10L),
                ),
                runtimeAssets =
                mapOf(
                    "graphics_driver/wrapper.tzst" to RuntimeAssetPinPatch(sha256 = "b".repeat(64)),
                ),
            )
        DevPinOverlay.write(dir, pins)
        assertTrue(DevPinOverlay.file(dir).isFile)

        val loaded = DevPinOverlay.read(dir)
        assertEquals("a".repeat(64), loaded.components["box64"]!!.sha256)
        assertEquals(10L, loaded.components["box64"]!!.size)
        assertEquals(
            "b".repeat(64),
            loaded.runtimeAssets["graphics_driver/wrapper.tzst"]!!.sha256,
        )

        DevPinOverlay.clear(dir)
        assertFalse(DevPinOverlay.file(dir).isFile)
        assertTrue(DevPinOverlay.read(dir).isEmpty)
    }
}
