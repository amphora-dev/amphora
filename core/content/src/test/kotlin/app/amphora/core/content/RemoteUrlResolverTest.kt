package app.amphora.core.content

import app.amphora.core.content.model.ContentComponent
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class RemoteUrlResolverTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private var catalogBody: String = "[]"

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/default.json") { exchange ->
            val bytes = catalogBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun prefersPinnedRemoteUrlWithoutCatalogFetch() {
        // Any catalog fetch would 404 after the server stops; pinned remoteUrl
        // must short-circuit before catalogUrls() runs.
        server.stop(0)
        val manifest = ContentManifest.parse(
            """
            {
              "version": 1,
              "wcpCatalogUrl": "$baseUrl/default.json",
              "components": {
                "wine": {
                  "assetPath": "Proton-10.0-4-x86_64.wcp",
                  "sha256": "${"a".repeat(64)}",
                  "version": "Proton-10.0-4-x86_64-0",
                  "kind": "WCP",
                  "contentType": "Proton",
                  "verName": "10.0-4-x86_64",
                  "verCode": 0,
                  "remoteUrl": "https://cdn.example/Proton-10.0-4-x86_64.wcp"
                }
              }
            }
            """.trimIndent(),
        )
        val resolved = RemoteUrlResolver(manifest).resolve(manifest.entry(ContentComponent.WINE)!!)
        assertEquals("https://cdn.example/Proton-10.0-4-x86_64.wcp", resolved)
    }

    @Test
    fun resolvesWcpFilenameFromStableCatalog() {
        catalogBody = """
            [
              {"remoteUrl":"https://cdn.example/releases/Proton-10.0-4-x86_64.wcp"},
              {"remoteUrl":"https://cdn.example/releases/other.wcp"}
            ]
        """.trimIndent()
        val manifest = catalogManifest(assetPath = "Proton-10.0-4-x86_64.wcp")
        val resolved = RemoteUrlResolver(manifest).resolve(manifest.entry(ContentComponent.WINE)!!)
        assertEquals(
            "https://cdn.example/releases/Proton-10.0-4-x86_64.wcp",
            resolved,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun missingCatalogEntryFails() {
        catalogBody = """[{"remoteUrl":"https://cdn.example/releases/other.wcp"}]"""
        val manifest = catalogManifest(assetPath = "Proton-10.0-4-x86_64.wcp")
        RemoteUrlResolver(manifest).resolve(manifest.entry(ContentComponent.WINE)!!)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpsCatalogUrls() {
        catalogBody = """[{"remoteUrl":"http://insecure.example/releases/Proton-10.0-4-x86_64.wcp"}]"""
        val manifest = catalogManifest(assetPath = "Proton-10.0-4-x86_64.wcp")
        RemoteUrlResolver(manifest).resolve(manifest.entry(ContentComponent.WINE)!!)
    }

    @Test(expected = IllegalArgumentException::class)
    fun archiveWithoutRemoteUrlFails() {
        val manifest = ContentManifest.parse(
            """
            {
              "version": 1,
              "wcpCatalogUrl": "$baseUrl/default.json",
              "components": {
                "turnip": {
                  "assetPath": "graphics_driver/wrapper.tzst",
                  "sha256": "${"b".repeat(64)}",
                  "version": "1",
                  "kind": "ARCHIVE"
                }
              }
            }
            """.trimIndent(),
        )
        RemoteUrlResolver(manifest).resolve(manifest.entry(ContentComponent.TURNIP)!!)
    }

    private fun catalogManifest(assetPath: String): ContentManifest =
        ContentManifest.parse(
            """
            {
              "version": 1,
              "wcpCatalogUrl": "$baseUrl/default.json",
              "components": {
                "wine": {
                  "assetPath": "$assetPath",
                  "sha256": "${"a".repeat(64)}",
                  "version": "Proton-10.0-4-x86_64-0",
                  "kind": "WCP",
                  "contentType": "Proton",
                  "verName": "10.0-4-x86_64",
                  "verCode": 0
                }
              }
            }
            """.trimIndent(),
        )
}
