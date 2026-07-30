package app.amphora.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentManifestLoaderTest {
    @Test
    fun defaultRemoteUrlIsHttpsGithubRaw() {
        assertTrue(ContentManifestLoader.DEFAULT_REMOTE_URL.startsWith("https://"))
        assertTrue(
            ContentManifestLoader.DEFAULT_REMOTE_URL.contains(
                "amphora-dev/amphora"
            )
        )
        assertTrue(
            ContentManifestLoader.DEFAULT_REMOTE_URL.endsWith(
                "content_manifest.json"
            )
        )
    }

    @Test
    fun fetchHttpsTextRejectsNonHttps() {
        try {
            ContentManifestLoader.fetchHttpsText("http://example.com/manifest.json")
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("HTTPS"))
        }
    }

    @Test
    fun parseShapeMatchesBundledManifest() {
        // Guard: remote schema must stay identical to the APK fallback.
        val bundled = java.io.File("src/main/assets/content_manifest.json").readText()
        val manifest = ContentManifest.parse(bundled)
        assertEquals(6, manifest.all().size)
        assertTrue(manifest.entry(app.amphora.core.content.model.ContentComponent.ROOTFS) != null)
    }
}
