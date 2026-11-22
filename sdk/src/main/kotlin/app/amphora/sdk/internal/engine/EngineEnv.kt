package app.amphora.sdk.internal.engine

import app.amphora.sdk.engine.FexInfo
import app.amphora.sdk.engine.InstalledRuntime
import app.amphora.sdk.internal.AmphoraPaths
import java.io.File

/**
 * Variables the engine owns for every Wine process of [prefixDir], whether a
 * session or the one-off prefix initialization; settings and user lines
 * cannot override them.
 */
internal fun engineEnv(paths: AmphoraPaths, runtime: InstalledRuntime, prefixDir: File): Map<String, String> =
    linkedMapOf<String, String>(
        "WINEPREFIX" to prefixDir.path,
        "LD_LIBRARY_PATH" to runtime.libraryPath.joinToString(":") { it.path },
        "HOME" to paths.home.path,
        // Proton's user name, so save paths under drive_c/users match Proton's.
        "USER" to "steamuser",
        "TMPDIR" to paths.tmp.path,
        // Prefix files are links into the runtime; the wineserver gives the prefix
        // its own copy before one is modified (12 §3.3).
        "WINE_COW_STORE" to runtime.dir.path,
        // Proton's fullscreen hack renders at the game's resolution and scales to the screen
        // inside the swapchain. Here the desktop has the game's size and the host scales the
        // layer (12 §3.1), so the swapchain must stay the one the driver made.
        "WINE_DISABLE_FULLSCREEN_HACK" to "1",
    ).apply {
        runtime.manifest.mesa?.let {
            // Turnip, the one GPU driver (D4): Wine's display driver opens it instead of the
            // system's Vulkan driver.
            put("WINE_ANDROID_VULKAN_HAL", File(runtime.dir, it.vulkanDriver).path)
        }
        if (runtime.manifest.fex != null) {
            // FEX's defaults (Valve's, shipped in the runtime), as Proton sets it. FEX opens
            // the file from inside Windows, where a bare unix path would resolve against the
            // current drive (D: for a game), so it gets the Z: form.
            put("FEX_APP_CONFIG_LOCATION", dosPath(File(runtime.dir, FexInfo.CONFIG_DIR)) + "\\")
        }
    }

/**
 * DLL overrides the engine sets for every session, as Proton does for the
 * Direct3D implementations it puts into a prefix: native only, so a missing
 * file is an error instead of a silent fall back to Wine's own. Settings may
 * replace them (the WineD3D choices).
 */
internal fun engineDllOverrides(runtime: InstalledRuntime): Map<String, String> =
    listOfNotNull(runtime.manifest.dxvk, runtime.manifest.vkd3d).flatMap { it.dlls }.associateWith { "n" }

/** [file] as Windows sees it through the Z: drive every prefix maps to `/`. */
internal fun dosPath(file: File): String = "Z:" + file.absolutePath.replace('/', '\\')
