package app.amphora.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentManifestLoaderTest {
    @Test
    fun defaultRemoteUrlTracksLatestPinsNotACommit() {
        val url = ContentManifestLoader.DEFAULT_REMOTE_URL
        assertTrue(url.startsWith("https://"))
        assertTrue(url.contains("amphora-dev/content_manifest"))
        assertTrue(url.endsWith("content_manifest.json") || url.contains("content_manifest.json?"))
        // Prefer jsDelivr @latest (semver tags CI purges). Never embed a commit SHA.
        assertTrue(
            "default manifest URL must track latest/main, got $url",
            url.contains("@latest/") ||
                url.contains("@main/") ||
                url.contains("/main/") ||
                url.contains("ref=main"),
        )
        assertFalse(
            "default manifest URL must not embed a commit SHA: $url",
            Regex("""/[0-9a-f]{40}/""").containsMatchIn(url) ||
                Regex("""@[0-9a-f]{40}/""").containsMatchIn(url),
        )
    }

    @Test
    fun candidateUrlsPreferPrimaryThenFreshnessAwareMirrors() {
        val primary =
            "https://cdn.jsdelivr.net/gh/amphora-dev/content_manifest@latest/content_manifest.json"
        val candidates = ContentManifestLoader.candidateUrls(primary)
        assertEquals(primary, candidates.first())
        assertTrue(candidates.any { it.contains("raw.githubusercontent.com") && it.contains("/main/") })
        assertTrue(candidates.any { it.contains("api.github.com") && it.contains("ref=main") })
        // API is last (rate-limited).
        assertTrue(candidates.last().contains("api.github.com"))
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
