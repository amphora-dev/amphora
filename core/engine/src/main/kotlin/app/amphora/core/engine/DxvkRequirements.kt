package app.amphora.core.engine

/**
 * Everything that makes a given DXVK build reject a physical device, expressed
 * as data checked against a [GuestVulkanDevice] captured *inside Wine*.
 *
 * Each table is pinned to the exact build the app ships — not to upstream
 * marketing names. Feature subjects use the probe's `structTag.field` keys,
 * which mirror the `version.name`/`ext.name` identifiers in DXVK's
 * `DxvkDeviceCapabilities::getFeatureList()` (`src/dxvk/dxvk_device_info.cpp`).
 */
data class DxvkRequirementSet(
    /** [DxvkFlavorIds] id this table belongs to. */
    val flavorId: String,
    /** Shipped package identity, for logging and comments. */
    val build: String,
    /** Where the rules were read from; keep this accurate when the pin moves. */
    val source: String,
    /** `properties.apiVersion` lower bound (`VK_MAKE_API_VERSION`). */
    val minApiVersion: Int,
    /** CPU-type adapters are skipped when any non-CPU adapter exists. */
    val skipCpuDevices: Boolean,
    /** Required with `VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT` in one family. */
    val requiresGraphicsQueue: Boolean,
    /** Extensions the build requires unconditionally (`ENABLE_EXT` entries). */
    val requiredExtensions: Set<String>,
    /** Required feature fields (`require=true` entries), `tag.field` keys. */
    val requiredFeatures: Set<String>,
    /**
     * `limits.<name>` → minimum value. Each limit is waived when the device
     * advertises [limitsExemptFeature] (the `descriptorHeap` escape in
     * `checkDeviceCompatibility`: push data is only needed when the descriptor
     * heap path is unavailable).
     */
    val requiredLimits: Map<String, Long>,
    val limitsExemptFeature: String? = null,
) {
    /**
     * Human-readable names of everything [device] is missing. Empty means the
     * build can enumerate and create a device on this adapter.
     */
    fun missing(device: GuestVulkanDevice): List<String> {
        val out = mutableListOf<String>()
        if (skipCpuDevices && device.deviceType == GuestVulkanDevice.VK_PHYSICAL_DEVICE_TYPE_CPU) {
            out += "non-CPU adapter (got CPU: ${device.deviceName})"
        }
        if (device.apiVersion < minApiVersion) {
            out += "${device.apiLabel} below required ${apiLabel(minApiVersion)}"
        }
        if (requiresGraphicsQueue && !device.hasGraphicsComputeQueue()) {
            out += "graphics+compute queue family"
        }
        for (ext in requiredExtensions) {
            if (ext !in device.extensions) out += "extension $ext"
        }
        for (feature in requiredFeatures) {
            if (!device.feature(feature)) out += "feature $feature"
        }
        val limitsExempt = limitsExemptFeature?.let(device::feature) == true
        if (!limitsExempt) {
            for ((name, min) in requiredLimits) {
                val actual = device.limit(name)
                if (actual < min) out += "limit $name=$actual below $min"
            }
        }
        return out
    }

    companion object {
        fun apiLabel(apiVersion: Int): String = "Vulkan ${apiVersion shr 22}.${(apiVersion shr 12) and 0x3ff}"
    }
}

object DxvkRequirements {

    /**
     * DXVK gplasync build, `Dxvk-3.0.2-gplasync-6b20f622a.wcp`.
     *
     * Source: doitsujin/dxvk `6b20f622a77b87b2921fe5d2c1774d2f2ba3e9b7` (master
     * track, not the v3.0.2 tag). `src/dxvk/dxvk_device_info.cpp`:
     * `checkDeviceCompatibility()` requires `apiVersion >= VK_API_VERSION_1_3`,
     * a `GRAPHICS|COMPUTE` queue family, every `getFeatureList()` entry marked
     * `require` — the 51 entries below — and `maxPushConstantsSize >=
     * MaxTotalPushDataSize` (256, `dxvk_limits.h`) unless
     * `VK_EXT_descriptor_heap` is usable. Extension-required entries
     * (`ENABLE_EXT`) need the extension present; feature-required entries need
     * the field true (their struct is only chained when the extension exists,
     * so a missing extension shows up as a missing feature anyway).
     */
    val DXVK_3_0_2_GPLASYNC = DxvkRequirementSet(
        flavorId = DxvkFlavorIds.DXVK,
        build = "Dxvk-3.0.2-gplasync-6b20f622a.wcp",
        source = "doitsujin/dxvk@6b20f622a src/dxvk/dxvk_device_info.cpp " +
            "getFeatureList() require=true + checkDeviceCompatibility()",
        minApiVersion = GuestVulkanDevice.apiVersion(1, 3),
        skipCpuDevices = true,
        requiresGraphicsQueue = true,
        requiredExtensions = setOf(
            /* ENABLE_EXT(khrLoadStoreOpNone, true) — LOAD_OP_NONE for tilers */
            "VK_KHR_load_store_op_none",
            /* ENABLE_EXT(khrSwapchain, true) — presentation */
            "VK_KHR_swapchain",
        ),
        requiredFeatures = setOf(
            // core.features (VkPhysicalDeviceFeatures)
            "core.depthBiasClamp",
            "core.depthClamp",
            "core.dualSrcBlend",
            "core.fillModeNonSolid",
            "core.fragmentStoresAndAtomics",
            "core.fullDrawIndexUint32",
            "core.geometryShader",
            "core.imageCubeArray",
            "core.independentBlend",
            "core.multiDrawIndirect",
            "core.multiViewport",
            "core.occlusionQueryPrecise",
            "core.robustBufferAccess",
            "core.sampleRateShading",
            "core.samplerAnisotropy",
            "core.shaderClipDistance",
            "core.shaderCullDistance",
            "core.shaderImageGatherExtended",
            "core.shaderInt16",
            "core.shaderInt64",
            "core.shaderSampledImageArrayDynamicIndexing",
            "core.textureCompressionBC",
            // vk11 (VkPhysicalDeviceVulkan11Features) — storageBuffer16BitAccess
            // is what Adreno 630/Turnip lacks
            "vk11.shaderDrawParameters",
            "vk11.storageBuffer16BitAccess",
            // vk12 (VkPhysicalDeviceVulkan12Features)
            "vk12.bufferDeviceAddress",
            "vk12.descriptorBindingPartiallyBound",
            "vk12.descriptorBindingSampledImageUpdateAfterBind",
            "vk12.descriptorBindingUpdateUnusedWhilePending",
            "vk12.descriptorIndexing",
            "vk12.hostQueryReset",
            "vk12.runtimeDescriptorArray",
            "vk12.samplerMirrorClampToEdge",
            "vk12.scalarBlockLayout",
            "vk12.shaderInt8",
            "vk12.storageBuffer8BitAccess",
            "vk12.timelineSemaphore",
            "vk12.uniformBufferStandardLayout",
            "vk12.vulkanMemoryModel",
            // vk13 (VkPhysicalDeviceVulkan13Features)
            "vk13.computeFullSubgroups",
            "vk13.dynamicRendering",
            "vk13.inlineUniformBlock",
            "vk13.maintenance4",
            "vk13.shaderDemoteToHelperInvocation",
            "vk13.shaderZeroInitializeWorkgroupMemory",
            "vk13.subgroupSizeControl",
            "vk13.synchronization2",
            // ext structs — VK_EXT_depth_clip_enable
            "extDepthClipEnable.depthClipEnable",
            // VK_EXT_robustness2 (all three are required for correctness)
            "extRobustness2.robustBufferAccess2",
            "extRobustness2.nullDescriptor",
            // VK_KHR_maintenance5 / VK_KHR_maintenance6
            "khrMaintenance5.maintenance5",
            "khrMaintenance6.maintenance6",
        ),
        requiredLimits = mapOf(
            // dxvk_limits.h MaxTotalPushDataSize; skipped when the device can
            // use VK_EXT_descriptor_heap instead of push constants.
            "maxPushConstantsSize" to 256L,
        ),
        limitsExemptFeature = "extDescriptorHeap.descriptorHeap",
    )

    /**
     * DXVK-Sarek build, `DXVK-1.11.0-async-sarek-0` — Sarek is a DXVK 1.10.x
     * fork (pythonlover02/DXVK-Sarek).
     *
     * Source: pythonlover02/DXVK-Sarek `main` `src/dxvk/dxvk_device_filter.cpp`
     * `testAdapter()` (`apiVersion >= VK_MAKE_VERSION(1,1,0)`, CPU adapters
     * skipped when a GPU exists) and `dxvk_adapter.cpp` `createDevice()` —
     * `VK_KHR_swapchain` is the only `DxvkExtMode::Required` extension in
     * `dxvk_extensions.h`; `findQueueFamilies()` needs one
     * `GRAPHICS|COMPUTE` family or `vkCreateDevice` fails. Sarek has no
     * adapter-level feature checks (per-feature `checkFeatureSupport` runs
     * later, per-D3D call, on the frontend's requested set).
     */
    val SAREK_1_11_0 = DxvkRequirementSet(
        flavorId = DxvkFlavorIds.SAREK,
        build = "DXVK-1.11.0-async-sarek-0",
        source = "pythonlover02/DXVK-Sarek main src/dxvk/dxvk_device_filter.cpp " +
            "testAdapter() + dxvk_adapter.cpp createDevice()/findQueueFamilies() " +
            "+ dxvk_extensions.h DxvkExtMode::Required",
        minApiVersion = GuestVulkanDevice.apiVersion(1, 1),
        skipCpuDevices = true,
        requiresGraphicsQueue = true,
        requiredExtensions = setOf("VK_KHR_swapchain"),
        requiredFeatures = emptySet(),
        requiredLimits = emptyMap(),
    )

    /** Builds `auto` may pick, best first. */
    val AUTO_ORDER: List<DxvkRequirementSet> = listOf(DXVK_3_0_2_GPLASYNC, SAREK_1_11_0)

    fun forFlavor(flavorId: String): DxvkRequirementSet? = when (flavorId) {
        DxvkFlavorIds.DXVK -> DXVK_3_0_2_GPLASYNC
        DxvkFlavorIds.SAREK -> SAREK_1_11_0
        else -> null
    }

    /**
     * Requirements missing on the best adapter in [report] for [flavorId] —
     * i.e. what DXVK itself would complain about. Empty when some adapter
     * satisfies the build's full set. A null report (probe never ran or
     * failed) is surfaced by the caller, not here.
     */
    fun missing(flavorId: String, report: GuestVulkanProbeReport): List<String> {
        val set = forFlavor(flavorId) ?: return listOf("unknown DXVK build '$flavorId'")
        if (report.devices.isEmpty()) {
            return listOf("no Vulkan adapter enumerated (instanceCreate=${report.instanceCreate})")
        }
        // An adapter qualifies when its complete requirement set is met — the
        // same "any adapter wins" rule as DXVK's adapter scan.
        return report.devices.map { set.missing(it) }.minByOrNull { it.size }!!
    }
}
