package app.amphora.core.engine

/**
 * DirectDraw compatibility layers offered by Amphora.
 *
 * Both choices replace Wine's 32-bit builtin ddraw/WineD3D path and feed D3D9
 * into DXVK. They are mutually exclusive because both install
 * `syswow64/ddraw.dll`. Neither project ships x86_64 DLLs, so 64-bit DirectDraw
 * processes retain Proton's builtin ddraw → WineD3D/Zink path.
 */
object DirectDrawWrapperIds {
    const val PREFS_NAME = "amphora_graphics"
    const val PREFS_KEY_WRAPPER_ID = "directdraw_wrapper_id"

    /** elishacloud/dxwrapper with Dd7to9 enabled (default, broad DX1-7 coverage). */
    const val DXWRAPPER_DD7TO9 = "dd7to9"

    /** FunkyFr3sh/cnc-ddraw, forced to its D3D9 renderer for 2D games. */
    const val CNC_DDRAW = "cnc-ddraw"

    fun normalize(id: String?): String = when (id) {
        CNC_DDRAW -> CNC_DDRAW
        DXWRAPPER_DD7TO9 -> DXWRAPPER_DD7TO9
        else -> DXWRAPPER_DD7TO9
    }
}
