package app.amphora.gamesession.wineandroid

/**
 * Android keycodes that wineandroid intentionally leaves unmapped so the host
 * Activity / system can handle them.
 *
 * Native `keycode_to_vkey` returns 0 for these → [WineAndroidNative.nativeSendKeyboardEvent]
 * returns false → [WineAndroidSessionActivity.dispatchKeyEvent] falls through to
 * `super` (Android BACK finish / volume / power). Do **not** invent guest vkeys
 * for them; shell chrome and system keys stay host-owned.
 *
 * Numeric values match `android.view.KeyEvent` (kept as ints so JVM unit tests
 * need no Robolectric).
 */
object WineAndroidKeyPassThrough {
    const val KEYCODE_HOME = 3
    const val KEYCODE_BACK = 4
    const val KEYCODE_VOLUME_UP = 24
    const val KEYCODE_VOLUME_DOWN = 25
    const val KEYCODE_POWER = 26
    const val KEYCODE_VOLUME_MUTE = 164

    /** True when host must own the key (guest pipe must not swallow it). */
    fun isIntentionalHostPassThrough(keyCode: Int): Boolean =
        when (keyCode) {
            KEYCODE_HOME,
            KEYCODE_BACK,
            KEYCODE_VOLUME_UP,
            KEYCODE_VOLUME_DOWN,
            KEYCODE_POWER,
            KEYCODE_VOLUME_MUTE,
            -> true
            else -> false
        }

    /**
     * Classify a failed native send for logging.
     * @return `intentional-host`, `unmapped`, or null when [ok] is true
     */
    fun passThroughLabel(keyCode: Int, ok: Boolean): String? {
        if (ok) return null
        return if (isIntentionalHostPassThrough(keyCode)) "intentional-host" else "unmapped"
    }
}
