package app.amphora.core.content

import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.ManifestEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContentManifestTest {
    @Test fun parsesValidatedStartupComponents() {
        val e = ContentManifest.parse(SAMPLE).entry(ContentComponent.WINE)
        assertNotNull(e)
        e!!
        assertEquals(ManifestEntry.Kind.WCP, e.kind)
        assertEquals("Proton-10.0-4-x86_64.wcp", e.assetPath)
        assertEquals("Proton-10.0-4-x86_64-0", e.version)
        assertEquals("a".repeat(64), e.sha256)
        assertEquals("Proton", e.contentType)
        assertEquals("10.0-4-x86_64", e.verName)
        assertEquals(0, e.verCode)
        assertEquals(ContentComponent.entries.toSet(), ContentManifest.parse(SAMPLE).all().map { it.component }.toSet())
    }

    @Test fun parsesRootfsEntryAndCompression() {
        val e = ContentManifest.parse(SAMPLE).entry(ContentComponent.ROOTFS)!!
        assertEquals(ManifestEntry.Kind.ROOTFS, e.kind)
        assertEquals(ManifestEntry.Compression.XZ, e.compression)
        assertEquals("27", e.version)
    }

    @Test fun parsesTopLevelFields() {
        val m = ContentManifest.parse(SAMPLE)
        assertEquals("https://catalog.example/default.json", m.wcpCatalogUrl)
    }

    @Test fun parsesRuntimeAssets() {
        val assets = ContentManifest.parse(SAMPLE).runtimeAssets()
        assertEquals(2, assets.size)
        val wrapper = assets.single { it.assetPath == "graphics_driver/wrapper.tzst" }
        assertEquals("c".repeat(64), wrapper.sha256)
        assertEquals("https://cdn.example/wrapper.tzst", wrapper.remoteUrl)
        assertEquals(667959L, wrapper.size)
        assertEquals(null, assets.single { it.assetPath == "metadata/gpu_cards.json" }.size)
    }

    @Test fun runtimeAssetsDefaultToEmptyWhenAbsent() {
        val json = JSONObject(SAMPLE).apply { remove("runtimeAssets") }.toString()
        val m = ContentManifest.parse(json)
        assertTrue(m.runtimeAssets().isEmpty())
    }

    @Test fun archiveDefaultsToZstdWhenCompressionAbsent() {
        val root = JSONObject(SAMPLE)
        root.getJSONObject("components").getJSONObject("dxvk").remove("compression")
        val json = root.toString()
        val e = ContentManifest.parse(json).entry(ContentComponent.DXVK)!!
        assertEquals(ManifestEntry.Compression.ZSTD, e.compression)
    }

    @Test fun unknownComponentKeyIsSkippedNotFatal() {
        val root = JSONObject(SAMPLE)
        root.getJSONObject("components").put("someFutureThing", JSONObject().put("anything", true))
        val m = ContentManifest.parse(root.toString())
        assertEquals(ContentComponent.entries.size, m.all().size)
    }

    @Test fun rejectsMissingStartupComponent() {
        for (component in ContentComponent.entries) {
            val root = JSONObject(SAMPLE)
            root.getJSONObject("components").remove(component.name.lowercase())
            assertInvalid(root.toString())
        }
    }

    @Test fun rejectsMalformedShaUnsafePathAndInsecureUrl() {
        val badSha = JSONObject(SAMPLE)
        badSha.getJSONObject("components").getJSONObject("wine").put("sha256", "abc")
        assertInvalid(badSha.toString())

        val unsafePath = JSONObject(SAMPLE)
        unsafePath.getJSONObject("components").getJSONObject("box64").put("assetPath", "../box64.wcp")
        assertInvalid(unsafePath.toString())

        val insecureUrl = JSONObject(SAMPLE)
        insecureUrl.getJSONArray("runtimeAssets").getJSONObject(0).put("remoteUrl", "http://cdn.example/wrapper")
        assertInvalid(insecureUrl.toString())
    }

    @Test fun rejectsNonPositiveManifestRootfsVersionAndSize() {
        val badManifestVersion = JSONObject(SAMPLE).put("version", 0)
        assertInvalid(badManifestVersion.toString())

        val badRootfsVersion = JSONObject(SAMPLE)
        badRootfsVersion.getJSONObject("components").getJSONObject("rootfs").put("version", "0")
        assertInvalid(badRootfsVersion.toString())

        val badSize = JSONObject(SAMPLE)
        badSize.getJSONObject("components").getJSONObject("dxvk").put("size", 0)
        assertInvalid(badSize.toString())
    }

    private fun assertInvalid(json: String) {
        try {
            ContentManifest.parse(json)
            fail("Expected structurally invalid manifest to be rejected")
        } catch (_: RuntimeException) {
            // Expected: require()/JSONObject validation failures are both fatal.
        }
    }

    companion object {
        val SAMPLE =
            """
            {
              "version": 1,
              "wcpCatalogUrl": "https://catalog.example/default.json",
              "components": {
                "wine": {
                  "assetPath": "Proton-10.0-4-x86_64.wcp",
                  "sha256": "${"a".repeat(64)}",
                  "version": "Proton-10.0-4-x86_64-0",
                  "kind": "WCP",
                  "contentType": "Proton",
                  "verName": "10.0-4-x86_64",
                  "verCode": 0,
                  "size": 100
                },
                "box64": {
                  "assetPath": "Box64-0.3.0.wcp",
                  "sha256": "${"d".repeat(64)}",
                  "version": "Box64-0.3.0-0",
                  "kind": "WCP",
                  "contentType": "Box64",
                  "verName": "0.3.0",
                  "verCode": 0,
                  "size": 200
                },
                "dxvk": {
                  "assetPath": "dxvk.tzst",
                  "sha256": "${"b".repeat(64)}",
                  "version": "1",
                  "kind": "ARCHIVE",
                  "compression": "zstd",
                  "remoteUrl": "https://cdn.example/dxvk.tzst",
                  "size": 300
                },
                "dxvk_sarek": {
                  "assetPath": "Dxvk-sarek-1.11.wcp",
                  "sha256": "${"f".repeat(64)}",
                  "version": "DXVK-1.11-sarek-0",
                  "kind": "WCP",
                  "contentType": "DXVK",
                  "verName": "1.11-sarek",
                  "verCode": 0,
                  "remoteUrl": "https://cdn.example/Dxvk-sarek-1.11.wcp",
                  "size": 320
                },
                "vkd3d": {
                  "assetPath": "Vkd3d-3.0.wcp",
                  "sha256": "${"e".repeat(64)}",
                  "version": "VKD3D-3.0-0",
                  "kind": "WCP",
                  "contentType": "VKD3D",
                  "verName": "3.0",
                  "verCode": 0,
                  "size": 400
                },
                "rootfs": {
                  "assetPath": "imagefs.txz",
                  "sha256": "${"f".repeat(64)}",
                  "version": "27",
                  "kind": "ROOTFS",
                  "compression": "xz",
                  "remoteUrl": "https://cdn.example/imagefs.txz",
                  "size": 9814876
                }
              },
              "runtimeAssets": [
                {
                  "assetPath": "graphics_driver/wrapper.tzst",
                  "sha256": "${"c".repeat(64)}",
                  "remoteUrl": "https://cdn.example/wrapper.tzst",
                  "size": 667959
                },
                {
                  "assetPath": "metadata/gpu_cards.json",
                  "sha256": "${"f".repeat(64)}",
                  "remoteUrl": "https://cdn.example/gpu_cards.json"
                }
              ]
            }
            """.trimIndent()
    }
}
