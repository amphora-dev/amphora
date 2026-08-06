package app.amphora.core.content.update

import android.content.Context
import android.util.Log
import app.amphora.core.content.BuildConfig
import app.amphora.core.content.ContentManifestLoader

/**
 * Loads [AppUpdateManifest] from a remote HTTPS URL (same transport as
 * [ContentManifestLoader]).
 *
 * Default URL comes from `amphora.appUpdate.url` in `gradle.properties` via
 * BuildConfig. Overridable with meta-data `app.amphora.APP_UPDATE_URL` or the
 * system property `amphora.app_update_url`.
 */
object AppUpdateLoader {
    private const val TAG = "AppUpdateLoader"

    const val DEFAULT_REMOTE_URL: String = BuildConfig.APP_UPDATE_URL

    fun load(context: Context, remoteUrl: String? = resolveRemoteUrl(context)): AppUpdateManifest {
        val url =
            remoteUrl?.takeIf { it.isNotBlank() }
                ?: error("app update remote URL is not configured")
        val json = ContentManifestLoader.fetchHttpsText(url)
        val manifest = AppUpdateManifest.parse(json)
        Log.i(
            TAG,
            "Loaded app update manifest from $url " +
                "(${manifest.channel} ${manifest.versionName} code=${manifest.versionCode})",
        )
        return manifest
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
                ?: DEFAULT_REMOTE_URL.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            DEFAULT_REMOTE_URL.takeIf { it.isNotBlank() }
        }
    }

    private const val META_DATA_KEY = "app.amphora.APP_UPDATE_URL"
    private const val SYSTEM_PROPERTY = "amphora.app_update_url"
}
