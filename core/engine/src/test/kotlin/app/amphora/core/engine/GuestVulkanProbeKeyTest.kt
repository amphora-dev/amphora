package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the probe cache key: every input that can change what the guest
 * enumerates must re-probe, and nothing else may. The runner itself needs a
 * device; the key is pure.
 */
class GuestVulkanProbeKeyTest {

    private fun inputs(
        driver: String = GraphicsDriverIds.WRAPPER,
        pkg: String = "wrapper",
        wine: String = "Proton-11.0-2fd246cfc-x86_64-0",
        box64: String = "0.4.5-0db8df775-0",
        layer: String = GuestVulkanProbe.LAYER_NAME,
        exeHash: String = "abc123",
        env: Map<String, String> = mapOf(
            "VK_ICD_FILENAMES" to "/img/usr/share/vulkan/icd.d/wrapper_icd.aarch64.json",
            "WRAPPER_EMULATE_BCN" to "3",
            "WRAPPER_VK_VERSION" to "1.4",
            "LD_PRELOAD" to "/lib/libamphora_wsi.so",
            "AMPHORA_WINEANDROID" to "1",
            // Inert for the guest view — must not re-probe on change.
            "DXVK_HUD" to "1",
        ),
    ) = GuestVulkanProbe.Inputs(
        effectiveDriverId = driver,
        driverPackageVersion = pkg,
        wineVersion = wine,
        box64Version = box64,
        layerName = layer,
        appVersionCode = 1,
        probeExeHash = exeHash,
        env = env,
    )

    @Test
    fun identicalInputsShareAKey() {
        assertEquals(inputs().key(), inputs().key())
    }

    @Test
    fun everyGuestViewInputInvalidates() {
        val base = inputs()
        for ((label, changed) in listOf(
            "driver" to inputs(driver = GraphicsDriverIds.TURNIP_BALANCED),
            "driver package" to inputs(pkg = "WN-Turnip-1.06-b"),
            "wine" to inputs(wine = "Proton-11.0-fffffffff-x86_64-0"),
            "box64" to inputs(box64 = "0.4.6-0db8df775-0"),
            "layer" to inputs(layer = ""),
            "probe exe" to inputs(exeHash = "def456"),
            "guest env" to inputs(env = inputs().env + ("WRAPPER_EXTENSION_BLACKLIST" to "VK_EXT_x")),
        )) {
            assertNotEquals(label, base.key(), changed.key())
        }
    }

    @Test
    fun inertEnvDoesNotInvalidate() {
        assertEquals(
            inputs().key(),
            inputs(env = inputs().env + mapOf("DXVK_HUD" to "full", "MESA_LOADER_DRIVER_OVERRIDE" to "zink"))
                .key(),
        )
    }
}
