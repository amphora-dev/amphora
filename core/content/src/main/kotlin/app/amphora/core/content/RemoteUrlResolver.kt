package app.amphora.core.content

import app.amphora.core.content.model.ManifestEntry
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Resolves pinned WCP filenames through the upstream stable `default.json`. */
class RemoteUrlResolver(
    private val manifest: ContentManifest,
) {
    @Volatile
    private var catalog: Map<String, String>? = null

    fun resolve(entry: ManifestEntry): String {
        entry.remoteUrl?.let { return it }
        require(entry.kind == ManifestEntry.Kind.WCP) {
            "A remoteUrl is required for non-WCP asset ${entry.assetPath}"
        }
        return catalogUrls()[entry.assetPath]
            ?: error(
                "${entry.assetPath} is not present in the stable WCP catalog " +
                    "(${manifest.wcpCatalogUrl})"
            )
    }

    private fun catalogUrls(): Map<String, String> {
        catalog?.let { return it }
        return synchronized(this) {
            catalog?.let { return@synchronized it }
            val url = manifest.wcpCatalogUrl
                ?: error("content manifest does not define wcpCatalogUrl")
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            try {
                if (connection.responseCode !in 200..299) {
                    throw IOException("HTTP ${connection.responseCode} ${connection.responseMessage}")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val array = JSONArray(body)
                buildMap {
                    for (index in 0 until array.length()) {
                        val remoteUrl = array.getJSONObject(index).optString("remoteUrl")
                        if (remoteUrl.isBlank()) continue
                        val uri = URI(remoteUrl)
                        require(uri.scheme.equals("https", ignoreCase = true)) {
                            "WCP catalog contains non-HTTPS URL: $remoteUrl"
                        }
                        put(uri.path.substringAfterLast('/'), remoteUrl)
                    }
                }.also { catalog = it }
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
