package app.amphora.gamesession.wineandroid

import app.amphora.gamesession.input.ImeUiState

/**
 * Host-local IME UI helpers for wineandroid.
 *
 * Composition stays on the Android host ([ImeUiState.composingText]); commit still
 * goes through [WineAndroidImeCommit] / [WineAndroidNative.nativeSendUnicodeChar].
 * No IMM32/TSF into guest Wine.
 */
object WineAndroidImeUi {
    /** Pure reducer: apply composing / keyboard-visible patches onto prior state. */
    fun update(
        prior: ImeUiState,
        composingText: String = prior.composingText,
        keyboardVisible: Boolean = prior.keyboardVisible,
    ): ImeUiState {
        val next =
            ImeUiState(
                composingText = composingText,
                keyboardVisible = keyboardVisible,
            )
        return if (next == prior) prior else next
    }

    /** Whether the host composing chip should be shown. */
    fun shouldShowComposingOverlay(state: ImeUiState): Boolean =
        state.composingText.isNotEmpty()

    /** Text for the host composing chip (empty when hidden). */
    fun composingOverlayText(state: ImeUiState): String =
        if (shouldShowComposingOverlay(state)) state.composingText else ""

    /**
     * Soft-IME auto-show policy for pointer DOWN on a GDI [WindowGroup].
     *
     * Always false: tapping the desktop/chrome must not pop the soft keyboard
     * (HA262 immersive smoke: IME covering half the screen). Touch still sets
     * key-target / focus for hardware keys. There is no reliable guest
     * "text field focused" signal yet, so prefer no auto-show.
     *
     * Callers that need IME use [WineAndroidDesktop.showSoftKeyboard] explicitly
     * (GameSession drawer "Show keyboard", future overlay / long-press).
     * [WineAndroidDesktop.onCheckIsTextEditor] stays true so the system/user
     * can still request IME when appropriate.
     */
    fun shouldAutoShowSoftKeyboardOnTouch(): Boolean = false
}
