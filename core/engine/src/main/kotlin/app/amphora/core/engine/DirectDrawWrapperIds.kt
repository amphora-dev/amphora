package app.amphora.core.engine

/**
 * DirectDraw compatibility layers offered by Amphora.
 *
 * Each choice replaces Wine's 32-bit builtin ddraw/WineD3D path. They are
 * mutually exclusive because all of them install `syswow64/ddraw.dll`. None of
 * the projects ships x86_64 DLLs, so 64-bit DirectDraw processes retain
 * Proton's builtin ddraw → WineD3D/Zink path.
 */
object DirectDrawWrapperIds {
    const val PREFS_NAME = "amphora_graphics"
    const val PREFS_KEY_WRAPPER_ID = "directdraw_wrapper_id"

    /** elishacloud/dxwrapper with Dd7to9 enabled (default, broad DX1-7 coverage). */
    const val DXWRAPPER_DD7TO9 = "dd7to9"

    /** FunkyFr3sh/cnc-ddraw, forced to its D3D9 renderer for 2D games. */
    const val CNC_DDRAW = "cnc-ddraw"

    /** WinterSnowfall/d7vk, translating Direct3D 3–7 directly to Vulkan. */
    const val D7VK = "d7vk"

    fun normalize(id: String?): String = when (id) {
        CNC_DDRAW -> CNC_DDRAW
        D7VK -> D7VK
        DXWRAPPER_DD7TO9 -> DXWRAPPER_DD7TO9
        else -> DXWRAPPER_DD7TO9
    }
}
