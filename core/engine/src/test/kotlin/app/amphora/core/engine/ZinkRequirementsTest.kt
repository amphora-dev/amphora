package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ZinkRequirementsTest {
    /** Everything zink asks for, as an Adreno blob or Turnip reports it. */
    private val complete = listOf(
        "VK_KHR_swapchain",
        "VK_KHR_maintenance1",
        "VK_KHR_create_renderpass2",
        "VK_KHR_imageless_framebuffer",
        "VK_KHR_descriptor_update_template",
        "VK_KHR_dynamic_rendering",
        "VK_EXT_robustness2",
        "VK_EXT_custom_border_color",
    )

    @Test
    fun completeDeviceHasNoBlockers() {
        assertEquals(emptyList<String>(), ZinkRequirements.missingExtensions(complete))
    }

    @Test
    fun maliR34BlobBlocksOnDynamicRenderingAndRobustness2() {
        val maliR34 = complete - setOf("VK_KHR_dynamic_rendering", "VK_EXT_robustness2")
        assertEquals(
            listOf("VK_KHR_dynamic_rendering", "VK_EXT_robustness2"),
            ZinkRequirements.missingExtensions(maliR34),
        )
    }

    /** A failed probe reports nothing; that must not be read as "supports nothing". */
    @Test
    fun emptyProbeReportsNoBlockers() {
        assertEquals(emptyList<String>(), ZinkRequirements.missingExtensions(emptyList()))
    }
}
