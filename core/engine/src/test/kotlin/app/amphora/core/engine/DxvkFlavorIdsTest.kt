package app.amphora.core.engine

import app.amphora.core.content.model.ContentComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DxvkFlavorIdsTest {
    @Test
    fun autoFollowsTheDeviceVulkanLevelRatherThanItsDriver() {
        assertEquals(
            DxvkFlavorIds.SAREK,
            DxvkFlavorIds.resolve(DxvkFlavorIds.AUTO, vulkanMinor = 1, usesLeegao = true),
        )
        // A Vulkan 1.3 vendor HAL on the Leegao path has no reason to give up DXVK 3.
        assertEquals(
            DxvkFlavorIds.DXVK,
            DxvkFlavorIds.resolve(DxvkFlavorIds.AUTO, vulkanMinor = 3, usesLeegao = true),
        )
        // And an Adreno stuck on Vulkan 1.1 needs Sarek just as much as a Mali does.
        assertEquals(
            DxvkFlavorIds.SAREK,
            DxvkFlavorIds.resolve(DxvkFlavorIds.AUTO, vulkanMinor = 1, usesLeegao = false),
        )
    }

    @Test
    fun aFailedProbeFallsBackToWhatTheDriverImplies() {
        assertEquals(
            DxvkFlavorIds.SAREK,
            DxvkFlavorIds.resolve(DxvkFlavorIds.AUTO, vulkanMinor = null, usesLeegao = true),
        )
        assertEquals(
            DxvkFlavorIds.DXVK,
            DxvkFlavorIds.resolve(DxvkFlavorIds.AUTO, vulkanMinor = null, usesLeegao = false),
        )
    }

    @Test
    fun anExplicitPickWinsEvenWhenTheDeviceCannotRunIt() {
        assertEquals(
            DxvkFlavorIds.DXVK,
            DxvkFlavorIds.resolve(DxvkFlavorIds.DXVK, vulkanMinor = 1, usesLeegao = true),
        )
        assertEquals(
            DxvkFlavorIds.SAREK,
            DxvkFlavorIds.resolve(DxvkFlavorIds.SAREK, vulkanMinor = 3, usesLeegao = false),
        )
    }

    @Test
    fun onlyDxvkOnASubDot3DeviceCountsAsUnsupported() {
        assertTrue(DxvkFlavorIds.isUnsupported(DxvkFlavorIds.DXVK, vulkanMinor = 2))
        assertFalse(DxvkFlavorIds.isUnsupported(DxvkFlavorIds.DXVK, vulkanMinor = 3))
        // An unknown Vulkan level is not evidence of a problem.
        assertFalse(DxvkFlavorIds.isUnsupported(DxvkFlavorIds.DXVK, vulkanMinor = null))
        // Sarek runs anywhere; it is only ever a downgrade, never a failure.
        assertFalse(DxvkFlavorIds.isUnsupported(DxvkFlavorIds.SAREK, vulkanMinor = 3))
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
