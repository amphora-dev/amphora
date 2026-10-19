package app.amphora.core.engine

/**
 * What makes vkd3d-proton refuse a physical device — the checks that return
 * `E_INVALIDARG`/skip the adapter during `D3D12CreateDevice`, expressed as a
 * table checked against the [GuestVulkanDevice] captured inside Wine.
 *
 * Source: HansKristian-Work/vkd3d-proton @
 * `3b10bd7a7ec6a7347e616cf8bea59333afec2255` (tag v3.0.1 — the shipped
 * `Vkd3d-3.0.1-3b10bd7a7.wcp`, buildstream `l1/vkd3d-wcp.bst`).
 * `libs/vkd3d/device.c`:
 *
 * - `vkd3d_select_physical_device` skips adapters whose
 *   `properties.apiVersion < VKD3D_MIN_API_VERSION` (= `VK_API_VERSION_1_3`,
 *   `include/vkd3d.h`) — a Vulkan 1.1/1.2 device never reaches D3D12.
 * - `vkd3d_physical_device_info_init` returns `E_INVALIDARG` when any of:
 *   `vulkan_1_2_features.samplerMirrorClampToEdge`,
 *   `robustness2_features.{robustBufferAccess2,robustImageAccess2}` (both
 *   required together), `robustness2_features.nullDescriptor`,
 *   `vulkan_1_1_features.shaderDrawParameters`, or the
 *   `VK_KHR_push_descriptor` extension is missing.
 * - `VK_KHR_swapchain` is not checked at device creation but the dxgi
 *   swapchain path needs it — a device without it cannot present.
 *
 * Warn-only checks (robustBufferAccessUpdateAfterBind,
 * EXT_dynamic_rendering_unused_attachments) do not refuse the device and are
 * intentionally not in the table.
 */
object Vkd3dRequirements {
    /** `VK_API_VERSION_1_3` — `VKD3D_MIN_API_VERSION`/`VKD3D_MAX_API_VERSION`. */
    const val MIN_API_VERSION = (1 shl 22) or (3 shl 12)

    private const val BUILD = "VKD3D-3.0.1-3b10bd7a7-0"
    private const val SOURCE = "HansKristian-Work/vkd3d-proton@3b10bd7a7 (v3.0.1), libs/vkd3d/device.c"

    private val REQUIRED_FEATURES = listOf(
        "vk12.samplerMirrorClampToEdge",
        "extRobustness2.robustBufferAccess2",
        "extRobustness2.robustImageAccess2",
        "extRobustness2.nullDescriptor",
        "vk11.shaderDrawParameters",
    )

    private val REQUIRED_EXTENSIONS = listOf(
        "VK_KHR_push_descriptor",
        "VK_KHR_swapchain",
    )

    /**
     * Human-readable names of everything [device] is missing, in source
     * check order. Empty means vkd3d-proton accepts the adapter for D3D12.
     */
    fun missing(device: GuestVulkanDevice): List<String> {
        val out = mutableListOf<String>()
        if (device.apiVersion < MIN_API_VERSION) {
            out += "${device.apiLabel} below required ${apiLabel(MIN_API_VERSION)}"
        }
        if (!device.hasGraphicsComputeQueue()) {
            out += "graphics+compute queue family"
        }
        for (feature in REQUIRED_FEATURES) {
            if (!device.feature(feature)) out += "feature $feature"
        }
        for (ext in REQUIRED_EXTENSIONS) {
            if (ext !in device.extensions) out += "extension $ext"
        }
        return out
    }

    /**
     * D3D12 also needs a dxgi factory that will create a D3D12 device.
     * Sarek's dxgi (`DXVK-Sarek` build) rejects D3D12 adapters outright —
     * no Vulkan feature can fix that while Sarek is the selected DXVK.
     */
    fun dxgiBlocksD3d12(dxvkFlavorId: String): Boolean = DxvkFlavorIds.normalize(dxvkFlavorId) == DxvkFlavorIds.SAREK

    private fun apiLabel(apiVersion: Int): String = "Vulkan ${apiVersion shr 22}.${(apiVersion shr 12) and 0x3ff}"
}
