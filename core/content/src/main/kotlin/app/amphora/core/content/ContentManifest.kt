package app.amphora.core.content

import app.amphora.core.content.model.ComponentId
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.ManifestEntry
import app.amphora.core.content.model.RuntimeAssetEntry
import app.amphora.core.content.model.id
import java.net.URI
import org.json.JSONObject

/**
 * Parses the remote `content_manifest.json` (hosted in
 * `amphora-dev/content_manifest`, not packaged into the APK) into
 * [ManifestEntry]s keyed by [ComponentId].
 *
 * Pure parsing is [parse] (no Android deps) so it is JVM-unit-testable.
 */
class ContentManifest private constructor(
    private val entries: Map<ComponentId, ManifestEntry>,
    val wcpCatalogUrl: String?,
    private val runtimeAssetEntries: List<RuntimeAssetEntry>,
) {
    fun entry(component: ComponentId): ManifestEntry? = entries[component]

    fun entry(component: ContentComponent): ManifestEntry? = entries[component.id]

    fun all(): Collection<ManifestEntry> = entries.values

    fun runtimeAssets(): List<RuntimeAssetEntry> = runtimeAssetEntries

    companion object {
        /** Parse a manifest JSON string. JVM-testable (no Android deps). */
        fun parse(json: String): ContentManifest {
            val root = JSONObject(json)
            requirePositiveInteger(root.get("version"), "manifest version")
            val wcpCatalogUrl =
                optString(root, "wcpCatalogUrl")?.also {
                    requireHttpsUrl(it, "wcpCatalogUrl")
                }
            val components = root.getJSONObject("components")
            val entries = LinkedHashMap<ComponentId, ManifestEntry>()
            val keys = components.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                // Skip components this build does not know about. The manifest is
                // fetched at runtime from a repo that moves independently of the
                // app, so throwing here would let a single new key brick every
                // installed version. Typos are caught upstream by
                // content_manifest's validate_manifest.py, which knows the set.
                val component =
                    ContentComponent.entries
                        .firstOrNull { it.name.equals(key, ignoreCase = true) }
                        ?: continue
                require(component.id !in entries) { "Duplicate component: $key" }
                entries[component.id] =
                    parseEntry(component, components.getJSONObject(key), wcpCatalogUrl)
            }
            val missing = ContentComponent.entries.filter { it.id !in entries }
            require(missing.isEmpty()) {
                "Manifest is missing startup component(s): ${missing.joinToString { it.name.lowercase() }}"
            }
            val assetPaths = linkedSetOf<String>()
            for (entry in entries.values) {
                require(assetPaths.add(entry.assetPath)) {
                    "Duplicate assetPath: ${entry.assetPath}"
                }
            }
            val runtimeAssets =
                buildList {
                    val array = root.optJSONArray("runtimeAssets") ?: return@buildList
                    for (index in 0 until array.length()) {
                        val obj = array.getJSONObject(index)
                        val assetPath =
                            obj.getString("assetPath").also {
                                requireSafeRelativePath(it, "runtimeAssets[$index].assetPath")
                                require(assetPaths.add(it)) { "Duplicate assetPath: $it" }
                            }
                        val sha256 =
                            obj.getString("sha256").also {
                                requireSha256(it, "runtimeAssets[$index].sha256")
                            }
                        val remoteUrl =
                            obj.getString("remoteUrl").also {
                                requireHttpsUrl(it, "runtimeAssets[$index].remoteUrl")
                            }
                        val size =
                            optLongOrNull(obj, "size")?.also {
                                require(it > 0L) { "runtimeAssets[$index].size must be positive" }
                            }
                        add(
                            RuntimeAssetEntry(
                                assetPath = assetPath,
                                sha256 = sha256,
                                remoteUrl = remoteUrl,
                                size = size,
                            ),
                        )
                    }
                }
            return ContentManifest(
                entries = entries,
                wcpCatalogUrl = wcpCatalogUrl,
                runtimeAssetEntries = runtimeAssets,
            )
        }

        private fun parseEntry(component: ContentComponent, obj: JSONObject, wcpCatalogUrl: String?): ManifestEntry {
            val kind = ManifestEntry.Kind.valueOf(obj.getString("kind").uppercase())
            val compression =
                if (obj.has("compression") && !obj.isNull("compression")) {
                    ManifestEntry.Compression.valueOf(obj.getString("compression").uppercase())
                } else {
                    ManifestEntry.Compression.ZSTD
                }
            require(
                (component == ContentComponent.ROOTFS && kind == ManifestEntry.Kind.ROOTFS) ||
                    (component != ContentComponent.ROOTFS && kind != ManifestEntry.Kind.ROOTFS),
            ) {
                "${component.name.lowercase()} has incompatible kind $kind"
            }
            val assetPath =
                obj.getString("assetPath").also {
                    requireSafeRelativePath(it, "${component.name.lowercase()}.assetPath")
                }
            val sha256 =
                obj.getString("sha256").also {
                    requireSha256(it, "${component.name.lowercase()}.sha256")
                }
            val version =
                obj.getString("version").also {
                    require(it.isNotBlank() && it == it.trim()) {
                        "${component.name.lowercase()}.version must be non-blank"
                    }
                    if (component == ContentComponent.ROOTFS) {
                        require(it.toIntOrNull()?.let { value -> value > 0 } == true) {
                            "rootfs.version must be a positive integer"
                        }
                    }
                }
            val remoteUrl =
                optString(obj, "remoteUrl")?.also {
                    requireHttpsUrl(it, "${component.name.lowercase()}.remoteUrl")
                }
            val size =
                optLongOrNull(obj, "size")?.also {
                    require(it > 0L) { "${component.name.lowercase()}.size must be positive" }
                }
            val contentType = optString(obj, "contentType")
            val verName = optString(obj, "verName")
            val verCode = optIntOrNull(obj, "verCode")
            if (kind == ManifestEntry.Kind.WCP) {
                require(!contentType.isNullOrBlank()) {
                    "${component.name.lowercase()}.contentType is required for WCP"
                }
                require(!verName.isNullOrBlank()) {
                    "${component.name.lowercase()}.verName is required for WCP"
                }
                require(verCode != null && verCode >= 0) {
                    "${component.name.lowercase()}.verCode must be non-negative for WCP"
                }
                require(remoteUrl != null || wcpCatalogUrl != null) {
                    "${component.name.lowercase()} requires remoteUrl or wcpCatalogUrl"
                }
            } else {
                require(remoteUrl != null) {
                    "${component.name.lowercase()}.remoteUrl is required for $kind"
                }
            }
            return ManifestEntry(
                component = component,
                assetPath = assetPath,
                sha256 = sha256,
                version = version,
                kind = kind,
                compression = compression,
                contentType = contentType,
                verName = verName,
                verCode = verCode,
                remoteUrl = remoteUrl,
                size = size,
            )
        }

        private fun requirePositiveInteger(value: Any, label: String) {
            require(value is Number) { "$label must be a positive integer" }
            val longValue = value.toLong()
            require(longValue > 0L && value.toDouble() == longValue.toDouble()) {
                "$label must be a positive integer"
            }
        }

        private fun requireSha256(value: String, label: String) {
            require(AssetDigest.HEX.matches(value.lowercase())) {
                "$label must be a 64-character SHA-256"
            }
        }

        private fun requireSafeRelativePath(value: String, label: String) {
            require(value.isNotBlank() && value == value.trim()) { "$label must be non-blank" }
            require(!value.startsWith("/") && !value.startsWith("\\") && '\\' !in value) {
                "$label must be a relative slash-separated path"
            }
            require('\u0000' !in value) { "$label contains a NUL byte" }
            val segments = value.split('/')
            require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) {
                "$label is not a safe relative path: $value"
            }
        }

        private fun requireHttpsUrl(value: String, label: String) {
            val uri = URI(value)
            require(
                uri.scheme.equals("https", ignoreCase = true) &&
                    !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null,
            ) {
                "$label must be an HTTPS URL without credentials"
            }
        }

        private fun optString(obj: JSONObject, key: String): String? =
            if (obj.has(key) && !obj.isNull(key)) obj.getString(key) else null

        private fun optIntOrNull(obj: JSONObject, key: String): Int? =
            if (obj.has(key) && !obj.isNull(key)) obj.getInt(key) else null

        private fun optLongOrNull(obj: JSONObject, key: String): Long? =
            if (obj.has(key) && !obj.isNull(key)) obj.getLong(key) else null
    }
}
