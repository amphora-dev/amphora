package app.amphora.core.content

import android.util.Log
import app.amphora.core.content.model.ComponentId
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.ManifestEntry
import app.amphora.core.content.model.RuntimeAssetEntry
import app.amphora.core.content.model.id
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import org.json.JSONObject

/**
 * Development-time catalog pin overlay (`filesDir/content/dev_pins.json`).
 *
 * Patches the remote [ContentManifest] in memory so Prepare / health / UI see
 * an effective pin without mutating the published release manifest. Not a
 * release channel — clear the overlay to return to remote pins.
 */
object DevPinOverlay {
    const val FILE_NAME = "dev_pins.json"
    private const val TAG = "DevPinOverlay"
    private const val SUPPORTED_VERSION = 1

    fun file(contentDir: File): File = File(contentDir, FILE_NAME)

    /** Empty pins when missing or malformed (warns; never throws). */
    fun read(contentDir: File): DevPins {
        val path = file(contentDir)
        if (!path.isFile) return DevPins.EMPTY
        return try {
            parse(path.readText())
        } catch (failure: Throwable) {
            Log.w(TAG, "Ignoring malformed $FILE_NAME: ${failure.message}")
            DevPins.EMPTY
        }
    }

    fun write(contentDir: File, pins: DevPins) {
        check(contentDir.mkdirs() || contentDir.isDirectory) {
            "Cannot create content directory: $contentDir"
        }
        val target = file(contentDir)
        val temporary = File.createTempFile("dev_pins.", ".tmp", contentDir)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(pins.toJson().toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            AtomicFilePublisher.replace(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    fun clear(contentDir: File) {
        file(contentDir).delete()
    }

    /**
     * Apply [pins] onto [manifest]. Unknown component / runtime keys are
     * ignored. Overlay cannot invent components absent from the remote catalog.
     */
    fun apply(manifest: ContentManifest, pins: DevPins): Pair<ContentManifest, DevPinApplication> {
        if (pins.isEmpty) {
            return manifest to DevPinApplication.EMPTY
        }
        val entries = LinkedHashMap<ComponentId, ManifestEntry>()
        for (entry in manifest.all()) {
            entries[entry.component.id] = entry
        }
        val overriddenComponents = linkedSetOf<ContentComponent>()
        for ((key, patch) in pins.components) {
            val component =
                ContentComponent.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
            if (component == null) {
                Log.w(TAG, "Ignoring unknown component pin: $key")
                continue
            }
            val existing = entries[component.id]
            if (existing == null) {
                Log.w(TAG, "Ignoring pin for component absent from catalog: $key")
                continue
            }
            entries[component.id] = existing.patchedBy(patch)
            overriddenComponents += component
        }

        val runtimeByPath =
            manifest.runtimeAssets().associateByTo(LinkedHashMap()) { it.assetPath }
        val overriddenRuntimeAssets = linkedSetOf<String>()
        for ((assetPath, patch) in pins.runtimeAssets) {
            val existing = runtimeByPath[assetPath]
            if (existing == null) {
                Log.w(TAG, "Ignoring pin for runtime asset absent from catalog: $assetPath")
                continue
            }
            runtimeByPath[assetPath] = existing.patchedBy(patch)
            overriddenRuntimeAssets += assetPath
        }

        val effective =
            ContentManifest.of(
                entries = entries,
                wcpCatalogUrl = manifest.wcpCatalogUrl,
                runtimeAssets = runtimeByPath.values.toList(),
            )
        return effective to
            DevPinApplication(
                overriddenComponents = overriddenComponents,
                overriddenRuntimeAssets = overriddenRuntimeAssets,
            )
    }

    internal fun parse(json: String): DevPins {
        val root = JSONObject(json)
        val version = root.optInt("version", SUPPORTED_VERSION)
        require(version == SUPPORTED_VERSION) { "unsupported dev_pins version: $version" }

        val components = linkedMapOf<String, ComponentPinPatch>()
        val componentsObj = root.optJSONObject("components")
        if (componentsObj != null) {
            val keys = componentsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                components[key] = parseComponentPatch(componentsObj.getJSONObject(key), key)
            }
        }

        val runtimeAssets = linkedMapOf<String, RuntimeAssetPinPatch>()
        val runtimeObj = root.optJSONObject("runtimeAssets")
        if (runtimeObj != null) {
            val keys = runtimeObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                runtimeAssets[key] = parseRuntimePatch(runtimeObj.getJSONObject(key), key)
            }
        }
        return DevPins(version = version, components = components, runtimeAssets = runtimeAssets)
    }

    private fun parseComponentPatch(obj: JSONObject, label: String): ComponentPinPatch {
        val sha256 =
            optString(obj, "sha256")?.also {
                require(AssetDigest.HEX.matches(it.lowercase())) {
                    "$label.sha256 must be a 64-character SHA-256"
                }
            }?.lowercase()
        val size =
            optLong(obj, "size")?.also {
                require(it > 0L) { "$label.size must be positive" }
            }
        val assetPath = optString(obj, "assetPath")
        val remoteUrl =
            optString(obj, "remoteUrl")?.also {
                requireHttpsUrl(it, "$label.remoteUrl")
            }
        val version = optString(obj, "version")
        val verName = optString(obj, "verName")
        val verCode = optInt(obj, "verCode")
        return ComponentPinPatch(
            sha256 = sha256,
            size = size,
            assetPath = assetPath,
            remoteUrl = remoteUrl,
            version = version,
            verName = verName,
            verCode = verCode,
        )
    }

    private fun parseRuntimePatch(obj: JSONObject, label: String): RuntimeAssetPinPatch {
        val sha256 =
            optString(obj, "sha256")?.also {
                require(AssetDigest.HEX.matches(it.lowercase())) {
                    "$label.sha256 must be a 64-character SHA-256"
                }
            }?.lowercase()
        val size =
            optLong(obj, "size")?.also {
                require(it > 0L) { "$label.size must be positive" }
            }
        val remoteUrl =
            optString(obj, "remoteUrl")?.also {
                requireHttpsUrl(it, "$label.remoteUrl")
            }
        return RuntimeAssetPinPatch(sha256 = sha256, size = size, remoteUrl = remoteUrl)
    }

    private fun ManifestEntry.patchedBy(patch: ComponentPinPatch): ManifestEntry = copy(
        sha256 = patch.sha256 ?: sha256,
        size = patch.size ?: size,
        assetPath = patch.assetPath ?: assetPath,
        remoteUrl = patch.remoteUrl ?: remoteUrl,
        version = patch.version ?: version,
        verName = patch.verName ?: verName,
        verCode = patch.verCode ?: verCode,
    )

    private fun RuntimeAssetEntry.patchedBy(patch: RuntimeAssetPinPatch): RuntimeAssetEntry = copy(
        sha256 = patch.sha256 ?: sha256,
        size = patch.size ?: size,
        remoteUrl = patch.remoteUrl ?: remoteUrl,
    )

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

    private fun optLong(obj: JSONObject, key: String): Long? =
        if (obj.has(key) && !obj.isNull(key)) obj.getLong(key) else null

    private fun optInt(obj: JSONObject, key: String): Int? =
        if (obj.has(key) && !obj.isNull(key)) obj.getInt(key) else null
}

/** Catalog-level pin overlay payload. */
data class DevPins(
    val version: Int = 1,
    val components: Map<String, ComponentPinPatch> = emptyMap(),
    val runtimeAssets: Map<String, RuntimeAssetPinPatch> = emptyMap(),
) {
    val isEmpty: Boolean get() = components.isEmpty() && runtimeAssets.isEmpty()

    fun toJson(): String {
        val root = JSONObject()
        root.put("version", version)
        val componentsObj = JSONObject()
        for ((key, patch) in components) {
            componentsObj.put(key, patch.toJson())
        }
        root.put("components", componentsObj)
        val runtimeObj = JSONObject()
        for ((key, patch) in runtimeAssets) {
            runtimeObj.put(key, patch.toJson())
        }
        root.put("runtimeAssets", runtimeObj)
        return root.toString(2)
    }

    companion object {
        val EMPTY = DevPins()
    }
}

data class ComponentPinPatch(
    val sha256: String? = null,
    val size: Long? = null,
    val assetPath: String? = null,
    val remoteUrl: String? = null,
    val version: String? = null,
    val verName: String? = null,
    val verCode: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        sha256?.let { put("sha256", it) }
        size?.let { put("size", it) }
        assetPath?.let { put("assetPath", it) }
        if (remoteUrl != null) put("remoteUrl", remoteUrl) else put("remoteUrl", JSONObject.NULL)
        version?.let { put("version", it) }
        verName?.let { put("verName", it) }
        verCode?.let { put("verCode", it) }
    }
}

data class RuntimeAssetPinPatch(val sha256: String? = null, val size: Long? = null, val remoteUrl: String? = null) {
    fun toJson(): JSONObject = JSONObject().apply {
        sha256?.let { put("sha256", it) }
        size?.let { put("size", it) }
        if (remoteUrl != null) put("remoteUrl", remoteUrl) else put("remoteUrl", JSONObject.NULL)
    }
}

/** Which catalog entries were overridden by a [DevPins] apply. */
data class DevPinApplication(
    val overriddenComponents: Set<ContentComponent> = emptySet(),
    val overriddenRuntimeAssets: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY = DevPinApplication()
    }
}
