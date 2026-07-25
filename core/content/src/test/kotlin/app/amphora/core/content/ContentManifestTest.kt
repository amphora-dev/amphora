package app.amphora.core.content

import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.ManifestEntry
import app.amphora.core.content.model.id
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit test for [ContentManifest.parse]. Exercises both provisioning kinds
 * (WCP + ARCHIVE), null-SHA skipping, optional-field defaults, and the
 * ComponentId mapping. [realManifestHasAllShasPinned] parses the shipped
 * `content_manifest.json` so a malformed or un-pinned manifest fails here, not
 * on device.
 */
class ContentManifestTest {

    @Test fun parsesWcpEntryWithNullSha() {
        val m = ContentManifest.parse(SAMPLE)
        val e = m.entry(ContentComponent.WINE)
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
        val m = ContentManifest.parse(SAMPLE)
        val e = m.entry(ContentComponent.TURNIP)!!
        assertEquals(ManifestEntry.Kind.ARCHIVE, e.kind)
        assertEquals(ManifestEntry.Compression.ZSTD, e.compression)
        assertEquals(
            "2651fbe6372af36c7d269664416b4f62d959125122ad3b8f79a787788e510fd8",
            e.sha256,
        )
        assertNull(e.contentType)
        assertNull(e.verName)
    }

    @Test fun parsesAllSampleEntries() {
        val m = ContentManifest.parse(SAMPLE)
        val ids = m.all().map { it.component }.toSet()
        assertTrue("wine missing", ContentComponent.WINE in ids)
        assertTrue("turnip missing", ContentComponent.TURNIP in ids)
        // ROOTFS is owned by RootfsInstaller; deliberately absent from the manifest.
        assertNull("rootfs must not be in manifest", m.entry(ContentComponent.ROOTFS))
    }

    @Test fun archiveDefaultsToZstdWhenCompressionAbsent() {
        val json = """
            {"version":1,"components":{"dxvk":{
              "assetPath":"d.tzst","sha256":"abc","version":"1","kind":"ARCHIVE"
            }}}
        """.trimIndent()
        val e = ContentManifest.parse(json).entry(ContentComponent.DXVK)!!
        assertEquals(ManifestEntry.Compression.ZSTD, e.compression)
    }

    @Test fun unknownComponentReturnsNull() {
        val m = ContentManifest.parse(SAMPLE)
        assertNull(m.entry(ContentComponent.ROOTFS))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingComponentKeyThrowsViaValueOf() {
        // A key that isn't a ContentComponent name fails fast at parse time
        // (Enum.valueOf throws IllegalArgumentException for unknown names).
        ContentManifest.parse("""{"version":1,"components":{"bogus":{"kind":"ARCHIVE","assetPath":"x","version":"1"}}}""")
    }

    /**
     * Regression guard for gap #1: every shipped manifest entry must carry a
     * pinned SHA-256. A `null` digest silently skips runtime verification
     * ([BundledContentSource] logs a warning and trusts the asset), so this
     * fails fast if a component is ever un-pinned. Parses the real
     * `content_manifest.json` (not [SAMPLE]); Gradle runs unit tests with the
     * module dir as the working directory, so the source asset is reachable via
     * a relative path.
     */
    @Test fun realManifestHasAllShasPinned() {
        val manifest = ContentManifest.parse(
            File("src/main/assets/content_manifest.json").readText(),
        )
        assertEquals("shipped manifest must define 5 remote components", 5, manifest.all().size)
        val unpinned = manifest.all().filter { it.sha256 == null }
        assertTrue(
            "un-pinned SHA-256 (gap #1 regression): ${unpinned.joinToString { it.component.id.value }}",
            unpinned.isEmpty(),
        )
        assertTrue("stable WCP catalog URL missing", manifest.wcpCatalogUrl!!.endsWith("/default.json"))
        assertTrue("kernel runtime assets missing", manifest.runtimeAssets().isNotEmpty())
        assertTrue(
            "runtime asset SHA-256 missing",
            manifest.runtimeAssets().all { it.sha256.matches(Regex("[0-9a-f]{64}")) },
        )
    }

    private companion object {
        val SAMPLE = """
            {
              "version": 1,
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
                "turnip": {
                  "assetPath": "graphics_driver/wrapper.tzst",
                  "sha256": "2651fbe6372af36c7d269664416b4f62d959125122ad3b8f79a787788e510fd8",
                  "version": "1",
                  "kind": "ARCHIVE",
                  "compression": "zstd"
                }
              }
            }
        """.trimIndent()
    }
}
