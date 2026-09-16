package app.amphora.gamesession.wineandroid

import app.amphora.gamesession.input.ImeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WineAndroidImeUiTest {
    @Test
    fun updateComposingTextChangesState() {
        val prior = ImeUiState()
        val next = WineAndroidImeUi.update(prior, composingText = "ni")
        assertEquals("ni", next.composingText)
        assertFalse(next.keyboardVisible)
    }

    @Test
    fun identicalUpdateReturnsSameInstance() {
        val prior = ImeUiState(composingText = "hao", keyboardVisible = true)
        val next =
            WineAndroidImeUi.update(
                prior,
                composingText = "hao",
                keyboardVisible = true,
            )
        assertSame(prior, next)
    }

    @Test
    fun clearComposingHidesOverlay() {
        val composing = ImeUiState(composingText = "中")
        assertTrue(WineAndroidImeUi.shouldShowComposingOverlay(composing))
        assertEquals("中", WineAndroidImeUi.composingOverlayText(composing))

        val cleared = WineAndroidImeUi.update(composing, composingText = "")
        assertFalse(WineAndroidImeUi.shouldShowComposingOverlay(cleared))
        assertEquals("", WineAndroidImeUi.composingOverlayText(cleared))
    }

    @Test
    fun keyboardVisibleAloneDoesNotShowComposingChip() {
        val state = ImeUiState(keyboardVisible = true)
        assertFalse(WineAndroidImeUi.shouldShowComposingOverlay(state))
        assertEquals("", WineAndroidImeUi.composingOverlayText(state))
    }

    @Test
    fun touchDoesNotAutoShowSoftKeyboard() {
        // Default policy: no IME on every desktop/chrome tap (HA262 immersive).
        assertFalse(WineAndroidImeUi.shouldAutoShowSoftKeyboardOnTouch())
    }

    @Test
    fun textEditorOnlyWhenImeWanted() {
        // Focus/tap must not report as editor; explicit showSoftKeyboard sets imeWanted.
        assertFalse(WineAndroidImeUi.shouldReportAsTextEditor(imeWanted = false))
        assertTrue(WineAndroidImeUi.shouldReportAsTextEditor(imeWanted = true))
    }

    @Test
    fun toggleImeWantedFlips() {
        assertTrue(WineAndroidImeUi.toggleImeWanted(false))
        assertFalse(WineAndroidImeUi.toggleImeWanted(true))
    }

    @Test
    fun keyboardChipShowsWhenNotWanted() {
        val ui = WineAndroidImeUi.softKeyboardControl(imeWanted = false)
        assertEquals(WineAndroidImeUi.KEYBOARD_CONTROL_LABEL_SHOW, ui.label)
        assertEquals(WineAndroidImeUi.KEYBOARD_CONTROL_CONTENT_DESCRIPTION, ui.contentDescription)
        assertFalse(ui.shown)
    }

    @Test
    fun keyboardChipHidesWhenWanted() {
        val ui = WineAndroidImeUi.softKeyboardControl(imeWanted = true)
        assertEquals(WineAndroidImeUi.KEYBOARD_CONTROL_LABEL_HIDE, ui.label)
        assertEquals(WineAndroidImeUi.KEYBOARD_CONTROL_CONTENT_DESCRIPTION, ui.contentDescription)
        assertTrue(ui.shown)
    }
}
