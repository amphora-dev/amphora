package app.amphora.core.content

import app.amphora.core.content.model.ContentComponent
import org.junit.Assert.assertEquals
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

    @Test
    fun parsesEveryComponentTheEngineResolves() {
        val json = javaClass.classLoader!!
            .getResourceAsStream("content_manifest_fixture.json")!!
            .bufferedReader()
            .readText()
        val manifest = ContentManifest.parse(json)
        // Anything the engine asks for by name must survive a round-trip through
        // the parser; a missing key here surfaces on device as a launch failure.
        val required = listOf(
            ContentComponent.WINE,
            ContentComponent.BOX64,
            ContentComponent.TURNIP,
            ContentComponent.DXVK,
            ContentComponent.VKD3D,
            ContentComponent.ROOTFS,
        )
        for (component in required) {
            assertTrue("missing $component", manifest.entry(component) != null)
        }
        assertEquals(required.size, manifest.all().size)
    }
}
