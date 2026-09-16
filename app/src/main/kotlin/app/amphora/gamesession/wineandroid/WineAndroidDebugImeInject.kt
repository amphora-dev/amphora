package app.amphora.gamesession.wineandroid

import android.content.Intent

/**
 * Debug-only IME unicode inject extras for HA262 smoke (no soft Gboard).
 *
 * [EXTRA_IME_UNICODE_TEXT] is honored only when the process is FLAG_DEBUGGABLE.
 * [WineAndroidSessionActivity] applies it after desktop hwnd is ready, or via
 * onNewIntent once the session is already up.
 */
object WineAndroidDebugImeInject {
    const val EXTRA_IME_UNICODE_TEXT = "app.amphora.debug.IME_UNICODE_TEXT"

    /**
     * Returns non-blank [raw] when [debuggable]; otherwise null.
     * Pure helper for unit tests (no Android Intent required).
     */
    fun textIfDebuggable(raw: String?, debuggable: Boolean): String? {
        if (!debuggable) return null
        return raw?.takeIf { it.isNotEmpty() }
    }

    /** Intent wrapper around [textIfDebuggable]. */
    fun textFromIntent(intent: Intent?, debuggable: Boolean): String? =
        textIfDebuggable(intent?.getStringExtra(EXTRA_IME_UNICODE_TEXT), debuggable)
}
