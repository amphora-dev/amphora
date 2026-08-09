package app.amphora.core.content

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Loads [ContentManifest] exclusively from a remote HTTPS URL (MiceWine-style
 * remote index). There is **no** APK-bundled fallback — pins live in
 * `amphora-dev/content_manifest` and are refreshed at runtime so imagefs / WCP
 * SHA bumps do not require an APK rebuild.
 */
object ContentManifestLoader {
    private const val TAG = "ContentManifestLoader"

    /**
     * Default remote pin file, from `amphora.contentManifest.url` in
     * `gradle.properties` — the same value `:app:stageBundledContent` fetches, so
     * build-time staging and runtime provisioning cannot target different manifests.
     *
     * Overridable at runtime via `meta-data app.amphora.CONTENT_MANIFEST_URL` in the
     * app manifest, or the system property `amphora.content_manifest_url`
     * (tests / debug).
     */
    const val DEFAULT_REMOTE_URL: String = BuildConfig.CONTENT_MANIFEST_URL

    /** Main-tracking GitHub sources; Contents API first, raw media fallback. */
    val BRANCH_TRACKING_MIRRORS: List<String> =
        listOf(
            "https://api.github.com/repos/amphora-dev/content_manifest/contents/content_manifest.json?ref=main",
            "https://raw.githubusercontent.com/amphora-dev/content_manifest/main/content_manifest.json",
        )
    private val APP_UPDATE_MIRRORS: List<String> =
        listOf(
            "https://api.github.com/repos/amphora-dev/content_manifest/contents/app_update.json?ref=main",
            "https://raw.githubusercontent.com/amphora-dev/content_manifest/main/app_update.json",
        )

    /**
     * Fetch and parse the remote manifest. Throws when the URL is missing or
     * the request / JSON parse fails — callers must surface the error in UI.
     */
    fun load(context: Context, remoteUrl: String? = resolveRemoteUrl(context)): ContentManifest {
        val url =
            remoteUrl?.takeIf { it.isNotBlank() }
                ?: error("content manifest remote URL is not configured")
        val json = fetchHttpsText(url)
        val remote = ContentManifest.parse(json)
        Log.i(TAG, "Loaded remote content manifest from $url (${remote.all().size} components)")
        return remote
    }

    fun resolveRemoteUrl(context: Context): String? {
        System.getProperty(SYSTEM_PROPERTY)?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            val ai =
                context.packageManager.getApplicationInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_META_DATA,
                )
            ai.metaData?.getString(META_DATA_KEY)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_REMOTE_URL
        } catch (_: Throwable) {
            DEFAULT_REMOTE_URL
        }
    }

    /**
     * Fetch manifest text from [remoteUrl], falling back through [candidateUrls]
     * when the primary host fails or returns a non-2xx. Candidates always track
     * the `main` branch tip — never a frozen commit.
     */
    fun fetchHttpsText(remoteUrl: String): String {
        require(URI(remoteUrl).scheme.equals("https", ignoreCase = true)) {
            "Only HTTPS manifest URLs are allowed: $remoteUrl"
        }
        val candidates = candidateUrls(remoteUrl)
        var lastFailure: Throwable? = null
        for (candidate in candidates) {
            try {
                return fetchOnce(candidate)
            } catch (failure: Throwable) {
                lastFailure = failure
                Log.w(TAG, "Manifest fetch failed for $candidate: ${failure.message}")
            }
        }
        throw IOException(
            "Unable to fetch content manifest after ${candidates.size} sources",
            lastFailure,
        )
    }

    /** Primary URL first, then branch-tracking mirrors not already tried. */
    fun candidateUrls(remoteUrl: String): List<String> {
        val seen = linkedSetOf<String>()
        seen += remoteUrl
        val mirrors =
            if (URI(remoteUrl).path.endsWith("/app_update.json")) {
                APP_UPDATE_MIRRORS
            } else {
                BRANCH_TRACKING_MIRRORS
            }
        for (mirror in mirrors) {
            seen += mirror
        }
        return seen.toList()
    }

    private fun fetchOnce(remoteUrl: String): String {
        require(URI(remoteUrl).scheme.equals("https", ignoreCase = true)) {
            "Only HTTPS manifest URLs are allowed: $remoteUrl"
        }
        // Bust CDN/proxy caches (GitHub raw uses max-age≈300). Pin flips must be
        // visible on the next cold start, otherwise ensureRealDxwrapper keeps
        // migrating containers back to a stale DXVK/VKD3D token.
        // Note: raw.githubusercontent.com often keys cache on path only and ignores
        // the query — mirrors above cover that; bust still helps other CDNs.
        val separator = if (remoteUrl.contains('?')) '&' else '?'
        val bustUrl = "$remoteUrl${separator}amphora_cb=${System.currentTimeMillis()}"
        val connection = URI(bustUrl).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        connection.defaultUseCaches = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", acceptHeaderFor(remoteUrl))
        connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
        connection.setRequestProperty("Pragma", "no-cache")
        if (isGithubContentsApi(remoteUrl)) {
            // GitHub Contents API requires a UA; raw media type returns file bytes.
            connection.setRequestProperty("User-Agent", "Amphora-ContentManifestLoader")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code ${connection.responseMessage} for $remoteUrl")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun acceptHeaderFor(remoteUrl: String): String = if (isGithubContentsApi(remoteUrl)) {
        "application/vnd.github.raw"
    } else {
        "application/json, text/plain, */*"
    }

    private fun isGithubContentsApi(remoteUrl: String): Boolean =
        remoteUrl.startsWith("https://api.github.com/repos/") &&
            remoteUrl.contains("/contents/")

    private const val META_DATA_KEY = "app.amphora.CONTENT_MANIFEST_URL"
    private const val SYSTEM_PROPERTY = "amphora.content_manifest_url"
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 8_000
}
