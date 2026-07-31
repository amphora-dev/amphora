package app.amphora.core.content

import app.amphora.core.content.model.ComponentId
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.ManifestEntry
import app.amphora.core.content.model.RuntimeAssetEntry
import app.amphora.core.content.model.id
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
            val components = root.getJSONObject("components")
            val entries = LinkedHashMap<ComponentId, ManifestEntry>()
            val keys = components.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val component = ContentComponent.valueOf(key.uppercase())
                entries[component.id] = parseEntry(component, components.getJSONObject(key))
            }
            val runtimeAssets = buildList {
                val array = root.optJSONArray("runtimeAssets") ?: return@buildList
                for (index in 0 until array.length()) {
                    val obj = array.getJSONObject(index)
                    add(
                        RuntimeAssetEntry(
                            assetPath = obj.getString("assetPath"),
                            sha256 = obj.getString("sha256"),
                            remoteUrl = obj.getString("remoteUrl"),
                            size = optLongOrNull(obj, "size"),
                        )
                    )
                }
            }
            return ContentManifest(
                entries = entries,
                wcpCatalogUrl = optString(root, "wcpCatalogUrl"),
                runtimeAssetEntries = runtimeAssets,
            )
        }

        private fun parseEntry(component: ContentComponent, obj: JSONObject): ManifestEntry {
            val kind = ManifestEntry.Kind.valueOf(obj.getString("kind"))
            val compression =
                if (obj.has("compression") && !obj.isNull("compression"))
                    ManifestEntry.Compression.valueOf(obj.getString("compression").uppercase())
                else ManifestEntry.Compression.ZSTD
            return ManifestEntry(
                component = component,
                assetPath = obj.getString("assetPath"),
                sha256 = if (obj.isNull("sha256")) null else obj.getString("sha256"),
                version = obj.getString("version"),
                kind = kind,
                compression = compression,
                contentType = optString(obj, "contentType"),
                verName = optString(obj, "verName"),
                verCode = optIntOrNull(obj, "verCode"),
                remoteUrl = optString(obj, "remoteUrl"),
                size = optLongOrNull(obj, "size"),
            )
        }

        private fun optString(obj: JSONObject, key: String): String? =
            if (obj.has(key) && !obj.isNull(key)) obj.getString(key) else null

        private fun optIntOrNull(obj: JSONObject, key: String): Int? =
            if (obj.has(key) && !obj.isNull(key)) obj.getInt(key) else null

        private fun optLongOrNull(obj: JSONObject, key: String): Long? =
            if (obj.has(key) && !obj.isNull(key)) obj.getLong(key) else null
    }
}
