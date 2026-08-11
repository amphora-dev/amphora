package app.amphora.core.content

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContentManifestCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun missingCacheReadsAsEmpty() {
        val cache = ContentManifestCache(File(temporaryFolder.root, "missing/content_manifest.json"))

        assertNull(cache.read())
    }

    @Test
    fun malformedCacheReadsAsEmptyWithoutDeletingEvidence() {
        val cacheFile = File(temporaryFolder.newFolder("malformed"), "content_manifest.json")
        cacheFile.writeText("{not valid json")

        assertNull(ContentManifestCache(cacheFile).read())
        assertEquals("{not valid json", cacheFile.readText())
    }

    @Test
    fun replaceCreatesParentAndRoundTripsValidatedManifest() {
        val cacheFile = File(temporaryFolder.root, "nested/cache/content_manifest.json")
        val cache = ContentManifestCache(cacheFile)

        val replaced = cache.replace(ContentManifestTest.SAMPLE)

        assertTrue(cacheFile.isFile)
        assertEquals(replaced.all().size, cache.read()?.all()?.size)
        assertEquals(ContentManifestTest.SAMPLE, cacheFile.readText())
    }

    @Test
    fun invalidReplacementPreservesLastKnownGoodManifest() {
        val cacheFile = File(temporaryFolder.newFolder("content"), "content_manifest.json")
        val cache = ContentManifestCache(cacheFile)
        cache.replace(ContentManifestTest.SAMPLE)
        val lastKnownGood = cacheFile.readText()

        val invalid = JSONObject(ContentManifestTest.SAMPLE)
        invalid.getJSONObject("components").getJSONObject("rootfs").put("sha256", "broken")
        try {
            cache.replace(invalid.toString())
            throw AssertionError("invalid manifest unexpectedly replaced cache")
        } catch (_: RuntimeException) {
            // Expected.
        }

        assertEquals(lastKnownGood, cacheFile.readText())
        assertNotNull(cache.read())
    }
}
