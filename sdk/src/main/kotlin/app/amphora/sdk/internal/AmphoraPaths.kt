package app.amphora.sdk.internal

import android.content.Context
import java.io.File

/**
 * Where everything lives. One place, so a directory never has two names.
 *
 * ```
 * files/amphora/
 * ├── runtimes/<name>/            installed runtime (read-only once installed)
 * ├── prefixes/<id>/              Wine prefixes
 * ├── settings/global.json        user's global settings
 * ├── settings/games/<id>.json    user's per-game settings
 * ├── library.json                games the user added
 * ├── home/                       $HOME for Wine
 * ├── logs/<session>.log          one log per session
 * ├── sessions/current.json       the running session, written by the :wine process
 * └── trash/                      directories being deleted
 * cache/amphora/
 * ├── downloads/                  partial and complete runtime archives
 * └── tmp/                        $TMPDIR for Wine
 * ```
 */
internal class AmphoraPaths(
    files: File,
    cache: File,
    /** App-specific external storage: where a runtime archive can be side-loaded with adb. */
    val sideload: File?,
) {
    constructor(context: Context) : this(
        context.filesDir,
        context.cacheDir,
        context.getExternalFilesDir(null),
    )

    val root: File = File(files, "amphora")
    val runtimes: File = File(root, "runtimes")
    val prefixes: File = File(root, "prefixes")
    val settings: File = File(root, "settings")
    val globalSettings: File = File(settings, "global.json")
    val gameSettings: File = File(settings, "games")
    val library: File = File(root, "library.json")
    val home: File = File(root, "home")
    val logs: File = File(root, "logs")
    val sessions: File = File(root, "sessions")
    val currentSession: File = File(sessions, "current.json")
    val trash: File = File(root, "trash")

    val cacheRoot: File = File(cache, "amphora")
    val downloads: File = File(cacheRoot, "downloads")
    val tmp: File = File(cacheRoot, "tmp")

    fun runtime(name: String): File = File(runtimes, name)

    fun prefix(id: String): File = File(prefixes, id)

    fun gameSettings(gameId: String): File = File(gameSettings, "$gameId.json")

    fun log(sessionId: String): File = File(logs, "$sessionId.log")
}
