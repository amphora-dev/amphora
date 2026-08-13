package app.amphora.core.engine

import android.os.Build
import java.io.File

/**
 * Locates the device's vendor Vulkan HAL for the [GraphicsDriverIds.LEEGAO] path.
 *
 * The guest wrapper opens this library itself through an isolated adrenotools
 * namespace rather than going through the platform loader, so it needs the file
 * by path. Android names the HAL after a build property that differs across
 * vendors (`vulkan.kirin990.so`, `vulkan.mali.so`, `vulkan.exynos9820.so`), and
 * `Build.HARDWARE` only happens to match on some of them — so treat the property
 * as a hint and fall back to whatever the HAL directory actually contains.
 *
 * Having no HAL here is a normal answer, not a failure: emulators and software
 * renderers legitimately ship none, and those devices belong on
 * [GraphicsDriverIds.SYSTEM].
 */
object VendorVulkanHal {
    /** Rejects names that would need quoting once they reach the adrenotools env. */
    private val SAFE_NAME = Regex("vulkan\\.[A-Za-z0-9._-]+\\.so")

    private val HAL_DIRS = listOf("/vendor/lib64/hw", "/system/lib64/hw")

    fun find(): File? = find(HAL_DIRS.map(::File), buildHints())

    fun isAvailable(): Boolean = find() != null

    internal fun find(halDirs: List<File>, hints: List<String>): File? {
        for (dir in halDirs) {
            val candidates =
                dir
                    .listFiles()
                    ?.asSequence()
                    ?.filter { it.isFile && SAFE_NAME.matches(it.name) }
                    ?.associateBy { it.name }
                    .orEmpty()
            if (candidates.isEmpty()) continue

            for (hint in hints) {
                candidates["vulkan.$hint.so"]?.let { return it }
            }
            // `vulkan.default.so` is usually a stub or a symlink to the real HAL;
            // an explicitly named sibling is the better guess when both exist.
            return candidates
                .entries
                .sortedBy { it.key }
                .firstOrNull { it.key != "vulkan.default.so" }
                ?.value
                ?: candidates["vulkan.default.so"]
        }
        return null
    }

    private fun buildHints(): List<String> = listOf(Build.HARDWARE, Build.BOARD)
        .map { it.orEmpty().trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}
