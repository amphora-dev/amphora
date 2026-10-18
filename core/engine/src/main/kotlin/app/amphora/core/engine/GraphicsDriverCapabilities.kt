package app.amphora.core.engine

import android.content.Context
import android.util.Log
import com.winlator.cmod.runtime.system.GPUInformation
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers [GraphicsDriverIds] and [DxvkFlavorIds] questions for *this* device.
 *
 * DXVK flavor resolution is decided by the guest probe ([GuestVulkanProbe]):
 * the adapter enumerated inside Wine through the session's own Vulkan stack,
 * matched against each build's full requirement table. Host-side Vulkan
 * probing remains only for the things that genuinely are host-side — the
 * platform driver the wrapper will talk to (zink blockers) and the
 * `WRAPPER_VK_VERSION` clamp inside the preparer.
 *
 * Everything is cached for the process — neither the host probe nor the probe
 * verdict can change while the app runs, and the settings screen asks on every
 * recomposition.
 */
@Singleton
class GraphicsDriverCapabilities @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val guestVulkanProbe: GuestVulkanProbe,
) {
    private val isAdreno: Boolean by lazy { GPUInformation.isAdrenoGPU(context) }
    private val hasVendorHal: Boolean by lazy { VendorVulkanHal.isAvailable() }
    private val zinkBlockersByHostDriver = ConcurrentHashMap<String, List<String>>()

    /** Driver ids worth offering here, best first. */
    fun availableDriverIds(): List<String> = GraphicsDriverIds.availableDrivers(isAdreno, hasVendorHal)

    /** True on Qualcomm Adreno — gates the D3D8/9 Qualcomm split. */
    fun isAdrenoDevice(): Boolean = isAdreno

    /** What [storedDriverId] actually resolves to at launch. */
    fun effectiveDriverId(storedDriverId: String?): String =
        GraphicsDriverIds.resolveEffectiveDriver(storedDriverId, isAdreno, hasVendorHal)

    /**
     * [ZinkRequirements] this device cannot meet on the backend [storedDriverId]
     * runs on. Empty means guest OpenGL reaches the GPU through zink; anything
     * else means Mesa falls back to its software rasterizer.
     */
    fun zinkBlockers(storedDriverId: String?): List<String> {
        val hostDriver = GraphicsDriverIds.resolveHostDriver(storedDriverId, isAdreno, hasVendorHal)
        zinkBlockersByHostDriver[hostDriver]?.let { return it }
        val extensions =
            runCatching { GPUInformation.enumerateExtensions(hostDriver, context).orEmpty().toList() }
                .onFailure { Log.w(TAG, "Device extension probe failed for '$hostDriver'", it) }
                .getOrDefault(emptyList())
        if (extensions.isEmpty()) {
            Log.w(TAG, "No device extensions reported by '$hostDriver'; assuming zink can start")
        }
        val blockers = ZinkRequirements.missingExtensions(extensions)
        zinkBlockersByHostDriver[hostDriver] = blockers
        return blockers
    }

    /**
     * The last guest-probe verdict, or null before the first probe (no
     * container prepared yet). The verdict carries the driver id it was
     * probed under — a verdict for another driver is stale context for
     * display, not evidence.
     */
    fun guestProbeVerdict(): GuestVulkanProbe.Verdict? = guestVulkanProbe.cached()

    /**
     * The DXVK build [storedFlavorId] resolves to for [storedDriverId]. When
     * the cached verdict was probed under a different driver its report still
     * applies only if the same stack would run; a stale verdict is better
     * than none (the container manager re-probes whenever the key changes).
     */
    fun effectiveDxvkFlavor(storedFlavorId: String?, storedDriverId: String?): String = DxvkFlavorIds.resolve(
        id = storedFlavorId,
        report = guestVulkanProbe.cached()?.report,
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
    fun missingDxvkRequirements(storedFlavorId: String?): List<String>? {
        val report = guestVulkanProbe.cached()?.report ?: return null
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
