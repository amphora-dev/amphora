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
}
