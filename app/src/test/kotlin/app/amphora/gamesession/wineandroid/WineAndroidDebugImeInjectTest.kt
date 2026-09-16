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
    }
}
