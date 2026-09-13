package app.amphora.ui

/**
 * Product default is wineandroid ([FORCE_WINEANDROID_HOST] = true).
 *
 * Fall back to the legacy Java X11 host with an explicit
 * `displayBackend = DisplayBackend.X11` (e.g. [SessionLaunch.program]) or the
 * debuggable MainActivity extra `app.amphora.debug.X11=true`.
 *
 * See docs/12-WINEANDROID-MIGRATION.md.
 */
object WineAndroidLaunchGate {
    const val FORCE_WINEANDROID_HOST = true
}
