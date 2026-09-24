package app.amphora.gamesession.wineandroid

import android.content.Intent

/**
 * Debug-only IME inject extras for HA262 smoke (no soft Gboard).
 *
 * Honored only when the process is FLAG_DEBUGGABLE.
 * [WineAndroidSessionActivity] applies after layout (and for unicode, after desktop
 * hwnd is ready), or via onNewIntent once the session is already up.
 *
 * Mid-session adb must target [WineAndroidDebugImeRelayActivity] (debug sourceSet),
 * not MainActivity (buried under singleTask Session) and not Session directly
 * (exported=false → SecurityException).
 *
 * - [EXTRA_IME_UNICODE_TEXT]: commit path (KeyCharacterMap + unicode); empty/absent → no-op
 * - [EXTRA_IME_COMPOSING_TEXT]: host-only composing chip; absent → no-op; empty/null extra → clear
 *   chip. Use `am … --esn KEY` for clear — Android shell rejects `--es KEY ''`.
 * - [EXTRA_IME_SHOW]: explicit soft IME show/hide (`--ez … true|false`); absent → no-op.
 *   Does not change tap auto-show policy.
 */
object WineAndroidDebugImeInject {
    const val EXTRA_IME_UNICODE_TEXT = "app.amphora.debug.IME_UNICODE_TEXT"
    const val EXTRA_IME_COMPOSING_TEXT = "app.amphora.debug.IME_COMPOSING_TEXT"
    const val EXTRA_IME_SHOW = "app.amphora.debug.IME_SHOW"

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
     * Intent wrapper: requires [Intent.hasExtra] so `--esn …IME_COMPOSING_TEXT`
     * (null String extra) still clears the chip. Do not use `--es … ''` — am rejects it.
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

    /**
     * Explicit soft-IME show/hide: null = absent / not debuggable (no-op);
     * true = [WineAndroidDesktop.showSoftKeyboard]; false = hide.
     */
    fun showSoftKeyboardIfDebuggable(present: Boolean, value: Boolean, debuggable: Boolean): Boolean? {
        if (!debuggable || !present) return null
        return value
    }

    fun showSoftKeyboardFromIntent(intent: Intent?, debuggable: Boolean): Boolean? {
        if (intent == null || !intent.hasExtra(EXTRA_IME_SHOW)) {
            return showSoftKeyboardIfDebuggable(
                present = false,
                value = false,
                debuggable = debuggable,
            )
        }
        return showSoftKeyboardIfDebuggable(
            present = true,
            value = intent.getBooleanExtra(EXTRA_IME_SHOW, false),
            debuggable = debuggable,
        )
    }

    /**
     * Map raw adb extras onto [WineAndroidSessionActivity.intent] args.
     *
     * @return [unicodeText] null = omit; non-null non-empty = commit inject.
     *   [composingText] null = omit; non-null (incl. empty) = put composing extra.
     *   [showSoftKeyboard] null = omit; true/false = put IME_SHOW extra.
     */
    fun relayForward(
        unicodeRaw: String?,
        composingPresent: Boolean,
        composingRaw: String?,
        imeShowPresent: Boolean = false,
        imeShowValue: Boolean = false,
    ): RelayExtras {
        val unicode = unicodeRaw?.takeIf { it.isNotEmpty() }
        val composing =
            if (composingPresent) {
                composingRaw ?: ""
            } else {
                null
            }
        val show =
            if (imeShowPresent) {
                imeShowValue
            } else {
                null
            }
        return RelayExtras(
            unicodeText = unicode,
            composingText = composing,
            showSoftKeyboard = show,
        )
    }

    data class RelayExtras(val unicodeText: String?, val composingText: String?, val showSoftKeyboard: Boolean? = null)
}
