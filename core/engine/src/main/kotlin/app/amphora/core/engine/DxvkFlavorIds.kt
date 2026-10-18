package app.amphora.core.engine

import app.amphora.core.content.model.ContentComponent

/**
 * Which DXVK build a launch installs, and why.
 *
 * Two are pinned. Stock DXVK 3.0.2 (gplasync @6b20f622a) has a large hard
 * requirement set — Vulkan 1.3, a graphics queue, 51 required features, 2
 * required extensions, push-data limits — and fails with "No adapters found"
 * when any one is missing. DXVK-Sarek is a 1.11-era fork that only needs Vulkan 1.1 +
 * `VK_KHR_swapchain` + a graphics queue and runs where 3.0.2 cannot, at the
 * cost of everything DXVK gained since.
 *
 * The deciding fact is the device's capability *as the guest sees it* — the
 * adapter enumerated inside Wine through winevulkan → `VK_LAYER_AMPHORA_wsi` →
 * the session driver stack, checked against each build's full requirement
 * table in [DxvkRequirements]. A host-side Vulkan version probe cannot see
 * the wrapper's reported features (e.g. Turnip on Adreno 630 reports 1.3 but
 * lacks `storageBuffer16BitAccess`).
 */
object DxvkFlavorIds {
    const val PREFS_KEY_FLAVOR = "dxvk_flavor"

    /** Follow the guest Vulkan probe. */
    const val AUTO = "auto"
    const val DXVK = "dxvk"
    const val SAREK = "sarek"

    /**
     * The flavor [d3d89Flavor] returns — which pinned build supplies d3d8/d3d9
     * when the Qualcomm split engages. Single point to change once the split
     * build moves (e.g. to a DXVK 2.x pin).
     */
    const val D3D89_FLAVOR = SAREK

    fun normalize(id: String?): String = when (id) {
        DXVK -> DXVK
        SAREK -> SAREK
        else -> AUTO
    }

    /**
     * The build this launch will use.
     *
     * [report] is the guest probe's adapter data for the driver this launch
     * will run, or null when the probe itself failed (never ran, timed out,
     * crashed). `auto` picks the best build whose complete requirement set is
     * met on some enumerated adapter; with no probe data the safe answer is
     * [SAREK] — the same floor DXVK itself reaches when it finds no adapter.
     * Explicit `dxvk`/`sarek` are honoured as-is.
     */
    fun resolve(id: String?, report: GuestVulkanProbeReport?): String = when (normalize(id)) {
        DXVK -> DXVK
        SAREK -> SAREK
        else -> resolveAuto(report)
    }

    /** Best supported build per [DxvkRequirements.AUTO_ORDER], or [SAREK]. */
    fun resolveAuto(report: GuestVulkanProbeReport?): String {
        if (report == null) return SAREK
        return DxvkRequirements.AUTO_ORDER
            .firstOrNull { set -> report.devices.any { set.missing(it).isEmpty() } }
            ?.flavorId
            ?: SAREK
    }

    fun component(flavor: String): ContentComponent =
        if (normalize(flavor) == SAREK) ContentComponent.DXVK_SAREK else ContentComponent.DXVK

    /**
     * The build d3d8/d3d9 come from on a launch resolving to [resolvedFlavor]
     * on [effectiveDriverId], or null when they stay with that build.
     *
     * Qualcomm's proprietary Vulkan driver — what the wrapper ICD and the
     * System loader both reach on Adreno — rejects the fixed-function shaders
     * DXVK ≥ 2.x emits for D3D8/9 (`vkCreateGraphicsPipelines` fails with -13
     * and the draw never lands). DXVK-Sarek's compile fine, so on that path
     * `auto` puts d3d8/d3d9 on whatever [ContentComponent.DXVK_SAREK] pins while
     * dxgi/d3d10core/d3d11 stay on 3.0.2. Turnip compiles the same shaders
     * without error, non-Adreno devices never run the Qualcomm stack, and an
     * explicit flavor pick is meant to be pure — none of those split.
     */
    fun d3d89Flavor(id: String?, resolvedFlavor: String, effectiveDriverId: String, isAdreno: Boolean): String? = if (
        normalize(id) == AUTO &&
        resolvedFlavor == DXVK &&
        isAdreno &&
        (effectiveDriverId == GraphicsDriverIds.WRAPPER || effectiveDriverId == GraphicsDriverIds.SYSTEM)
    ) {
        D3D89_FLAVOR
    } else {
        null
    }
}
