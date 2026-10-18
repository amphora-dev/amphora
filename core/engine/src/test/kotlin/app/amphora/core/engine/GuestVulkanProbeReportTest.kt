package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser contract for `amphora-vkprobe.exe` output — pinned against the
 * exact JSON shape `scripts/guest-vkprobe/vkprobe.c` emits.
 */
class GuestVulkanProbeReportTest {

    private val stdout =
        """
        box64 startup noise...
        VKPROBE_JSON_BEGIN
        {"vkprobe":1,"surfaceInstanceCreate":0,"instanceCreate":0,
         "instanceExtensions":["VK_KHR_surface","VK_KHR_win32_surface","VK_KHR_android_surface"],
         "enumerateDevices":0,
         "devices":[{
           "deviceName":"Wrapper(Adreno (TM) 830)","apiVersion":4206844,"apiVersionText":"1.3.284",
           "driverVersion":2147745792,"vendorID":20803,"deviceID":4295062017,"deviceType":1,
           "driver":{"id":6,"name":"qualcomm","info":"v@0690.0","conformance":"1.3.6.0"},
           "vk11Props":{"subgroupSize":64,"subgroupSupportedOperations":511,"subgroupSupportedStages":31},
           "vk13Props":{"maxSubgroupSize":128,"maxInlineUniformBlockSize":1024},
           "limits":{"maxImageDimension1D":16384,"maxPushConstantsSize":256,
                     "maxSamplerLodBias":16.0,"maxComputeWorkGroupSize":[1024,1024,64]},
           "features":{
             "core":{"depthBiasClamp":1,"samplerAnisotropy":1,"textureCompressionBC":1},
             "vk11":{"storageBuffer16BitAccess":1,"shaderDrawParameters":1},
             "vk12":{"timelineSemaphore":1,"scalarBlockLayout":0},
             "extRobustness2":{"robustBufferAccess2":1,"nullDescriptor":1}},
           "extensions":["VK_KHR_swapchain","VK_KHR_driver_properties"],
           "queueFamilies":[{"flags":15,"count":3},{"flags":2,"count":1}]}]}
        VKPROBE_JSON_END
        box64 shutdown noise...
        """.trimIndent()

    @Test
    fun extractsAndParsesTheProbeDocument() {
        val report = GuestVulkanProbeReport.extract(stdout)!!
        assertTrue(report.ok)
        assertEquals(0, report.instanceCreate)
        assertEquals(3, report.instanceExtensions.size)
        val device = report.devices.single()
        assertEquals("Wrapper(Adreno (TM) 830)", device.deviceName)
        assertEquals("Vulkan 1.3", device.apiLabel)
        assertEquals(20803, device.vendorId)
        assertEquals("qualcomm", device.driverName)
        assertTrue(device.feature("vk11.storageBuffer16BitAccess"))
        assertFalse(device.feature("vk12.scalarBlockLayout"))
        assertTrue(device.feature("extRobustness2.nullDescriptor"))
        assertEquals(256L, device.limit("maxPushConstantsSize"))
        // Float and array limits are not scalar requirement inputs.
        assertEquals(0L, device.limit("maxComputeWorkGroupSize"))
        assertTrue(device.hasGraphicsComputeQueue())
        assertEquals(setOf("VK_KHR_swapchain", "VK_KHR_driver_properties"), device.extensions)
    }

    @Test
    fun missingMarkersMeanNoReport() {
        assertNull(GuestVulkanProbeReport.extract("box64: wine crashed before JSON"))
        assertNull(GuestVulkanProbeReport.extract("VKPROBE_JSON_BEGIN\n{truncated"))
    }

    @Test
    fun theEarlyExitDocumentsStillParse() {
        // vkprobe.c emits this when vkCreateInstance fails both ways.
        val out = "VKPROBE_JSON_BEGIN\n" +
            "{\"vkprobe\":1,\"surfaceInstanceCreate\":-9,\"instanceCreate\":-3,\"devices\":[]}" +
            "\nVKPROBE_JSON_END\n"
        val report = GuestVulkanProbeReport.extract(out)!!
        assertFalse(report.ok)
        assertTrue(report.devices.isEmpty())
        assertEquals(-3, report.instanceCreate)
    }
}
