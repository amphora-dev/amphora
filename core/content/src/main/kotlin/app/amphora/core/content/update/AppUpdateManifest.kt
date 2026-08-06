package app.amphora.core.content.update

import org.json.JSONObject

/**
 * Remote pin for an Amphora APK update (nightly / stable channel).
 *
 * Same idea as `content_manifest.json`: the device fetches this file over HTTPS,
 * compares [versionCode] to the installed app, then downloads the APK at [apkUrl]
 * and verifies [sha256] before handing it to the system package installer.
 */
data class AppUpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long? = null,
    val channel: String = "ci",
    val notes: String? = null,
) {
    fun isNewerThan(installedVersionCode: Long): Boolean = versionCode.toLong() > installedVersionCode

    companion object {
        fun parse(json: String): AppUpdateManifest {
            val root = JSONObject(json)
            val versionCode = root.getInt("versionCode")
            require(versionCode > 0) { "versionCode must be positive" }
            val versionName = root.getString("versionName").trim()
            require(versionName.isNotEmpty()) { "versionName must be non-empty" }
            val apkUrl = root.getString("apkUrl").trim()
            require(apkUrl.startsWith("https://", ignoreCase = true)) {
                "apkUrl must be HTTPS: $apkUrl"
            }
            val sha256 = root.getString("sha256").trim().lowercase()
            require(sha256.matches(SHA256_HEX)) { "sha256 must be 64 hex chars" }
            val size =
                if (root.has("size") && !root.isNull("size")) {
                    root.getLong("size").also { require(it > 0L) { "size must be positive" } }
                } else {
                    null
                }
            val channel =
                root.optString("channel", "ci").trim().ifBlank { "ci" }
            val notes =
                if (root.has("notes") && !root.isNull("notes")) {
                    root.getString("notes").trim().takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            return AppUpdateManifest(
                versionCode = versionCode,
                versionName = versionName,
                apkUrl = apkUrl,
                sha256 = sha256,
                size = size,
                channel = channel,
                notes = notes,
            )
        }

        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}
