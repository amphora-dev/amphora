package app.amphora.core.content

import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.ManifestEntry
import app.amphora.core.content.model.id
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit test for [ContentManifest.parse]. Exercises both provisioning kinds
 * (WCP + ARCHIVE), null-SHA skipping, optional-field defaults, and the
 * ComponentId mapping. [fixtureManifestSatisfiesProvisioningInvariants] parses a
 * full-shape fixture so a malformed manifest fails here, not on device.
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
     * Regression guard for gap #1: every manifest entry must carry a pinned
     * SHA-256, because a `null` digest silently skips download verification.
     *
     * Runs against [FIXTURE], a frozen sample of the manifest shape. It is
     * deliberately *not* kept in sync with `amphora-dev/content_manifest`, so
     * the assertions here are about invariants only — asserting a version or a
     * digest would turn every upstream pin bump into an edit in this file while
     * still telling us nothing about the manifest the app actually fetches.
     */
    @Test fun fixtureManifestSatisfiesProvisioningInvariants() {
        val manifest = ContentManifest.parse(FIXTURE)

        assertTrue("manifest defines no components", manifest.all().isNotEmpty())
        val unpinned = manifest.all().filter { it.sha256 == null }
        assertTrue(
            "un-pinned SHA-256 (gap #1 regression): ${unpinned.joinToString { it.component.id.value }}",
            unpinned.isEmpty(),
        )
        assertTrue(
            "component SHA-256 must be 64 lowercase hex chars",
            manifest.all().all { it.sha256!!.matches(SHA256) },
        )
        assertTrue(
            "every component needs a non-empty version for the install path",
            manifest.all().all { it.version.isNotBlank() },
        )

        val catalogUrl = manifest.wcpCatalogUrl
        assertNotNull("WCP catalog URL missing", catalogUrl)
        assertTrue("WCP catalog URL must be https", catalogUrl!!.startsWith("https://"))

        val runtimeAssets = manifest.runtimeAssets()
        assertTrue("kernel runtime assets missing", runtimeAssets.isNotEmpty())
        assertTrue(
            "runtime asset SHA-256 must be 64 lowercase hex chars",
            runtimeAssets.all { it.sha256.matches(SHA256) },
        )
        assertTrue(
            "runtime assets must be fetched over https",
            runtimeAssets.all { it.remoteUrl.startsWith("https://") },
        )
        assertEquals(
            "duplicate runtime asset paths would race in RuntimeAssetProvisioner",
            runtimeAssets.size,
            runtimeAssets.map { it.assetPath }.toSet().size,
        )
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")

        /** Frozen full-shape manifest; see the `${'$'}comment` in the resource. */
        val FIXTURE: String by lazy {
            ContentManifestTest::class.java.classLoader!!
                .getResourceAsStream("content_manifest_fixture.json")!!
                .bufferedReader()
                .readText()
        }

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
