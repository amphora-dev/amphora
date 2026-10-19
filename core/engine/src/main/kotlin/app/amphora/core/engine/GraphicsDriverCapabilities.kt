package app.amphora.core.engine

import android.content.Context
import com.winlator.cmod.runtime.system.GPUInformation
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers [GraphicsDriverIds] and [DxvkFlavorIds] questions for *this* device.
 *
 * DXVK flavor resolution is decided by the guest probe ([GuestVulkanProbe]):
 * the adapter enumerated inside Wine through the session's own Vulkan stack,
 * matched against each build's full requirement table. The same guest report
 * answers zink, D3D12 (vkd3d-proton) and BCn questions — the device the guest
 * sees is the device those runtimes run on. Host-side Vulkan probing remains
 * only for the `WRAPPER_VK_VERSION` clamp inside the preparer.
 */
@Singleton
class GraphicsDriverCapabilities @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val guestVulkanProbe: GuestVulkanProbe,
) {
    private val isAdreno: Boolean by lazy { GPUInformation.isAdrenoGPU(context) }
    private val hasVendorHal: Boolean by lazy { VendorVulkanHal.isAvailable() }

    /** Driver ids worth offering here, best first. */
    fun availableDriverIds(): List<String> = GraphicsDriverIds.availableDrivers(isAdreno, hasVendorHal)

    /** True on Qualcomm Adreno — gates the D3D8/9 Qualcomm split. */
    fun isAdrenoDevice(): Boolean = isAdreno

    /** What [storedDriverId] actually resolves to at launch. */
    fun effectiveDriverId(storedDriverId: String?): String =
        GraphicsDriverIds.resolveEffectiveDriver(storedDriverId, isAdreno, hasVendorHal)

    /**
     * The guest adapter of the newest verdict probed under the driver
     * [storedDriverId] resolves to — what every guest-side capability
     * question (zink, vkd3d, BCn) is answered from. Null until that driver
     * has been probed.
     */
    private fun guestDeviceFor(storedDriverId: String?): GuestVulkanDevice? =
        guestProbeVerdict(storedDriverId)?.report?.devices?.firstOrNull()

    /**
     * [ZinkRequirements] the guest-visible adapter cannot meet, per the last
     * probe verdict. Empty means guest OpenGL reaches the GPU through zink —
     * or that no verdict exists yet (same "assume it works until proven"
     * posture the old host probe took on a failed enumeration).
     */
    fun zinkBlockers(storedDriverId: String?): List<String> {
        val device = guestDeviceFor(storedDriverId) ?: return emptyList()
        return ZinkRequirements.missingExtensions(device.extensions)
    }

    /**
     * What the guest report says about D3D12 through the pinned
     * vkd3d-proton build: null until probed, else the missing requirement
     * names (empty = supported). Sarek's dxgi additionally blocks D3D12
     * regardless of the adapter — see [Vkd3dRequirements.dxgiBlocksD3d12].
     */
    fun vkd3dMissing(storedDriverId: String?, storedFlavorId: String?): List<String>? {
        val device = guestDeviceFor(storedDriverId)
        // Unprobed `auto` resolves to its no-report fallback, not a decision.
        if (device == null && DxvkFlavorIds.normalize(storedFlavorId) == DxvkFlavorIds.AUTO) return null
        val resolved = effectiveDxvkFlavor(storedFlavorId, storedDriverId)
        if (Vkd3dRequirements.dxgiBlocksD3d12(resolved)) {
            return listOf("dxgi: resolved DXVK '$resolved' rejects D3D12 devices")
        }
        return device?.let { Vkd3dRequirements.missing(it) }
    }

    /** Native BCn (`core.textureCompressionBC`) on the guest adapter, if known. */
    fun guestNativeBcn(storedDriverId: String?): Boolean? =
        guestDeviceFor(storedDriverId)?.feature("core.textureCompressionBC")

    /**
     * Manual "Re-detect": drop every verdict probed under the driver
     * [storedDriverId] resolves to, so the next launch probes fresh
     * regardless of the failure-retry budget.
     */
    fun invalidateProbeVerdicts(storedDriverId: String?) =
        guestVulkanProbe.invalidateDriver(effectiveDriverId(storedDriverId))

    /**
     * The newest guest-probe verdict for the driver [storedDriverId] resolves
     * to, or null when that driver has not been probed yet.
     */
    fun guestProbeVerdict(storedDriverId: String?): GuestVulkanProbe.Verdict? =
        guestVulkanProbe.latestFor(effectiveDriverId(storedDriverId))

    /**
     * The DXVK build [storedFlavorId] resolves to for [storedDriverId], from
     * that driver's own verdict. Another driver's report never decides: Turnip
     * and the Qualcomm driver expose different features on the same GPU.
     * Before the first probe for the driver this is the no-report answer;
     * the launch then probes and relinks.
     */
    fun effectiveDxvkFlavor(storedFlavorId: String?, storedDriverId: String?): String = DxvkFlavorIds.resolve(
        id = storedFlavorId,
        report = guestProbeVerdict(storedDriverId)?.report,
    )

    /**
     * Requirements the guest probe found unmet, for settings.
     *
     * For an explicit `dxvk`/`sarek` pick: what that build is missing on the
     * probed adapter — what DXVK itself will reject it over. For `auto`: why
     * the best build was rejected — empty when `auto` already resolves to it,
     * or when no probe has run (callers should report the probe verdict's
     * failure instead).
     */
    fun missingDxvkRequirements(storedFlavorId: String?, storedDriverId: String?): List<String>? {
        val report = guestProbeVerdict(storedDriverId)?.report ?: return null
        val flavor = when (DxvkFlavorIds.normalize(storedFlavorId)) {
            DxvkFlavorIds.AUTO -> {
                if (DxvkFlavorIds.resolveAuto(report) == DxvkRequirements.AUTO_ORDER.first().flavorId) {
                    return emptyList()
                }
                DxvkRequirements.AUTO_ORDER.first().flavorId
            }
            DxvkFlavorIds.DXVK -> DxvkFlavorIds.DXVK
            else -> DxvkFlavorIds.SAREK
        }
        return DxvkRequirements.missing(flavor, report).takeIf { it.isNotEmpty() }
    }

    /**
     * The second DXVK build d3d8/d3d9 come from for
     * [storedFlavorId]/[storedDriverId], or null when the launch is not split.
     * See [DxvkFlavorIds.d3d89Flavor].
     */
    fun effectiveD3d89DxvkFlavor(storedFlavorId: String?, storedDriverId: String?): String? = DxvkFlavorIds.d3d89Flavor(
        id = storedFlavorId,
        resolvedFlavor = effectiveDxvkFlavor(storedFlavorId, storedDriverId),
        effectiveDriverId = effectiveDriverId(storedDriverId),
        isAdreno = isAdreno,
    )

    private companion object {
        const val TAG = "GraphicsDriverCaps"
    }
}
