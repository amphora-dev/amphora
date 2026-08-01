package app.amphora.core.content

import org.junit.Assert.assertTrue
import org.junit.Test

class ContentManifestLoaderTest {
    @Test
    fun defaultRemoteUrlIsHttpsGithubRaw() {
        assertTrue(ContentManifestLoader.DEFAULT_REMOTE_URL.startsWith("https://"))
        assertTrue(
            ContentManifestLoader.DEFAULT_REMOTE_URL.contains(
                "amphora-dev/content_manifest"
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

}
