package app.amphora.core.content

import app.amphora.core.content.model.ManifestEntry
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONArray

/** Resolves pinned WCP filenames through the upstream stable `default.json`. */
class RemoteUrlResolver internal constructor(private val fetchCatalog: (String) -> String) {
    constructor() : this(::fetchCatalogOverHttps)

    private data class CachedCatalog(val sourceUrl: String, val urls: Map<String, String>)

    @Volatile
    private var catalog: CachedCatalog? = null

    fun resolve(entry: ManifestEntry, wcpCatalogUrl: String?): String {
        entry.remoteUrl?.let { return it }
        require(entry.kind == ManifestEntry.Kind.WCP) {
            "A remoteUrl is required for non-WCP asset ${entry.assetPath}"
        }
        return catalogUrls(wcpCatalogUrl)[entry.assetPath]
            ?: error(
                "${entry.assetPath} is not present in the stable WCP catalog " +
                    "($wcpCatalogUrl)",
            )
    }

    private fun catalogUrls(wcpCatalogUrl: String?): Map<String, String> {
        val url =
            wcpCatalogUrl
                ?: error("content manifest does not define wcpCatalogUrl")
        catalog?.takeIf { it.sourceUrl == url }?.let { return it.urls }
        return synchronized(this) {
            catalog?.takeIf { it.sourceUrl == url }?.let { return@synchronized it.urls }
            val uri = URI(url)
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
                "WCP catalog URL must use HTTPS: $url"
            }
            val array = JSONArray(fetchCatalog(url))
            buildMap {
                for (index in 0 until array.length()) {
                    val remoteUrl = array.getJSONObject(index).optString("remoteUrl")
                    if (remoteUrl.isBlank()) continue
                    val remoteUri = URI(remoteUrl)
                    require(
                        remoteUri.scheme.equals("https", ignoreCase = true) &&
                            !remoteUri.host.isNullOrBlank(),
                    ) {
                        "WCP catalog contains non-HTTPS URL: $remoteUrl"
                    }
                    val filename = remoteUri.path.substringAfterLast('/')
                    require(filename.isNotBlank()) { "WCP catalog URL has no filename: $remoteUrl" }
                    require(put(filename, remoteUrl) == null) {
                        "WCP catalog contains duplicate filename: $filename"
                    }
                }
            }.also { catalog = CachedCatalog(url, it) }
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000

        private fun fetchCatalogOverHttps(url: String): String {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            try {
                if (connection.responseCode !in 200..299) {
                    throw IOException("HTTP ${connection.responseCode} ${connection.responseMessage}")
                }
                return connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }
}
