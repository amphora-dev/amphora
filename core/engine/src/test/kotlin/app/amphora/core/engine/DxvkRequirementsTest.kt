package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the data behind `auto`: every shipped build's requirement table, and
 * the missing()/matching semantics the decision rides on. The tables are the
 * contract — a wrong entry here is a wrong build picked on a real device.
 */
class DxvkRequirementsTest {

    // --- fixture ----------------------------------------------------------

    /**
     * An adapter that satisfies every requirement of every build we ship:
     * Vulkan 1.4, all required features/extensions, generous limits, and a
     * graphics+compute queue — roughly a desktop-class guest view.
     */
    private fun fullyCapableDevice(
        apiVersion: Int = GuestVulkanDevice.apiVersion(1, 4),
        dropFeatures: Set<String> = emptySet(),
        dropExtensions: Set<String> = emptySet(),
        limits: Map<String, Long> = mapOf("maxPushConstantsSize" to 256L),
        deviceType: Int = 2, // VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU
        queueFlags: Int = GuestVulkanDevice.QUEUE_GRAPHICS_OR_COMPUTE,
    ): GuestVulkanDevice {
        val features =
            (DxvkRequirements.DXVK_3_0_2_GPLASYNC.requiredFeatures + "extDescriptorHeap.descriptorHeap")
                .associateWith { it !in dropFeatures }
        val extensions =
            DxvkRequirements.DXVK_3_0_2_GPLASYNC.requiredExtensions - dropExtensions
        return GuestVulkanDevice(
            deviceName = "Fixture GPU",
            apiVersion = apiVersion,
            vendorId = 0,
            deviceId = 0,
            deviceType = deviceType,
            driverName = "fixture",
            driverInfo = "",
            driverId = 0,
            conformance = "",
            features = features,
            extensions = extensions + "VK_KHR_swapchain",
            limits = limits,
            queueFamilies = listOf(GuestVulkanDevice.QueueFamily(flags = queueFlags, count = 1)),
        )
    }

    private fun report(vararg devices: GuestVulkanDevice) = GuestVulkanProbeReport(
        surfaceInstanceCreate = 0,
        instanceCreate = 0,
        instanceExtensions = emptySet(),
        enumerateDevices = 0,
        devices = devices.toList(),
    )

    // --- table shape -------------------------------------------------------

    @Test
    fun dxvk302TablePinsTheFullHardRequirementSet() {
        val set = DxvkRequirements.DXVK_3_0_2_GPLASYNC
        // checkDeviceCompatibility(): the API floor and queue/limits rules.
        assertEquals(GuestVulkanDevice.apiVersion(1, 3), set.minApiVersion)
        assertTrue(set.requiresGraphicsQueue)
        assertTrue(set.skipCpuDevices)
        assertEquals(mapOf("maxPushConstantsSize" to 256L), set.requiredLimits)
        assertEquals("extDescriptorHeap.descriptorHeap", set.limitsExemptFeature)
        // getFeatureList(): 46 core + 5 extension feature entries marked
        // require; the other 2 required entries are extension-only
        // (khrLoadStoreOpNone, khrSwapchain in requiredExtensions).
        assertEquals(51, set.requiredFeatures.size)
        // The Adreno 630 Turnip killer plus representative entries from every
        // feature struct DXVK chains.
        for (feature in listOf(
            "vk11.storageBuffer16BitAccess",
            "core.samplerAnisotropy",
            "vk12.timelineSemaphore",
            "vk12.descriptorIndexing",
            "vk13.dynamicRendering",
            "extRobustness2.nullDescriptor",
            "extDepthClipEnable.depthClipEnable",
            "khrMaintenance6.maintenance6",
        )) {
            assertTrue("missing required feature $feature", feature in set.requiredFeatures)
        }
        // The only two unconditionally-required extensions (ENABLE_EXT ... true).
        assertEquals(setOf("VK_KHR_load_store_op_none", "VK_KHR_swapchain"), set.requiredExtensions)
    }

    @Test
    fun sarekTablePinsItsActualAdapterAndDeviceChecks() {
        val set = DxvkRequirements.SAREK_1_11_0
        // dxvk_device_filter.cpp testAdapter(): API floor + non-CPU pick.
        assertEquals(GuestVulkanDevice.apiVersion(1, 1), set.minApiVersion)
        assertTrue(set.skipCpuDevices)
        // findQueueFamilies() + the one DxvkExtMode::Required extension.
        assertTrue(set.requiresGraphicsQueue)
        assertEquals(setOf("VK_KHR_swapchain"), set.requiredExtensions)
        // Sarek has no adapter-level feature gate (feature checks are
        // per-D3D-call inside the frontend) — keep it that way.
        assertTrue(set.requiredFeatures.isEmpty())
        assertTrue(set.requiredLimits.isEmpty())
    }

    // --- missing() ---------------------------------------------------------

    @Test
    fun aFullyCapableAdapterMissesNothing() {
        val device = fullyCapableDevice()
        assertTrue(DxvkRequirements.DXVK_3_0_2_GPLASYNC.missing(device).isEmpty())
        assertTrue(DxvkRequirements.SAREK_1_11_0.missing(device).isEmpty())
    }

    @Test
    fun missingStorageBuffer16BitAccessRejectsDxvkButNotSarek() {
        // The Turnip-on-Adreno-630 case: reports Vulkan 1.3 yet fails DXVK's
        // feature gate — exactly what the old host version probe got wrong.
        val device = fullyCapableDevice(
            apiVersion = GuestVulkanDevice.apiVersion(1, 3),
            dropFeatures = setOf("vk11.storageBuffer16BitAccess"),
        )
        val missing = DxvkRequirements.DXVK_3_0_2_GPLASYNC.missing(device)
        assertTrue(missing.any { it.contains("vk11.storageBuffer16BitAccess") })
        assertTrue(DxvkRequirements.SAREK_1_11_0.missing(device).isEmpty())
    }

    @Test
    fun sub13ApiRejectsDxvkWithAReadableReason() {
        val device = fullyCapableDevice(apiVersion = GuestVulkanDevice.apiVersion(1, 1))
        val missing = DxvkRequirements.DXVK_3_0_2_GPLASYNC.missing(device)
        assertTrue(missing.any { it.contains("Vulkan 1.3") })
    }

    @Test
    fun smallPushConstantsRejectDxvkUnlessDescriptorHeapSavesIt() {
        val device = fullyCapableDevice(
            dropFeatures = setOf("extDescriptorHeap.descriptorHeap"),
            limits = mapOf("maxPushConstantsSize" to 128L),
        )
        assertTrue(
            DxvkRequirements.DXVK_3_0_2_GPLASYNC.missing(device)
                .any { it.contains("maxPushConstantsSize") },
        )
        // With the descriptor-heap feature the push-data path is unnecessary.
        val heapDevice = fullyCapableDevice(limits = mapOf("maxPushConstantsSize" to 128L))
        assertFalse(
            DxvkRequirements.DXVK_3_0_2_GPLASYNC.missing(heapDevice)
                .any { it.contains("maxPushConstantsSize") },
        )
    }

    @Test
    fun missingSwapchainRejectsBothBuilds() {
        val device = fullyCapableDevice().copy(
            extensions = emptySet(),
        )
        assertFalse(DxvkRequirements.DXVK_3_0_2_GPLASYNC.missing(device).isEmpty())
        assertTrue(
            DxvkRequirements.SAREK_1_11_0.missing(device)
                .any { it.contains("VK_KHR_swapchain") },
        )
    }

    @Test
    fun cpuAdapterIsRejectedWhenAGpuMustWin() {
        val cpu = fullyCapableDevice(deviceType = GuestVulkanDevice.VK_PHYSICAL_DEVICE_TYPE_CPU)
        val missing = DxvkRequirements.DXVK_3_0_2_GPLASYNC.missing(cpu)
        assertTrue(missing.any { it.contains("CPU") })
        assertTrue(DxvkRequirements.SAREK_1_11_0.missing(cpu).any { it.contains("CPU") })
    }

    @Test
    fun anyQualifyingAdapterSatisfiesTheBuild() {
        val bad = fullyCapableDevice(dropFeatures = setOf("vk11.storageBuffer16BitAccess"))
        val good = fullyCapableDevice()
        assertTrue(DxvkRequirements.missing(DxvkFlavorIds.DXVK, report(bad, good)).isEmpty())
        // And when every adapter fails, the smallest miss list is what the
        // user sees.
        val missing = DxvkRequirements.missing(DxvkFlavorIds.DXVK, report(bad))
        assertTrue(missing.isNotEmpty())
    }

    @Test
    fun noAdaptersMeansDxvkFindsNothingEither() {
        val missing = DxvkRequirements.missing(DxvkFlavorIds.DXVK, report())
        assertEquals(1, missing.size)
        assertTrue(missing.single().contains("no Vulkan adapter"))
    }
}
