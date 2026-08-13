package app.amphora.core.engine

/**
 * Selectable adrenotools driver ids for container `graphicsDriverConfig.version`.
 *
 * Which of these a device can actually run is decided by [availableDrivers] and
 * [resolveEffectiveDriver], not by the stored preference: the same default value
 * has to mean "the wrapper ICD" on Adreno and "the vendor HAL through Leegao" on
 * Mali, because most users never open the driver picker.
 */
object GraphicsDriverIds {
    const val PREFS_NAME = "amphora_graphics"
    const val PREFS_KEY_DRIVER_ID = "adrenotools_driver_id"

    /** Bundled WinNative wrapper ICD over the platform Adreno driver (Adreno default). */
    const val WRAPPER = "wrapper"

    /** Android's system Vulkan loader/ICD. Last resort for devices with no vendor HAL. */
    const val SYSTEM = "System"

    /**
     * Leegao's guest wrapper around the vendor Vulkan HAL (non-Adreno default).
     *
     * The Android host compositor still uses [SYSTEM]. The guest wrapper opens the
     * HAL itself through an isolated adrenotools namespace so imagefs libraries
     * cannot shadow vendor dependencies — the collision that made plain [SYSTEM]
     * unusable on Huawei, where the vendor layer picked up imagefs' OpenSSL.
     * Applies to any vendor HAL, Mali and Xclipse and PowerVR alike.
     */
    const val LEEGAO = "Leegao"

    /** Pre-rename value of [LEEGAO], still on disk for anyone who tried it early. */
    private const val LEGACY_LEEGAO = "Mali-Leegao"

    /**
     * Optional open-source Turnip (Balanced). Folder name under adrenotools;
     * must match the unzip target used by [TurnipDriverProvisioner].
     */
    const val TURNIP_BALANCED = "WN-Turnip-1.06-b"

    /**
     * Key into `content_manifest.runtimeAssets[]`. The URL, SHA-256 and size of
     * the zip live there and nowhere else, so bumping the pin upstream does not
     * need an APK rebuild.
     */
    const val TURNIP_ZIP_RELATIVE = "adrenotools/WN-Turnip-1.06-b_Axxx.zip"

    /** Companion of [LEEGAO] in `content_manifest.runtimeAssets[]`. */
    const val LEEGAO_WRAPPER_RELATIVE = "graphics_driver/wrapper-leegao.tzst"

    fun isKnown(id: String): Boolean = id == WRAPPER || id == SYSTEM || id == TURNIP_BALANCED || id == LEEGAO

    fun normalize(id: String?): String = when {
        id.isNullOrBlank() -> WRAPPER
        id.equals(SYSTEM, ignoreCase = true) -> SYSTEM
        id == TURNIP_BALANCED -> TURNIP_BALANCED
        id == LEEGAO || id == LEGACY_LEEGAO -> LEEGAO
        else -> WRAPPER
    }

    /**
     * The driver ids worth offering on this device.
     *
     * [SYSTEM] is a fallback for hardware we have no better answer for — emulators,
     * software renderers, anything with no vendor HAL — rather than a general
     * escape hatch, so it stays out of the list whenever a real driver applies.
     */
    fun availableDrivers(isAdreno: Boolean, hasVendorHal: Boolean): List<String> = when {
        isAdreno -> listOf(WRAPPER, TURNIP_BALANCED)
        hasVendorHal -> listOf(LEEGAO)
        else -> listOf(SYSTEM)
    }

    /**
     * Resolves the one device-compatible driver selection shared by host and guest.
     *
     * The wrapper ICD and downloaded Turnip packages both depend on the Adreno
     * stack, and Leegao is the inverse: it exists to drive a non-Adreno vendor HAL.
     * Neither side may be resolved on its own — letting only the host fall back
     * leaves the guest pointing at an ICD it cannot load.
     */
    fun resolveEffectiveDriver(id: String?, isAdreno: Boolean, hasVendorHal: Boolean): String {
        val normalized = normalize(id)
        if (isAdreno) return if (normalized == LEEGAO) WRAPPER else normalized
        return when {
            // An explicit System pick is a deliberate bypass of the vendor path,
            // usually to check whether Leegao is what broke something.
            normalized == SYSTEM -> SYSTEM
            hasVendorHal -> LEEGAO
            else -> SYSTEM
        }
    }

    /**
     * Resolves the Vulkan backend used by the Android host compositor.
     *
     * The bundled wrapper and Leegao are both loader-facing ICDs
     * (`vk_icdGetInstanceProcAddr`), not Android HALs (`HMI`), and they wrap a
     * driver the host already reaches on its own — so the host opens the platform
     * loader. Downloaded Turnip packages are HALs and can go through adrenotools,
     * but only on Adreno devices.
     */
    fun resolveHostDriver(id: String?, isAdreno: Boolean, hasVendorHal: Boolean): String =
        when (resolveEffectiveDriver(id, isAdreno, hasVendorHal)) {
            TURNIP_BALANCED -> if (isAdreno) TURNIP_BALANCED else SYSTEM
            else -> SYSTEM
        }
}
