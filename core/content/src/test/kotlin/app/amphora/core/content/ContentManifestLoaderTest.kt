package app.amphora.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentManifestLoaderTest {
    @Test
    fun defaultRemoteUrlTracksMainBranchNotACommit() {
        val url = ContentManifestLoader.DEFAULT_REMOTE_URL
        assertTrue(url.startsWith("https://"))
        assertTrue(url.contains("amphora-dev/content_manifest"))
        assertTrue(url.endsWith("content_manifest.json") || url.contains("content_manifest.json?"))
        // Must follow main tip — never pin a 40-char commit SHA in the default URL.
        assertTrue(
            "default manifest URL must track main, got $url",
            url.contains("@main/") || url.contains("/main/") || url.contains("ref=main"),
        )
        assertFalse(
            "default manifest URL must not embed a commit SHA: $url",
            Regex("""/[0-9a-f]{40}/""").containsMatchIn(url) ||
                Regex("""@[0-9a-f]{40}/""").containsMatchIn(url),
        )
    }

    @Test
    fun candidateUrlsPreferPrimaryThenBranchTrackingMirrors() {
        val primary =
            "https://cdn.jsdelivr.net/gh/amphora-dev/content_manifest@main/content_manifest.json"
        val candidates = ContentManifestLoader.candidateUrls(primary)
        assertEquals(primary, candidates.first())
        assertTrue(candidates.any { it.contains("api.github.com") && it.contains("ref=main") })
        assertTrue(candidates.any { it.contains("raw.githubusercontent.com") && it.contains("/main/") })
        // No commit-SHA mirrors.
        assertTrue(candidates.none { Regex("""@[0-9a-f]{40}/""").containsMatchIn(it) })
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
