package app.amphora.core.content

import android.content.Context
import app.amphora.core.content.model.ComponentId
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.ManifestEntry
import app.amphora.core.content.model.id
import org.json.JSONObject

/**
 * Parses `content_manifest.json` (shipped in `:core:content` `assets/`, derived
 * from `docs/04-ASSET-MANIFEST.md`) into [ManifestEntry]s keyed by [ComponentId].
 *
 * Pure parsing is split into [parse] (no `Context`) so it is JVM-unit-testable;
 * [load] is the Android entry point that reads the asset.
 */
class ContentManifest private constructor(
    private val entries: Map<ComponentId, ManifestEntry>,
) {
    fun entry(component: ComponentId): ManifestEntry? = entries[component]

    fun entry(component: ContentComponent): ManifestEntry? = entries[component.id]

    fun all(): Collection<ManifestEntry> = entries.values

    companion object {
        private const val ASSET_NAME = "content_manifest.json"

        /** Load and parse the manifest from the app/library merged assets. */
        fun load(context: Context): ContentManifest {
            val json = context.assets.open(ASSET_NAME).use { src ->
                src.readBytes().toString(Charsets.UTF_8)
            }
            return parse(json)
        }

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
            return ContentManifest(entries)
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
            )
        }

        private fun optString(obj: JSONObject, key: String): String? =
            if (obj.has(key) && !obj.isNull(key)) obj.getString(key) else null

        private fun optIntOrNull(obj: JSONObject, key: String): Int? =
            if (obj.has(key) && !obj.isNull(key)) obj.getInt(key) else null
    }
}
