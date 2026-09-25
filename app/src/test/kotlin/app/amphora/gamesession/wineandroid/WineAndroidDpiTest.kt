package app.amphora.gamesession.wineandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** docs/04: Wine DPI stays classic 96; never invent Android densityDpi. */
class WineAndroidDpiTest {
    @Test
    fun virtualDesktopUsesClassic96() {
        assertEquals(96, WineAndroidDpi.CLASSIC_WINE_DPI)
        assertEquals(96, WineAndroidDpi.forVirtualDesktopWithHostScale())
    }

    @Test
    fun classicIsNotWinlatorScreenInfoOrPhoneDensity() {
        assertNotEquals(254, WineAndroidDpi.forVirtualDesktopWithHostScale())
        assertNotEquals(440, WineAndroidDpi.forVirtualDesktopWithHostScale())
    }
}
