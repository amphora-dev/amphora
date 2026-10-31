package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Part C consumers of the guest probe report, pinned against the real Y700
 * probe captures (`src/test/resources/vkprobe/`):
 *
 * - [Vkd3dRequirements] — the device checks that make vkd3d-proton 3.0.1
 *   refuse a device (`E_INVALIDARG`/api floor), plus the Sarek-dxgi D3D12
 *   block.
 * - [ZinkRequirements] — blockers must come from the *guest* report
 *   (extensions and features), not a host-side enumeration.
 * - `core.textureCompressionBC` — the BCn "auto" status source.
 */
class GuestCapabilityConsumersTest {

    private fun fixture(name: String): GuestVulkanProbeReport {
        val text =
            javaClass.getResource("/vkprobe/vkprobe-$name.txt")!!.readText()
        return GuestVulkanProbeReport.extract(text)!!
    }

    private fun device(name: String) = fixture(name).devices.first()

    // -- vkd3d-proton 3.0.1 (VKD3D-3.0.1-3b10bd7a7) -------------------------

    @Test
    fun vkd3dAcceptsAllThreeY700Adapters() {
        for (name in listOf("wrapper", "turnip", "system")) {
            assertEquals(
                "$name must satisfy every vkd3d check",
                emptyList<String>(),
                Vkd3dRequirements.missing(device(name)),
            )
        }
    }

    @Test
    fun vkd3dRejectsVulkan12Adapter() {
        val d = device("wrapper").copy(apiVersion = GuestVulkanDevice.apiVersion(1, 2))
        assertTrue(Vkd3dRequirements.missing(d).any { it.contains("below required Vulkan 1.3") })
    }

    @Test
    fun vkd3dNamesMissingFeatureAndExtension() {
        val d =
            device("wrapper").copy(
                features = device("wrapper").features - "extRobustness2.robustImageAccess2",
                extensions = device("wrapper").extensions - "VK_KHR_push_descriptor",
            )
        val missing = Vkd3dRequirements.missing(d)
        assertTrue(missing.any { it.contains("extRobustness2.robustImageAccess2") })
        assertTrue(missing.any { it.contains("VK_KHR_push_descriptor") })
    }

    @Test
    fun vkd3dRequiresGraphicsComputeQueue() {
        val d = device("wrapper").copy(queueFamilies = listOf(GuestVulkanDevice.QueueFamily(flags = 8, count = 1)))
        assertTrue(Vkd3dRequirements.missing(d).any { it.contains("queue") })
    }

    @Test
    fun sarekDxgiBlocksD3d12() {
        assertTrue(Vkd3dRequirements.dxgiBlocksD3d12(DxvkFlavorIds.SAREK))
        assertFalse(Vkd3dRequirements.dxgiBlocksD3d12(DxvkFlavorIds.DXVK))
        assertFalse(Vkd3dRequirements.dxgiBlocksD3d12(DxvkFlavorIds.AUTO))
    }

    // -- zink from the guest report ----------------------------------------

    @Test
    fun zinkHasNoBlockersOnAllThreeY700Adapters() {
        for (name in listOf("wrapper", "turnip", "system")) {
            assertEquals(
                "$name: guest adapter must satisfy Mesa 26.2.0 zink",
                emptyList<String>(),
                ZinkRequirements.missing(device(name)),
            )
        }
    }

    @Test
    fun zinkBlockersComeFromGuestReport() {
        // A device whose guest-visible list lacks the zink must-haves is
        // blocked even if the host driver would pass — the guest is what runs.
        // The Y700 adapters are Vulkan 1.3, so maintenance5 is not waived.
        val d = device("wrapper")
        val guestMissing =
            d.copy(
                extensions = d.extensions - "VK_KHR_maintenance5",
                features = d.features - "extRobustness2.nullDescriptor",
            )
        assertEquals(
            listOf("extension VK_KHR_maintenance5", "feature extRobustness2.nullDescriptor"),
            ZinkRequirements.missing(guestMissing),
        )
    }

    // -- BCn ----------------------------------------------------------------

    @Test
    fun bcnNativeFeatureReadsFromGuestReport() {
        for (name in listOf("wrapper", "turnip", "system")) {
            assertTrue("$name reports native BCn", device(name).feature("core.textureCompressionBC"))
        }
        val noBcn = device("wrapper").copy(features = device("wrapper").features - "core.textureCompressionBC")
        assertFalse(noBcn.feature("core.textureCompressionBC"))
    }
}
