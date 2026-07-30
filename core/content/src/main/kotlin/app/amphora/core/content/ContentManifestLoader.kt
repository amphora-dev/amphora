package app.amphora.core.content

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Loads [ContentManifest] preferring a remote HTTPS copy (MiceWine-style
 * `index.json` pattern: publish pins separately from the APK), falling back to
 * the APK-bundled `assets/content_manifest.json`.
 *
 * Changing ROOTFS URL/SHA (or any other pin) then only requires updating the
 * remote JSON — no APK rebuild — as long as [ImageFsInstaller.LATEST_VERSION] /
 * rootfs `version` is bumped when the installed tree must be replaced.
 */
object ContentManifestLoader {
    private const val TAG = "ContentManifestLoader"

    /**
     * Default remote pin file. Overridable via
     * `meta-data app.amphora.CONTENT_MANIFEST_URL` in the app manifest, or the
     * system property `amphora.content_manifest_url` (tests / debug).
     */
    const val DEFAULT_REMOTE_URL =
        "https://raw.githubusercontent.com/amphora-dev/amphora/main/" +
            "core/content/src/main/assets/content_manifest.json"

    fun load(
        context: Context,
        remoteUrl: String? = resolveRemoteUrl(context),
    ): ContentManifest {
        val url = remoteUrl?.takeIf { it.isNotBlank() }
        if (url != null) {
            try {
                val json = fetchHttpsText(url)
                val remote = ContentManifest.parse(json)
                Log.i(TAG, "Loaded remote content manifest from $url (${remote.all().size} components)")
                return remote
            } catch (failure: Throwable) {
                Log.w(
                    TAG,
                    "Remote content manifest unavailable ($url); using APK fallback",
                    failure,
                )
            }
        }
        return ContentManifest.load(context)
    }

    fun resolveRemoteUrl(context: Context): String? {
        System.getProperty(SYSTEM_PROPERTY)?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            val ai = context.packageManager.getApplicationInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_META_DATA,
            )
            ai.metaData?.getString(META_DATA_KEY)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_REMOTE_URL
        } catch (_: Throwable) {
            DEFAULT_REMOTE_URL
        }
    }

    internal fun fetchHttpsText(remoteUrl: String): String {
        require(URI(remoteUrl).scheme.equals("https", ignoreCase = true)) {
            "Only HTTPS manifest URLs are allowed: $remoteUrl"
        }
        val connection = URI(remoteUrl).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
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

    private const val META_DATA_KEY = "app.amphora.CONTENT_MANIFEST_URL"
    private const val SYSTEM_PROPERTY = "amphora.content_manifest_url"
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 8_000
}
