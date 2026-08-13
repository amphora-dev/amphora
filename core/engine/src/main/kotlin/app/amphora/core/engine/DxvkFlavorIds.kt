package app.amphora.core.engine

import app.amphora.core.content.model.ContentComponent

/**
 * Which DXVK build a launch installs, and why.
 *
 * Two are pinned. Stock DXVK 3.0.2 needs a Vulkan 1.3 device and fails
 * enumeration outright below that ("No adapters found ... a Vulkan 1.3 capable
 * setup is required"); DXVK-Sarek is a 1.11-era fork that targets Vulkan 1.1/1.2
 * and runs where 3.0.2 cannot, at the cost of everything DXVK gained since.
 *
 * The deciding fact is the device's Vulkan level, not its GPU vendor or driver:
 * a Vulkan 1.3 Mali on the Leegao path wants 3.0.2, and a Vulkan 1.1 Adreno on
 * the wrapper wants Sarek.
 */
object DxvkFlavorIds {
    const val PREFS_KEY_FLAVOR = "dxvk_flavor"

    /** Follow the device's Vulkan level. */
    const val AUTO = "auto"
    const val DXVK = "dxvk"
    const val SAREK = "sarek"

    /** Vulkan minor version DXVK 3.0.2 requires. */
    const val DXVK_MIN_VULKAN_MINOR = 3

    fun normalize(id: String?): String = when (id) {
        DXVK -> DXVK
        SAREK -> SAREK
        else -> AUTO
    }

    /**
     * The build this launch will use. [vulkanMinor] is null when the Vulkan
     * version probe failed, which leaves the driver as the only hint: the Leegao
     * path exists for devices whose vendor Vulkan predates 1.3.
     */
    fun resolve(id: String?, vulkanMinor: Int?, usesLeegao: Boolean): String = when (normalize(id)) {
        DXVK -> DXVK
        SAREK -> SAREK
        else -> when {
            vulkanMinor != null -> if (vulkanMinor >= DXVK_MIN_VULKAN_MINOR) DXVK else SAREK
            usesLeegao -> SAREK
            else -> DXVK
        }
    }

    fun component(flavor: String): ContentComponent =
        if (normalize(flavor) == SAREK) ContentComponent.DXVK_SAREK else ContentComponent.DXVK

    /** True when [flavor] cannot enumerate an adapter on a device at [vulkanMinor]. */
    fun isUnsupported(flavor: String, vulkanMinor: Int?): Boolean =
        normalize(flavor) == DXVK && vulkanMinor != null && vulkanMinor < DXVK_MIN_VULKAN_MINOR
}
