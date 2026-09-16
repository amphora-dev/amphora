package app.amphora.gamesession.wineandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WineAndroidKeyPassThroughTest {
    @Test
    fun backIsIntentionalHostPassThrough() {
        assertTrue(WineAndroidKeyPassThrough.isIntentionalHostPassThrough(WineAndroidKeyPassThrough.KEYCODE_BACK))
        assertEquals(4, WineAndroidKeyPassThrough.KEYCODE_BACK)
    }

    @Test
    fun volumeAndSystemKeysPassThrough() {
        assertTrue(WineAndroidKeyPassThrough.isIntentionalHostPassThrough(WineAndroidKeyPassThrough.KEYCODE_VOLUME_UP))
        assertTrue(WineAndroidKeyPassThrough.isIntentionalHostPassThrough(WineAndroidKeyPassThrough.KEYCODE_VOLUME_DOWN))
        assertTrue(WineAndroidKeyPassThrough.isIntentionalHostPassThrough(WineAndroidKeyPassThrough.KEYCODE_VOLUME_MUTE))
        assertTrue(WineAndroidKeyPassThrough.isIntentionalHostPassThrough(WineAndroidKeyPassThrough.KEYCODE_HOME))
        assertTrue(WineAndroidKeyPassThrough.isIntentionalHostPassThrough(WineAndroidKeyPassThrough.KEYCODE_POWER))
    }

    @Test
    fun letterKeysAreGuestMappedNotPassThrough() {
        // AKEYCODE_A = 29 — mapped to vkey 'A' in wineandroid_host_ipc.c
        assertFalse(WineAndroidKeyPassThrough.isIntentionalHostPassThrough(29))
        // AKEYCODE_ENTER = 66
        assertFalse(WineAndroidKeyPassThrough.isIntentionalHostPassThrough(66))
        // AKEYCODE_DEL = 67 (maps to VK_BACK / backspace — guest owns it)
        assertFalse(WineAndroidKeyPassThrough.isIntentionalHostPassThrough(67))
    }

    @Test
    fun passThroughLabelOnlyWhenNativeFails() {
        assertNull(WineAndroidKeyPassThrough.passThroughLabel(WineAndroidKeyPassThrough.KEYCODE_BACK, ok = true))
        assertEquals(
            "intentional-host",
            WineAndroidKeyPassThrough.passThroughLabel(WineAndroidKeyPassThrough.KEYCODE_BACK, ok = false),
        )
        assertEquals("unmapped", WineAndroidKeyPassThrough.passThroughLabel(9999, ok = false))
    }
}
