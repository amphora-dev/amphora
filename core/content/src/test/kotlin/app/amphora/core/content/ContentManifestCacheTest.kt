package app.amphora.core.content

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContentManifestCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
