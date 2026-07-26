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

    /**
     * Optional open-source Turnip (Balanced). Folder name under adrenotools;
     * must match the unzip target used by [TurnipDriverProvisioner].
     */
    const val TURNIP_BALANCED = "WN-Turnip-1.06-b"

    const val TURNIP_ZIP_RELATIVE = "adrenotools/WN-Turnip-1.06-b_Axxx.zip"
    const val TURNIP_ZIP_URL =
        "https://github.com/WinNative-Emu/Drivers/releases/download/v1.06/WN-Turnip-1.06-b_Axxx.zip"
    const val TURNIP_ZIP_SHA256 =
        "c88c6ee2983f8d0814479f895e815e37fa4caa1e61020f4f1ac736026183f785"
    const val TURNIP_ZIP_SIZE = 2_694_674L

    fun isKnown(id: String): Boolean = id == WRAPPER || id == TURNIP_BALANCED

    fun normalize(id: String?): String =
        when {
            id.isNullOrBlank() -> WRAPPER
            id == TURNIP_BALANCED -> TURNIP_BALANCED
            else -> WRAPPER
        }
}
