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
        assertTrue(s.accelerated)
        assertTrue(s.detail.contains("first starts"))
    }

    @Test
    fun openGlAccelerated() {
        val s = OpenGlBackendStatus.of(zinkBlockers = emptyList(), probed = true)
        assertTrue(s.accelerated)
        assertTrue(s.detail.contains("Zink"))
    }

    @Test
    fun openGlBlockedNamesExtensions() {
        val s =
            OpenGlBackendStatus.of(
                zinkBlockers = listOf("VK_KHR_dynamic_rendering", "VK_EXT_robustness2"),
                probed = true,
            )
        assertFalse(s.accelerated)
        assertTrue(s.detail.contains("VK_KHR_dynamic_rendering"))
        assertTrue(s.detail.contains("VK_EXT_robustness2"))
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
