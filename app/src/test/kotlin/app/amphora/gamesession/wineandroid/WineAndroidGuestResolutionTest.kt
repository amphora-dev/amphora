package app.amphora.gamesession.wineandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WineAndroidGuestResolutionTest {
    @Test
    fun defaultIs720p() {
        assertEquals(1280, WineAndroidGuestResolution.DEFAULT.width)
        assertEquals(720, WineAndroidGuestResolution.DEFAULT.height)
    }

    @Test
    fun catalogIdsUnique() {
        val ids = WineAndroidGuestResolution.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun ha262ShortSideStays720p() {
        // Landscape short side 1904 → still HD_1280x720 (scale ~2.375 comfortable)
        assertEquals(
            WineAndroidGuestResolution.HD_1280x720,
            WineAndroidGuestResolution.suggestForHostShortSide(1904),
        )
        // Portrait short side 1904 same
        assertEquals(
            WineAndroidGuestResolution.HD_1280x720,
            WineAndroidGuestResolution.suggestForHostShortSide(minOf(1904, 3040)),
        )
    }

    @Test
    fun veryLargeShortSideSuggestsFhd() {
        assertEquals(
            WineAndroidGuestResolution.FHD_1920x1080,
            WineAndroidGuestResolution.suggestForHostShortSide(2560),
        )
    }

    @Test
    fun byIdRoundTrip() {
        for (p in WineAndroidGuestResolution.ALL) {
            assertEquals(p, WineAndroidGuestResolution.byId(p.id))
        }
        assertTrue(WineAndroidGuestResolution.byId("nope") == null)
    }
}
