package app.amphora.ui

/**
 * Dev switch for the wineandroid host path.
 *
 * Keep [FORCE_WINEANDROID_HOST] false in committed trees so the default UI still
 * opens X11 [app.amphora.gamesession.SessionActivity]. Flip locally, or pass
 * MainActivity extra `app.amphora.debug.WINEANDROID=true` (debuggable builds).
 *
 * See docs/12-WINEANDROID-MIGRATION.md §P1.
 */
object WineAndroidLaunchGate {
    const val FORCE_WINEANDROID_HOST = false
}
