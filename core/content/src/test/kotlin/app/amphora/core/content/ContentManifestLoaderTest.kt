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
        // Track main through GitHub; never embed a commit SHA.
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
            "https://api.github.com/repos/amphora-dev/content_manifest/contents/content_manifest.json?ref=main"
        val candidates = ContentManifestLoader.candidateUrls(primary)
        assertEquals(primary, candidates.first())
        assertTrue(candidates.any { it.contains("raw.githubusercontent.com") && it.contains("/main/") })
        assertTrue(candidates.none { it.contains("jsdelivr") })
        assertTrue(candidates.none { Regex("""@[0-9a-f]{40}/""").containsMatchIn(it) })
    }

    @Test
    fun appUpdateFallbacksNeverReturnContentManifest() {
        val candidates =
            ContentManifestLoader.candidateUrls(
                "https://api.github.com/repos/amphora-dev/content_manifest/contents/app_update.json?ref=main",
            )
        assertTrue(candidates.all { it.contains("app_update.json") })
        assertTrue(candidates.none { it.contains("jsdelivr") })
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
