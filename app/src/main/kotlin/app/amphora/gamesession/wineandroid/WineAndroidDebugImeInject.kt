package app.amphora.gamesession.wineandroid

import android.content.Intent

/**
 * Debug-only IME inject extras for HA262 smoke (no soft Gboard).
 *
 * Honored only when the process is FLAG_DEBUGGABLE.
 * [WineAndroidSessionActivity] applies after layout (and for unicode, after desktop
 * hwnd is ready), or via onNewIntent once the session is already up.
 *
 * - [EXTRA_IME_UNICODE_TEXT]: commit path (KeyCharacterMap + unicode); empty/absent → no-op
 * - [EXTRA_IME_COMPOSING_TEXT]: host-only composing chip; absent → no-op; empty → clear chip
 */
object WineAndroidDebugImeInject {
    const val EXTRA_IME_UNICODE_TEXT = "app.amphora.debug.IME_UNICODE_TEXT"
    const val EXTRA_IME_COMPOSING_TEXT = "app.amphora.debug.IME_COMPOSING_TEXT"

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

    /**
     * Host composing inject: null = absent / not debuggable (no-op);
     * empty string = clear chip; non-empty = show chip.
     * Pure helper for unit tests.
     */
    fun composingIfDebuggable(raw: String?, present: Boolean, debuggable: Boolean): String? {
        if (!debuggable || !present) return null
        return raw ?: ""
    }

    /**
     * Intent wrapper: requires [Intent.hasExtra] so empty `--es …IME_COMPOSING_TEXT ''`
     * still clears the chip.
     */
    fun composingFromIntent(intent: Intent?, debuggable: Boolean): String? {
        if (intent == null || !intent.hasExtra(EXTRA_IME_COMPOSING_TEXT)) {
            return composingIfDebuggable(raw = null, present = false, debuggable = debuggable)
        }
        return composingIfDebuggable(
            raw = intent.getStringExtra(EXTRA_IME_COMPOSING_TEXT),
            present = true,
            debuggable = debuggable,
        )
    }
}
