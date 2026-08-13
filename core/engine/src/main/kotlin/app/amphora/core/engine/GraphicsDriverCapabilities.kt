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
 * Every probe here goes through the platform Vulkan loader, so results are cached
 * for the process — they cannot change while the app runs, and the settings
 * screen asks on every recomposition.
 */
@Singleton
class GraphicsDriverCapabilities @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val isAdreno: Boolean by lazy { GPUInformation.isAdrenoGPU(context) }
    private val hasVendorHal: Boolean by lazy { VendorVulkanHal.isAvailable() }
    private val vulkanMinorByHostDriver = ConcurrentHashMap<String, Int>()
    private val zinkBlockersByHostDriver = ConcurrentHashMap<String, List<String>>()

    /** Driver ids worth offering here, best first. */
    fun availableDriverIds(): List<String> = GraphicsDriverIds.availableDrivers(isAdreno, hasVendorHal)

    /** What [storedDriverId] actually resolves to at launch. */
    fun effectiveDriverId(storedDriverId: String?): String =
        GraphicsDriverIds.resolveEffectiveDriver(storedDriverId, isAdreno, hasVendorHal)

    /**
     * Vulkan minor version reported by the backend [storedDriverId] will run on,
     * or null when the probe fails. Probing the *host* driver is deliberate: it is
     * the one the guest ICD ends up wrapping, and it is what
     * [XServerWineSessionPreparer] already clamps the container's Vulkan version to.
     */
    fun vulkanMinorVersion(storedDriverId: String?): Int? {
        val hostDriver = GraphicsDriverIds.resolveHostDriver(storedDriverId, isAdreno, hasVendorHal)
        vulkanMinorByHostDriver[hostDriver]?.let { return it }
        val reported =
            runCatching { GPUInformation.getVulkanVersion(hostDriver, context) }
                .onFailure { Log.w(TAG, "Vulkan version probe failed for '$hostDriver'", it) }
                .getOrNull()
        val minor = reported?.split('.')?.getOrNull(1)?.toIntOrNull()
        if (minor == null) {
            Log.w(TAG, "Unreadable Vulkan version '$reported' from '$hostDriver'")
            return null
        }
        vulkanMinorByHostDriver[hostDriver] = minor
        return minor
    }

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

    /** The DXVK build [storedFlavorId] resolves to for [storedDriverId]. */
    fun effectiveDxvkFlavor(storedFlavorId: String?, storedDriverId: String?): String = DxvkFlavorIds.resolve(
        id = storedFlavorId,
        vulkanMinor = vulkanMinorVersion(storedDriverId),
        usesLeegao = effectiveDriverId(storedDriverId) == GraphicsDriverIds.LEEGAO,
    )

    private companion object {
        const val TAG = "GraphicsDriverCaps"
    }
}
