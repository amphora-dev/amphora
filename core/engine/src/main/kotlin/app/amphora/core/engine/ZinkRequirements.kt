package app.amphora.core.engine

/**
 * What makes Mesa 26.2.0's zink refuse a physical device, checked against the
 * [GuestVulkanDevice] the guest probe captured inside Wine.
 *
 * Pinned to the Mesa that imagefs builds zink from. Two builds share
 * `mesa-26.2.0.tar.xz`: `buildstream/elements/graphics/mesa-gl.bst` (the
 * native Bionic/Linux Mesa) and `buildstream/elements/l1/mesa-wgl.bst` (the
 * Windows `opengl32.dll` that Windows programs use, see [MesaWglRuntime]).
 * This list feeds the Settings text and the wined3d renderer choice
 * ([MesaWglRuntime.wined3dConfig]); Mesa WGL itself decides between zink and
 * softpipe when it creates its screen. Source,
 * tag `mesa-26.2.0`, `src/gallium/drivers/zink/`:
 *
 * - `zink_device_info.py` `EXTENSIONS`: five entries carry `required=True` —
 *   `VK_KHR_maintenance1` (l.62), `VK_KHR_maintenance5` (l.69),
 *   `VK_KHR_create_renderpass2` (l.100), `VK_KHR_dynamic_rendering` (l.209),
 *   `VK_KHR_descriptor_update_template` (l.322). The generated
 *   `zink_get_physical_device_info()` (template l.763-795) fails with
 *   `ZINK: <ext> required!` when one is not advertised, unless the extension
 *   was promoted to a core version the device runs (`have_vulkanXY`, set when
 *   `screen->vk_version` reaches it; `vk_version` = min(device apiVersion,
 *   loader version), `zink_screen.c:1837`).
 * - `zink_screen.c:3515` `zink_internal_create_screen()`: fails with
 *   `Zink requires the nullDescriptor feature of KHR/EXT robustness2` when
 *   `rb2_feats.nullDescriptor` is false.
 * - `zink_screen.c:3574`: fails with `KHR_timeline_semaphore is required`
 *   unless `VK_KHR_timeline_semaphore` is advertised or
 *   `VkPhysicalDeviceVulkan12Features::timelineSemaphore` is set.
 *
 * Compared with the 25.x list this replaces: `VK_KHR_imageless_framebuffer` is
 * no longer required (no `required=True` in 26.2.0), `VK_KHR_maintenance5` is
 * new, and robustness2 is checked by its `nullDescriptor` feature instead of
 * the extension name.
 *
 * Not modelled: `choose_pdev()` (`zink_screen.c:1828`) skips CPU adapters
 * unless software rendering is forced, and l.3581 rejects Imagination blobs
 * older than DDK 24.1. Neither applies to the Adreno and Mali adapters the
 * guest sees.
 *
 * Probe limits: the report has no loader version, so the device apiVersion
 * stands in for `vk_version` (a loader older than the device would make zink
 * stricter than this check). `extRobustness2` is only captured when the device
 * advertises `VK_EXT_robustness2`; a device exposing only
 * `VK_KHR_robustness2` reads as missing `nullDescriptor` here although zink
 * would accept it.
 *
 * Observed on Mali-G76 with the r34 blob (Vulkan 1.1.191): no
 * `VK_KHR_dynamic_rendering` and no `VK_EXT_robustness2`. Adreno blobs and
 * Turnip on the Y700 meet the whole set (`src/test/resources/vkprobe/`).
 */
object ZinkRequirements {
    /** Mesa release the table is read from. */
    const val MESA_VERSION = "26.2.0"

    /**
     * A `required=True` extension and the core version it was promoted to
     * (`promotedto` in Mesa's `vk.xml`); a device at that version or newer
     * passes without advertising it.
     */
    private data class RequiredExtension(val name: String, val coreSince: Int)

    /** In `zink_device_info.py` order, which is the order zink checks them. */
    private val REQUIRED_EXTENSIONS = listOf(
        RequiredExtension("VK_KHR_maintenance1", GuestVulkanDevice.apiVersion(1, 1)),
        RequiredExtension("VK_KHR_maintenance5", GuestVulkanDevice.apiVersion(1, 4)),
        RequiredExtension("VK_KHR_create_renderpass2", GuestVulkanDevice.apiVersion(1, 2)),
        RequiredExtension("VK_KHR_dynamic_rendering", GuestVulkanDevice.apiVersion(1, 3)),
        RequiredExtension("VK_KHR_descriptor_update_template", GuestVulkanDevice.apiVersion(1, 1)),
    )

    private const val NULL_DESCRIPTOR = "extRobustness2.nullDescriptor"
    private const val TIMELINE_EXTENSION = "VK_KHR_timeline_semaphore"
    private const val TIMELINE_FEATURE = "vk12.timelineSemaphore"

    /**
     * What [device] is missing, in the order zink fails on it. Empty means
     * zink 26.2 can create a screen on this adapter.
     */
    fun missing(device: GuestVulkanDevice): List<String> {
        val out = mutableListOf<String>()
        for (ext in REQUIRED_EXTENSIONS) {
            if (ext.name !in device.extensions && device.apiVersion < ext.coreSince) {
                out += "extension ${ext.name}"
            }
        }
        if (!device.feature(NULL_DESCRIPTOR)) out += "feature $NULL_DESCRIPTOR"
        if (TIMELINE_EXTENSION !in device.extensions && !device.feature(TIMELINE_FEATURE)) {
            out += "extension $TIMELINE_EXTENSION or feature $TIMELINE_FEATURE"
        }
        return out
    }
}
