package app.amphora.core.engine

import app.amphora.core.content.model.ContentComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DxvkFlavorIdsTest {
    private fun deviceMeeting(requirements: DxvkRequirementSet): GuestVulkanDevice = GuestVulkanDevice(
        deviceName = "Fixture GPU",
        apiVersion = requirements.minApiVersion.coerceAtLeast(GuestVulkanDevice.apiVersion(1, 4)),
        vendorId = 0,
        deviceId = 0,
        deviceType = 2,
        driverName = "fixture",
        driverInfo = "",
        driverId = 0,
        conformance = "",
        features = (requirements.requiredFeatures + "extDescriptorHeap.descriptorHeap")
            .associateWith { true },
        extensions = requirements.requiredExtensions + "VK_KHR_swapchain",
        limits = mapOf("maxPushConstantsSize" to 256L),
        queueFamilies = listOf(
            GuestVulkanDevice.QueueFamily(
                flags = GuestVulkanDevice.QUEUE_GRAPHICS_OR_COMPUTE,
                count = 1,
            ),
        ),
    )

    private fun report(vararg devices: GuestVulkanDevice) = GuestVulkanProbeReport(
        surfaceInstanceCreate = 0,
        instanceCreate = 0,
        instanceExtensions = emptySet(),
        enumerateDevices = 0,
        devices = devices.toList(),
    )

    @Test
    fun autoPicksTheBestBuildWhoseRequirementsAreAllMet() {
        assertEquals(
            DxvkFlavorIds.DXVK,
            DxvkFlavorIds.resolve(
                DxvkFlavorIds.AUTO,
                report(deviceMeeting(DxvkRequirements.DXVK_3_0_2_GPLASYNC)),
            ),
        )
    }

    @Test
    fun autoFallsToSarekWhenDxvkMissesARequirement() {
        // Turnip on Adreno 630: Vulkan 1.3 but no storageBuffer16BitAccess —
        // the case the old host version probe could not see.
        val device = deviceMeeting(DxvkRequirements.DXVK_3_0_2_GPLASYNC).copy(
            apiVersion = GuestVulkanDevice.apiVersion(1, 3),
            features = DxvkRequirements.DXVK_3_0_2_GPLASYNC.requiredFeatures
                .associateWith { it != "vk11.storageBuffer16BitAccess" },
        )
        assertEquals(DxvkFlavorIds.SAREK, DxvkFlavorIds.resolve(DxvkFlavorIds.AUTO, report(device)))
    }

    @Test
    fun aFailedProbeFallsBackToSarek() {
        // null report = probe never ran / timed out / crashed — Sarek is the
        // same floor DXVK itself reaches when it finds no adapter.
        assertEquals(DxvkFlavorIds.SAREK, DxvkFlavorIds.resolve(DxvkFlavorIds.AUTO, null))
        assertEquals(DxvkFlavorIds.SAREK, DxvkFlavorIds.resolve(DxvkFlavorIds.AUTO, report()))
    }

    @Test
    fun anExplicitPickWinsEvenWhenTheDeviceCannotRunIt() {
        val weak = report()
        assertEquals(DxvkFlavorIds.DXVK, DxvkFlavorIds.resolve(DxvkFlavorIds.DXVK, weak))
        assertEquals(DxvkFlavorIds.SAREK, DxvkFlavorIds.resolve(DxvkFlavorIds.SAREK, weak))
    }

    @Test
    fun qualcommProprietaryAutoResolvingToStockSplitsD3d89() {
        // The Qualcomm stack is what the wrapper ICD wraps and what the System
        // loader loads on Adreno — both get the split.
        for (driver in listOf(GraphicsDriverIds.WRAPPER, GraphicsDriverIds.SYSTEM)) {
            assertEquals(
                "driver=$driver",
                DxvkFlavorIds.D3D89_FLAVOR,
                DxvkFlavorIds.d3d89Flavor(
                    id = DxvkFlavorIds.AUTO,
                    resolvedFlavor = DxvkFlavorIds.DXVK,
                    effectiveDriverId = driver,
                    isAdreno = true,
                ),
            )
        }
    }

    @Test
    fun turnipLeegaoAndExplicitFlavorsNeverSplit() {
        // Turnip compiles DXVK's fixed-function shaders fine — no split.
        assertNull(
            DxvkFlavorIds.d3d89Flavor(
                id = DxvkFlavorIds.AUTO,
                resolvedFlavor = DxvkFlavorIds.DXVK,
                effectiveDriverId = GraphicsDriverIds.TURNIP_BALANCED,
                isAdreno = true,
            ),
        )
        // Leegao is only ever the non-Adreno path.
        assertNull(
            DxvkFlavorIds.d3d89Flavor(
                id = DxvkFlavorIds.AUTO,
                resolvedFlavor = DxvkFlavorIds.DXVK,
                effectiveDriverId = GraphicsDriverIds.LEEGAO,
                isAdreno = false,
            ),
        )
        // Explicit picks are meant to be pure, broken or not.
        for (flavor in listOf(DxvkFlavorIds.DXVK, DxvkFlavorIds.SAREK)) {
            assertNull(
                "flavor=$flavor",
                DxvkFlavorIds.d3d89Flavor(
                    id = flavor,
                    resolvedFlavor = flavor,
                    effectiveDriverId = GraphicsDriverIds.WRAPPER,
                    isAdreno = true,
                ),
            )
        }
    }

    @Test
    fun theSplitOnlyArmsOffStockDxvkOnAdreno() {
        // Auto resolving to Sarek (sub-1.3 device) leaves nothing to split off.
        assertNull(
            DxvkFlavorIds.d3d89Flavor(
                id = DxvkFlavorIds.AUTO,
                resolvedFlavor = DxvkFlavorIds.SAREK,
                effectiveDriverId = GraphicsDriverIds.WRAPPER,
                isAdreno = true,
            ),
        )
        // Non-Adreno hardware never runs the Qualcomm proprietary stack.
        assertNull(
            DxvkFlavorIds.d3d89Flavor(
                id = DxvkFlavorIds.AUTO,
                resolvedFlavor = DxvkFlavorIds.DXVK,
                effectiveDriverId = GraphicsDriverIds.WRAPPER,
                isAdreno = false,
            ),
        )
    }

    @Test
    fun normalizeAndComponentMapEveryStoredValue() {
        assertEquals(DxvkFlavorIds.AUTO, DxvkFlavorIds.normalize(null))
        assertEquals(DxvkFlavorIds.AUTO, DxvkFlavorIds.normalize("something-else"))
        assertEquals(DxvkFlavorIds.SAREK, DxvkFlavorIds.normalize(DxvkFlavorIds.SAREK))
        assertEquals(ContentComponent.DXVK_SAREK, DxvkFlavorIds.component(DxvkFlavorIds.SAREK))
        assertEquals(ContentComponent.DXVK, DxvkFlavorIds.component(DxvkFlavorIds.DXVK))
        assertEquals(ContentComponent.DXVK, DxvkFlavorIds.component(DxvkFlavorIds.AUTO))
    }
}
