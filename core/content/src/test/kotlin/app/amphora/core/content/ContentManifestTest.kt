package app.amphora.core.content

import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.ManifestEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit test for [ContentManifest.parse]: both provisioning kinds
 * (WCP + ARCHIVE), null-SHA skipping, optional-field defaults, the ComponentId
 * mapping, `runtimeAssets[]`, and forward compatibility with unknown keys.
 *
 * Whether the *real* manifest is well-formed is checked where it is edited, by
 * `amphora-dev/content_manifest`'s `validate_manifest.py`. Asserting that here
 * would mean keeping a copy of the manifest in this repo, and that copy would
 * silently go stale.
 */
class ContentManifestTest {
    @Test fun parsesWcpEntryWithNullSha() {
        val e = ContentManifest.parse(SAMPLE).entry(ContentComponent.WINE)
        assertNotNull(e)
        e!!
        assertEquals(ManifestEntry.Kind.WCP, e.kind)
        assertEquals("Proton-10.0-4-x86_64.wcp", e.assetPath)
        assertEquals("Proton-10.0-4-x86_64-0", e.version)
        assertNull("null sha256 must parse to null (not \"null\")", e.sha256)
        assertEquals("Proton", e.contentType)
        assertEquals("10.0-4-x86_64", e.verName)
        assertEquals(0, e.verCode)
    }

    @Test fun parsesArchiveEntryWithSha() {
        val e = ContentManifest.parse(SAMPLE).entry(ContentComponent.DXVK)!!
        assertEquals(ManifestEntry.Kind.ARCHIVE, e.kind)
        assertEquals(ManifestEntry.Compression.ZSTD, e.compression)
        assertEquals("b".repeat(64), e.sha256)
        assertNull(e.contentType)
        assertNull(e.verName)
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
        val ids = m.all().map { it.component }.toSet()
        assertTrue("wine missing", ContentComponent.WINE in ids)
        assertTrue("rootfs missing", ContentComponent.ROOTFS in ids)
    }

    @Test fun parsesRuntimeAssets() {
        val assets = ContentManifest.parse(SAMPLE).runtimeAssets()
        assertEquals(2, assets.size)
        val wrapper = assets.single { it.assetPath == "graphics_driver/wrapper.tzst" }
        assertEquals("c".repeat(64), wrapper.sha256)
        assertEquals("https://cdn.example/wrapper.tzst", wrapper.remoteUrl)
        assertEquals(667959L, wrapper.size)
        // size is optional; the second entry omits it.
        assertNull(assets.single { it.assetPath == "metadata/gpu_cards.json" }.size)
    }

    @Test fun runtimeAssetsDefaultToEmptyWhenAbsent() {
        val m = ContentManifest.parse("""{"version":1,"components":{}}""")
        assertTrue(m.runtimeAssets().isEmpty())
    }

    @Test fun archiveDefaultsToZstdWhenCompressionAbsent() {
        val json =
            """
            {"version":1,"components":{"dxvk":{
              "assetPath":"d.tzst","sha256":"abc","version":"1","kind":"ARCHIVE"
            }}}
            """.trimIndent()
        val e = ContentManifest.parse(json).entry(ContentComponent.DXVK)!!
        assertEquals(ManifestEntry.Compression.ZSTD, e.compression)
    }

    @Test fun unknownComponentKeyIsSkippedNotFatal() {
        // The manifest is fetched at runtime from a repo that evolves on its own
        // schedule. A component this build has never heard of must not take the
        // whole parse down, or adding one would brick every installed version.
        val m =
            ContentManifest.parse(
                """
                {"version":1,"components":{
                  "someFutureThing":{"kind":"ARCHIVE","assetPath":"x","version":"1","sha256":"${"d".repeat(64)}"},
                  "box64":{"kind":"WCP","assetPath":"b.wcp","version":"1","sha256":"${"e".repeat(64)}"}
                }}
                """.trimIndent(),
            )
        assertEquals(1, m.all().size)
        assertNotNull("known components must still parse", m.entry(ContentComponent.BOX64))
    }

    @Test fun missingComponentReturnsNull() {
        assertNull(ContentManifest.parse("""{"version":1,"components":{}}""").entry(ContentComponent.WINE))
    }

    private companion object {
        val SAMPLE =
            """
            {
              "version": 1,
              "wcpCatalogUrl": "https://catalog.example/default.json",
              "components": {
                "wine": {
                  "assetPath": "Proton-10.0-4-x86_64.wcp",
                  "sha256": null,
                  "version": "Proton-10.0-4-x86_64-0",
                  "kind": "WCP",
                  "contentType": "Proton",
                  "verName": "10.0-4-x86_64",
                  "verCode": 0
                },
                "dxvk": {
                  "assetPath": "dxvk.tzst",
                  "sha256": "${"b".repeat(64)}",
                  "version": "1",
                  "kind": "ARCHIVE",
                  "compression": "zstd"
                },
                "rootfs": {
                  "assetPath": "imagefs.txz",
                  "sha256": "${"a".repeat(64)}",
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
