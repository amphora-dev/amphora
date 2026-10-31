package app.amphora.core.engine

import android.content.Context
import android.util.Log
import app.amphora.core.content.RuntimeAssetProvisioner
import com.winlator.cmod.shared.io.FileUtils
import java.io.File
import java.nio.file.Files

/**
 * Mesa WGL as every prefix's `opengl32.dll`.
 *
 * The runtime asset [ASSET_PATH] (imagefs `l1/mesa-wgl.bst`) holds Mesa's
 * Windows `opengl32.dll` + `libgallium_wgl.dll` for system32 and syswow64, with
 * the zink and softpipe gallium drivers. Wine's builtin opengl32 has no working
 * backend under wineandroid (its EGL driver gets zero configs from the native
 * Mesa), so the PE ICD is linked unconditionally, independent of the dxwrapper
 * selection: wined3d (DirectDraw, 64-bit dx7) needs GL too. Wine loads it with
 * [WINE_DLL_OVERRIDE].
 *
 * zink renders on the guest Vulkan driver and presents through winevulkan →
 * `VK_KHR_android_surface`. `GALLIUM_DRIVER` is deliberately left unset: Mesa's
 * WGL screen then tries zink and falls back to softpipe when zink cannot create
 * a screen on this driver, whereas setting it pins one driver with no fallback.
 *
 * When [ZinkRequirements] reports blockers for the launch driver, Mesa ends up
 * on softpipe, and wined3d is moved off GL ([wined3dConfig]): on the OnePlus 6T
 * (Adreno 630, Qualcomm Vulkan 1.1) 64-bit DirectDraw 2D fell from 60 fps on
 * wined3d's GDI path to 5.6 fps on softpipe. OpenGL programs still get softpipe;
 * 64-bit Direct3D 7 has no renderer there, as before Mesa WGL.
 */
internal object MesaWglRuntime {
    private const val TAG = "MesaWglRuntime"

    const val ASSET_PATH = "graphics_driver/mesa-wgl.tzst"
    const val WINE_DLL_OVERRIDE = "opengl32=n,b"

    /** wined3d renderer used when zink cannot run: Wine's no-3D (GDI) path. */
    const val WINED3D_RENDERER_WITHOUT_ZINK = "gdi"
    private const val TYPE_DIR = "MESAWGL"
    private const val CACHE_ID = "mesa-wgl"

    /** Prefix paths under `drive_c/windows` that must be links into the cache. */
    val PREFIX_DLLS: List<String> =
        listOf(
            "system32/opengl32.dll",
            "system32/libgallium_wgl.dll",
            "syswow64/opengl32.dll",
            "syswow64/libgallium_wgl.dll",
        )

    /** Links the pinned Mesa WGL DLLs into [windowsDir] and deletes caches of older pins. */
    fun link(context: Context, windowsDir: File): File {
        val cacheDir =
            RuntimeAssetDllCache.link(
                context,
                File(RuntimeAssetProvisioner.runtimeAssetsDir(context), ASSET_PATH),
                TYPE_DIR,
                CACHE_ID,
                windowsDir,
                requiredFiles = PREFIX_DLLS,
            )
        check(!linksNeedRefresh(windowsDir)) { "Mesa WGL links are incomplete in $windowsDir" }
        pruneSiblings(cacheDir).forEach { Log.i(TAG, "Removed unpinned Mesa WGL cache $it") }
        return cacheDir
    }

    /** True unless every [PREFIX_DLLS] entry is a symlink that resolves to a file. */
    internal fun linksNeedRefresh(windowsDir: File): Boolean = PREFIX_DLLS.any { relative ->
        val target = File(windowsDir, relative)
        !(Files.isSymbolicLink(target.toPath()) && target.isFile)
    }

    /**
     * `WINE_D3D_CONFIG` for this launch: [base] unchanged when zink can run,
     * else with `renderer=` replaced by [WINED3D_RENDERER_WITHOUT_ZINK] so
     * wined3d does not render on softpipe.
     */
    internal fun wined3dConfig(base: String, zinkBlockers: List<String>): String {
        if (zinkBlockers.isEmpty()) return base
        val fields = base.split(',').filter { it.isNotEmpty() && !it.startsWith("renderer=") }
        return (fields + "renderer=$WINED3D_RENDERER_WITHOUT_ZINK").joinToString(",")
    }

    /** Deletes `mesa-wgl-*` caches next to [cacheDir] and returns what it removed. */
    internal fun pruneSiblings(cacheDir: File): List<File> {
        val stale =
            cacheDir.parentFile
                ?.listFiles()
                .orEmpty()
                .filter { it.name.startsWith("$CACHE_ID-") && it.name != cacheDir.name }
                .sortedBy { it.name }
        stale.forEach { FileUtils.delete(it) }
        return stale
    }
}
