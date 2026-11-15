package app.amphora.sdk.internal.engine

import app.amphora.sdk.engine.InstalledRuntime
import app.amphora.sdk.internal.AmphoraPaths
import java.io.File

/**
 * Variables the engine owns for every Wine process of [prefixDir], whether a
 * session or the one-off prefix initialization; settings and user lines
 * cannot override them.
 */
internal fun engineEnv(paths: AmphoraPaths, runtime: InstalledRuntime, prefixDir: File): Map<String, String> =
    linkedMapOf(
        "WINEPREFIX" to prefixDir.path,
        "LD_LIBRARY_PATH" to runtime.libraryPath.joinToString(":") { it.path },
        "HOME" to paths.home.path,
        // Proton's user name, so save paths under drive_c/users match Proton's.
        "USER" to "steamuser",
        "TMPDIR" to paths.tmp.path,
        // Prefix files are links into the runtime; the wineserver gives the prefix
        // its own copy before one is modified (12 §3.3).
        "WINE_COW_STORE" to runtime.dir.path,
    )
