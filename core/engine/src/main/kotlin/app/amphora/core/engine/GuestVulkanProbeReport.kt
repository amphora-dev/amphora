package app.amphora.core.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * The guest's view of Vulkan, as reported by `amphora-vkprobe.exe` running
 * inside Wine through the real session stack (winevulkan →
 * `VK_LAYER_AMPHORA_wsi` → wrapper/Turnip/System ICD with the session env).
 *
 * The probe writes one JSON document between `VKPROBE_JSON_BEGIN` and
 * `VKPROBE_JSON_END` markers on stdout (`scripts/guest-vkprobe/vkprobe.c`,
 * schema `"vkprobe":1`). Feature fields keep the pNext-chain layout DXVK
 * queries: `features.<structTag>.<field>` — e.g. `vk12.storageBuffer16BitAccess`,
 * `extRobustness2.nullDescriptor` — so the requirement tables can name checks
 * exactly as `getFeatureList()` does in `dxvk_device_info.cpp`.
 */
data class GuestVulkanDevice(
    val deviceName: String,
    /** `VkPhysicalDeviceProperties::apiVersion` (`VK_MAKE_API_VERSION`). */
    val apiVersion: Int,
    val vendorId: Int,
    val deviceId: Int,
    /** `VkPhysicalDeviceType`; CPU = `VK_PHYSICAL_DEVICE_TYPE_CPU` (4). */
    val deviceType: Int,
    val driverName: String,
    val driverInfo: String,
    val driverId: Int,
    val conformance: String,
    /** Flat `tag.field` → supported map across every queried feature struct. */
    val features: Map<String, Boolean>,
    /** Device extension names exactly as `vkEnumerateDeviceExtensionProperties` reports. */
    val extensions: Set<String>,
    /** `VkPhysicalDeviceLimits` values keyed by field name. */
    val limits: Map<String, Long>,
    /** `(queueFlags, queueCount)` per family, in family-index order. */
    val queueFamilies: List<QueueFamily>,
) {
    data class QueueFamily(val flags: Int, val count: Int)

    val apiMajor: Int get() = apiVersion shr 22
    val apiMinor: Int get() = (apiVersion shr 12) and 0x3ff
    val apiLabel: String get() = "Vulkan $apiMajor.$apiMinor"

    fun hasGraphicsComputeQueue(): Boolean =
        queueFamilies.any { it.count > 0 && (it.flags and QUEUE_GRAPHICS_OR_COMPUTE) == QUEUE_GRAPHICS_OR_COMPUTE }

    fun feature(name: String): Boolean = features[name] == true

    fun limit(name: String): Long = limits[name] ?: 0L

    companion object {
        const val VK_PHYSICAL_DEVICE_TYPE_CPU = 4
        const val VK_QUEUE_GRAPHICS_BIT = 0x1
        const val VK_QUEUE_COMPUTE_BIT = 0x2
        const val QUEUE_GRAPHICS_OR_COMPUTE = VK_QUEUE_GRAPHICS_BIT or VK_QUEUE_COMPUTE_BIT

        fun apiVersion(major: Int, minor: Int): Int = (major shl 22) or (minor shl 12)
    }
}

/**
 * One probe run. [devices] empty means no adapter was enumerated — DXVK's own
 * answer to that is `No adapters found`, so an empty list already says Sarek.
 */
data class GuestVulkanProbeReport(
    /** `vkCreateInstance` result with DXVK's WSI extension list (0 = success). */
    val surfaceInstanceCreate: Int,
    /** `vkCreateInstance` result after the bare retry (0 = success). */
    val instanceCreate: Int,
    val instanceExtensions: Set<String>,
    val enumerateDevices: Int,
    val devices: List<GuestVulkanDevice>,
) {
    /** The guest-visible list — instanceCreate surfaced an error, no devices, etc. */
    val ok: Boolean get() = instanceCreate == 0 && enumerateDevices == 0

    companion object {
        const val SCHEMA = 1
        const val JSON_BEGIN = "VKPROBE_JSON_BEGIN"
        const val JSON_END = "VKPROBE_JSON_END"

        /**
         * Extracts the probe document from captured stdout, or null when no
         * complete marker pair is present (killed process, early crash).
         */
        fun extract(stdout: String): GuestVulkanProbeReport? {
            val start = stdout.indexOf(JSON_BEGIN)
            val end = stdout.indexOf(JSON_END)
            if (start < 0 || end < 0 || end <= start) return null
            return parse(stdout.substring(start + JSON_BEGIN.length, end).trim())
        }

        fun parse(json: String): GuestVulkanProbeReport? = runCatching {
            val root = JSONObject(json)
            val devices = mutableListOf<GuestVulkanDevice>()
            val arr = root.optJSONArray("devices") ?: JSONArray()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { devices.add(it.toDevice()) }
            }
            GuestVulkanProbeReport(
                surfaceInstanceCreate = root.optInt("surfaceInstanceCreate", -1),
                instanceCreate = root.optInt("instanceCreate", -1),
                instanceExtensions = root.optJSONArray("instanceExtensions").toStringSet(),
                enumerateDevices = root.optInt("enumerateDevices", -1),
                devices = devices,
            )
        }.getOrNull()

        private fun JSONObject.toDevice(): GuestVulkanDevice {
            val driver = optJSONObject("driver") ?: JSONObject()
            val features = mutableMapOf<String, Boolean>()
            val feats = optJSONObject("features")
            if (feats != null) {
                for (tag in feats.keys()) {
                    val st = feats.optJSONObject(tag) ?: continue
                    for (field in st.keys()) {
                        features["$tag.$field"] = st.optInt(field, 0) != 0
                    }
                }
            }
            val limits = mutableMapOf<String, Long>()
            optJSONObject("limits")?.let { lim ->
                for (key in lim.keys()) {
                    // Scalar limits only; the probe also emits float/array
                    // entries, which are irrelevant to requirement checks.
                    runCatching { limits[key] = lim.getLong(key) }
                }
            }
            val queues = mutableListOf<GuestVulkanDevice.QueueFamily>()
            optJSONArray("queueFamilies")?.let { qa ->
                for (i in 0 until qa.length()) {
                    val q = qa.optJSONObject(i) ?: continue
                    queues.add(
                        GuestVulkanDevice.QueueFamily(
                            flags = q.optInt("flags", 0),
                            count = q.optInt("count", 0),
                        ),
                    )
                }
            }
            return GuestVulkanDevice(
                deviceName = optString("deviceName", ""),
                apiVersion = optInt("apiVersion", 0),
                vendorId = optInt("vendorID", 0),
                deviceId = optInt("deviceID", 0),
                deviceType = optInt("deviceType", 0),
                driverName = driver.optString("name", ""),
                driverInfo = driver.optString("info", ""),
                driverId = driver.optInt("id", -1),
                conformance = driver.optString("conformance", ""),
                features = features,
                extensions = optJSONArray("extensions").toStringSet(),
                limits = limits,
                queueFamilies = queues,
            )
        }

        private fun JSONArray?.toStringSet(): Set<String> {
            if (this == null) return emptySet()
            val out = LinkedHashSet<String>(length())
            for (i in 0 until length()) out.add(optString(i))
            return out
        }
    }
}
