package app.amphora.core.engine

/**
 * Selectable adrenotools driver ids for container `graphicsDriverConfig.version`.
 *
 * Default remains [WRAPPER] (Mesa wrapper ICD → system Adreno). [TURNIP_BALANCED]
 * is an optional WN-Turnip package from [WinNative-Emu/Drivers] installed under
 * `filesDir/contents/adrenotools/<id>/`.
 */
object GraphicsDriverIds {
    const val PREFS_NAME = "amphora_graphics"
    const val PREFS_KEY_DRIVER_ID = "adrenotools_driver_id"

    /** Bundled WinNative wrapper ICD (default). */
    const val WRAPPER = "wrapper"

    /** Android's system Vulkan loader/ICD (Mali, virtual GPUs, SwiftShader). */
    const val SYSTEM = "System"

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

    fun isKnown(id: String): Boolean = id == WRAPPER || id == SYSTEM || id == TURNIP_BALANCED

    fun normalize(id: String?): String = when {
        id.isNullOrBlank() -> WRAPPER
        id.equals(SYSTEM, ignoreCase = true) -> SYSTEM
        id == TURNIP_BALANCED -> TURNIP_BALANCED
        else -> WRAPPER
    }

    /**
     * Resolves the Vulkan backend used by the Android host compositor.
     *
     * The bundled wrapper is a loader-facing ICD (`vk_icdGetInstanceProcAddr`), not an
     * Android HAL (`HMI`). Its guest path wraps the platform Adreno driver, so the host must
     * open the platform loader directly. Downloaded Turnip packages are Android HALs and can
     * be loaded through adrenotools, but only on Adreno devices.
     */
    fun resolveHostDriver(id: String?, isAdreno: Boolean): String = when (normalize(id)) {
        TURNIP_BALANCED -> if (isAdreno) TURNIP_BALANCED else SYSTEM
        WRAPPER, SYSTEM -> SYSTEM
        else -> SYSTEM
    }
}
