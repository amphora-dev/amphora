package app.amphora.core.content

import app.amphora.core.content.model.ContentComponent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteUrlResolverTest {
    @Test
    fun prefersPinnedRemoteUrlWithoutCatalogFetch() {
        var fetches = 0
        val resolver = RemoteUrlResolver {
            fetches++
            error("catalog should not be fetched")
        }
        val manifest =
            catalogManifest(
                catalogUrl = CATALOG_A,
                assetPath = "Proton-10.0-4-x86_64.wcp",
                directUrl = "https://cdn.example/Proton-10.0-4-x86_64.wcp",
            )
        val resolved = resolver.resolve(manifest.entry(ContentComponent.WINE)!!, manifest.wcpCatalogUrl)
        assertEquals("https://cdn.example/Proton-10.0-4-x86_64.wcp", resolved)
        assertEquals(0, fetches)
    }

    @Test
    fun resolvesWcpFilenameFromStableCatalog() {
        val resolver =
            RemoteUrlResolver {
                """
            [
              {"remoteUrl":"https://cdn.example/releases/Proton-10.0-4-x86_64.wcp"},
              {"remoteUrl":"https://cdn.example/releases/other.wcp"}
            ]
                """.trimIndent()
            }
        val manifest = catalogManifest(CATALOG_A, "Proton-10.0-4-x86_64.wcp")
        val resolved = resolver.resolve(manifest.entry(ContentComponent.WINE)!!, manifest.wcpCatalogUrl)
        assertEquals(
            "https://cdn.example/releases/Proton-10.0-4-x86_64.wcp",
            resolved,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun missingCatalogEntryFails() {
        val resolver =
            RemoteUrlResolver { """[{"remoteUrl":"https://cdn.example/releases/other.wcp"}]""" }
        val manifest = catalogManifest(CATALOG_A, "Proton-10.0-4-x86_64.wcp")
        resolver.resolve(manifest.entry(ContentComponent.WINE)!!, manifest.wcpCatalogUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpsCatalogUrls() {
        val resolver =
            RemoteUrlResolver {
                """[{"remoteUrl":"http://insecure.example/releases/Proton-10.0-4-x86_64.wcp"}]"""
            }
        val manifest = catalogManifest(CATALOG_A, "Proton-10.0-4-x86_64.wcp")
        resolver.resolve(manifest.entry(ContentComponent.WINE)!!, manifest.wcpCatalogUrl)
    }

    @Test
    fun catalogCacheIsInvalidatedWhenSourceUrlChanges() {
        val fetches = mutableListOf<String>()
        val resolver =
            RemoteUrlResolver { url ->
                fetches += url
                val host = if (url == CATALOG_A) "one.example" else "two.example"
                """[{"remoteUrl":"https://$host/releases/runtime.wcp"}]"""
            }

        val fromA = catalogManifest(CATALOG_A, "runtime.wcp")
        val fromB = catalogManifest(CATALOG_B, "runtime.wcp")
        assertEquals(
            "https://one.example/releases/runtime.wcp",
            resolver.resolve(fromA.entry(ContentComponent.WINE)!!, fromA.wcpCatalogUrl),
        )
        assertEquals(
            "https://two.example/releases/runtime.wcp",
            resolver.resolve(fromB.entry(ContentComponent.WINE)!!, fromB.wcpCatalogUrl),
        )
        assertEquals(
            "https://one.example/releases/runtime.wcp",
            resolver.resolve(fromA.entry(ContentComponent.WINE)!!, fromA.wcpCatalogUrl),
        )
        assertEquals(listOf(CATALOG_A, CATALOG_B, CATALOG_A), fetches)
    }

    @Test
    fun concurrentCatalogResolutionFetchesOnce() {
        val fetches = AtomicInteger()
        val fetchStarted = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val resolver =
            RemoteUrlResolver {
                fetches.incrementAndGet()
                fetchStarted.countDown()
                check(releaseFetch.await(5, TimeUnit.SECONDS))
                """[{"remoteUrl":"https://cdn.example/releases/runtime.wcp"}]"""
            }
        val manifest = catalogManifest(CATALOG_A, "runtime.wcp")
        val entry = manifest.entry(ContentComponent.WINE)!!
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results =
                List(8) {
                    executor.submit<String> {
                        resolver.resolve(entry, manifest.wcpCatalogUrl)
                    }
                }
            assertTrue(fetchStarted.await(5, TimeUnit.SECONDS))
            releaseFetch.countDown()

            assertEquals(
                List(8) { "https://cdn.example/releases/runtime.wcp" },
                results.map { it.get(5, TimeUnit.SECONDS) },
            )
            assertEquals(1, fetches.get())
        } finally {
            releaseFetch.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun duplicateFilenameFailureDoesNotPoisonLaterCatalog() {
        val fetches = mutableListOf<String>()
        val resolver =
            RemoteUrlResolver { url ->
                fetches += url
                if (url == CATALOG_A) {
                    """
                    [
                      {"remoteUrl":"https://one.example/releases/runtime.wcp"},
                      {"remoteUrl":"https://two.example/releases/runtime.wcp"}
                    ]
                    """.trimIndent()
                } else {
                    """[{"remoteUrl":"https://valid.example/releases/runtime.wcp"}]"""
                }
            }
        val invalid = catalogManifest(CATALOG_A, "runtime.wcp")
        val valid = catalogManifest(CATALOG_B, "runtime.wcp")

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                resolver.resolve(invalid.entry(ContentComponent.WINE)!!, invalid.wcpCatalogUrl)
            }
        assertTrue(failure.message.orEmpty().contains("duplicate filename"))

        assertEquals(
            "https://valid.example/releases/runtime.wcp",
            resolver.resolve(valid.entry(ContentComponent.WINE)!!, valid.wcpCatalogUrl),
        )
        assertEquals(listOf(CATALOG_A, CATALOG_B), fetches)
    }

    private fun catalogManifest(catalogUrl: String, assetPath: String, directUrl: String? = null): ContentManifest {
        val root = JSONObject(ContentManifestTest.SAMPLE)
        root.put("wcpCatalogUrl", catalogUrl)
        root.getJSONObject("components").getJSONObject("wine").apply {
            put("assetPath", assetPath)
            if (directUrl == null) remove("remoteUrl") else put("remoteUrl", directUrl)
        }
        return ContentManifest.parse(root.toString())
    }

    private companion object {
        const val CATALOG_A = "https://catalog-a.example/default.json"
        const val CATALOG_B = "https://catalog-b.example/default.json"
    }
}
