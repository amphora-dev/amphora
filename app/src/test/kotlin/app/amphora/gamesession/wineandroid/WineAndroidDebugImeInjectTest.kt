package app.amphora.gamesession.wineandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WineAndroidDebugImeInjectTest {
    @Test
    fun returnsNullWhenNotDebuggable() {
        assertNull(WineAndroidDebugImeInject.textIfDebuggable("中文A", debuggable = false))
    }

    @Test
    fun returnsNullWhenMissingOrEmpty() {
        assertNull(WineAndroidDebugImeInject.textIfDebuggable(null, debuggable = true))
        assertNull(WineAndroidDebugImeInject.textIfDebuggable("", debuggable = true))
    }

    @Test
    fun returnsCjkAndAsciiWhenDebuggable() {
        assertEquals(
            "中文A",
            WineAndroidDebugImeInject.textIfDebuggable("中文A", debuggable = true),
        )
    }

    @Test
    fun extraConstantMatchesSmokeContract() {
        assertEquals(
            "app.amphora.debug.IME_UNICODE_TEXT",
            WineAndroidDebugImeInject.EXTRA_IME_UNICODE_TEXT,
        )
        assertEquals(
            "app.amphora.debug.IME_COMPOSING_TEXT",
            WineAndroidDebugImeInject.EXTRA_IME_COMPOSING_TEXT,
        )
    }

    @Test
    fun composingNullWhenNotDebuggableEvenIfPresent() {
        assertNull(
            WineAndroidDebugImeInject.composingIfDebuggable(
                raw = "nihao",
                present = true,
                debuggable = false,
            ),
        )
    }

    @Test
    fun composingNullWhenAbsent() {
        assertNull(
            WineAndroidDebugImeInject.composingIfDebuggable(
                raw = null,
                present = false,
                debuggable = true,
            ),
        )
    }

    @Test
    fun composingEmptyClearsWhenPresentAndDebuggable() {
        assertEquals(
            "",
            WineAndroidDebugImeInject.composingIfDebuggable(
                raw = "",
                present = true,
                debuggable = true,
            ),
        )
        assertEquals(
            "",
            WineAndroidDebugImeInject.composingIfDebuggable(
                raw = null,
                present = true,
                debuggable = true,
            ),
        )
    }

    @Test
    fun composingTextWhenPresentAndDebuggable() {
        assertEquals(
            "nihao",
            WineAndroidDebugImeInject.composingIfDebuggable(
                raw = "nihao",
                present = true,
                debuggable = true,
            ),
        )
    }
}
