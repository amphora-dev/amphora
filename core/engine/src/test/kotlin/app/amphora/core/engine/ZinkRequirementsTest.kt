package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ZinkRequirementsTest {
    /** Everything Mesa 26.2.0 zink asks for, on a Vulkan 1.1 device that advertises it all. */
    // No VK_KHR_imageless_framebuffer: 26.2.0 dropped its required=True.
    private val complete = GuestVulkanDevice(
        deviceName = "fixture",
        apiVersion = GuestVulkanDevice.apiVersion(1, 1),
        vendorId = 0,
        deviceId = 0,
        deviceType = 1,
        driverName = "",
        driverInfo = "",
        driverId = 0,
        conformance = "",
        features = mapOf("extRobustness2.nullDescriptor" to true),
        extensions = setOf(
            "VK_KHR_swapchain",
            "VK_KHR_maintenance1",
            "VK_KHR_maintenance5",
            "VK_KHR_create_renderpass2",
            "VK_KHR_dynamic_rendering",
            "VK_KHR_descriptor_update_template",
            "VK_KHR_timeline_semaphore",
            "VK_EXT_robustness2",
        ),
        limits = emptyMap(),
        queueFamilies = emptyList(),
    )

    @Test
    fun completeDeviceHasNoBlockers() {
        assertEquals(emptyList<String>(), ZinkRequirements.missing(complete))
    }

    @Test
    fun maliR34BlobBlocksOnDynamicRenderingAndNullDescriptor() {
        val maliR34 = complete.copy(
            extensions = complete.extensions - setOf("VK_KHR_dynamic_rendering", "VK_EXT_robustness2"),
            features = emptyMap(),
        )
        assertEquals(
            listOf("extension VK_KHR_dynamic_rendering", "feature extRobustness2.nullDescriptor"),
            ZinkRequirements.missing(maliR34),
        )
    }

    @Test
    fun maintenance5IsRequiredBelowVulkan14() {
        val noMaint5 = complete.copy(extensions = complete.extensions - "VK_KHR_maintenance5")
        assertEquals(listOf("extension VK_KHR_maintenance5"), ZinkRequirements.missing(noMaint5))
    }

    /** A core version at or above the promotion waives the advertised name, as `have_vulkanXY` does. */
    @Test
    fun corePromotionWaivesTheExtensionName() {
        val bare = complete.copy(
            apiVersion = GuestVulkanDevice.apiVersion(1, 4),
            extensions = setOf("VK_KHR_timeline_semaphore", "VK_EXT_robustness2"),
        )
        assertEquals(emptyList<String>(), ZinkRequirements.missing(bare))
        assertEquals(
            listOf("extension VK_KHR_maintenance5"),
            ZinkRequirements.missing(bare.copy(apiVersion = GuestVulkanDevice.apiVersion(1, 3))),
        )
    }

    @Test
    fun nullDescriptorMustBeTrue() {
        val off = complete.copy(features = mapOf("extRobustness2.nullDescriptor" to false))
        assertEquals(listOf("feature extRobustness2.nullDescriptor"), ZinkRequirements.missing(off))
    }

    /** `zink_screen.c:3574`: the extension or the Vulkan 1.2 feature, either one. */
    @Test
    fun timelineSemaphoreFromExtensionOrVulkan12Feature() {
        val noExt = complete.copy(extensions = complete.extensions - "VK_KHR_timeline_semaphore")
        assertEquals(
            listOf("extension VK_KHR_timeline_semaphore or feature vk12.timelineSemaphore"),
            ZinkRequirements.missing(noExt),
        )
        val viaFeature = noExt.copy(features = noExt.features + ("vk12.timelineSemaphore" to true))
        assertEquals(emptyList<String>(), ZinkRequirements.missing(viaFeature))
    }
}
