package app.amphora.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings rendering of each guest-capability state: supported, unsupported
 * with named missing items, and unknown (no launch has probed this driver).
 */
class GuestCapabilityStatusTest {

    @Test
    fun openGlUnknownUntilProbed() {
        val s = OpenGlBackendStatus.of(zinkBlockers = emptyList(), probed = false)
        assertEquals(OpenGlBackendStatus.AVAILABILITY, s.availability)
        assertTrue(s.availability.contains("Mesa 26.2.0"))
        assertTrue(s.availability.contains("softpipe"))
        assertNull(s.zinkReady)
        assertTrue(s.zinkDetail.contains("first starts"))
    }

    @Test
    fun openGlRunsOnZinkWhenRequirementsAreMet() {
        val s = OpenGlBackendStatus.of(zinkBlockers = emptyList(), probed = true)
        assertEquals(OpenGlBackendStatus.AVAILABILITY, s.availability)
        assertEquals(true, s.zinkReady)
        assertTrue(s.zinkDetail.contains("meets every"))
        assertTrue(s.zinkDetail.contains("on the GPU"))
    }

    @Test
    fun openGlZinkBlockedNamesMissingItemsAndSoftpipe() {
        val s =
            OpenGlBackendStatus.of(
                zinkBlockers = listOf("extension VK_KHR_dynamic_rendering", "feature extRobustness2.nullDescriptor"),
                probed = true,
            )
        assertEquals(OpenGlBackendStatus.AVAILABILITY, s.availability)
        assertEquals(false, s.zinkReady)
        assertTrue(s.zinkDetail.contains("VK_KHR_dynamic_rendering"))
        assertTrue(s.zinkDetail.contains("extRobustness2.nullDescriptor"))
        assertTrue(s.zinkDetail.contains("softpipe"))
    }

    @Test
    fun d3d12UnknownUntilProbed() {
        val s = D3d12BackendStatus.of(missing = null)
        assertNull(s.supported)
        assertTrue(s.detail.contains("first starts"))
    }

    @Test
    fun d3d12Supported() {
        val s = D3d12BackendStatus.of(missing = emptyList())
        assertEquals(true, s.supported)
        assertTrue(s.detail.contains("supported"))
    }

    @Test
    fun d3d12UnsupportedNamesMissing() {
        val s =
            D3d12BackendStatus.of(
                missing = listOf("dxgi: resolved DXVK 'sarek' rejects D3D12 devices"),
            )
        assertEquals(false, s.supported)
        assertTrue(s.detail.contains("sarek"))
    }

    @Test
    fun dxvkImpactSaysDecidedOnFirstStartWhenUnknown() {
        // Unprobed auto resolves to its no-report fallback; that is not a choice.
        val status =
            DxvkFlavorStatus.of(
                selected = DxvkFlavorSetting.AUTO,
                resolvedId = "sarek",
                d3d89Id = null,
                probe =
                DxvkProbeInfo(
                    deviceName = null,
                    apiLabel = null,
                    failure = null,
                    missing = emptyList(),
                    dxvkSupported = null,
                ),
            )
        assertTrue(status.impact.contains("first starts"))
        assertFalse(status.impact.contains("Sarek"))
    }

    @Test
    fun dxvkImpactShowsProbeFailure() {
        val status =
            DxvkFlavorStatus.of(
                selected = DxvkFlavorSetting.AUTO,
                resolvedId = "dxvk",
                d3d89Id = null,
                probe =
                DxvkProbeInfo(
                    deviceName = null,
                    apiLabel = null,
                    failure = "no probe output within 30000ms",
                    missing = emptyList(),
                    dxvkSupported = null,
                ),
            )
        assertTrue(status.impact.contains("failed"))
    }
}
