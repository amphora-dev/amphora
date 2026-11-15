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
    ).apply {
        if (runtime.manifest.fex != null) {
            // FEX's defaults (Valve's, shipped in the runtime), as Proton sets it. FEX opens
            // the file from inside Windows, where a bare unix path would resolve against the
            // current drive (D: for a game), so it gets the Z: form.
            put("FEX_APP_CONFIG_LOCATION", dosPath(File(runtime.dir, FexInfo.CONFIG_DIR)) + "\\")
        }
    }

/** [file] as Windows sees it through the Z: drive every prefix maps to `/`. */
internal fun dosPath(file: File): String = "Z:" + file.absolutePath.replace('/', '\\')
