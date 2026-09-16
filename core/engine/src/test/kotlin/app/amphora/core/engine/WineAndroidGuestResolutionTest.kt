package app.amphora.core.engine

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
        assertEquals(
            WineAndroidGuestResolution.HD_1280x720,
            WineAndroidGuestResolution.suggestForHostShortSide(1904),
        )
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

    @Test
    fun preferenceNameRoundTripForAllPresets() {
        for (p in WineAndroidGuestResolution.ALL) {
            val stored = WineAndroidGuestResolution.preferenceName(p)
            assertEquals(p, WineAndroidGuestResolution.fromPreference(stored))
            assertEquals(p, WineAndroidGuestResolution.fromPreference(p.id))
        }
    }

    @Test
    fun fromPreferenceFallsBackForLegacyAndUnknown() {
        assertEquals(
            WineAndroidGuestResolution.DEFAULT,
            WineAndroidGuestResolution.fromPreference(null),
        )
        assertEquals(
            WineAndroidGuestResolution.DEFAULT,
            WineAndroidGuestResolution.fromPreference(""),
        )
        assertEquals(
            WineAndroidGuestResolution.DEFAULT,
            WineAndroidGuestResolution.fromPreference("R800x600"),
        )
        assertEquals(
            WineAndroidGuestResolution.DEFAULT,
            WineAndroidGuestResolution.fromPreference("R1920x1200"),
        )
        assertEquals(
            WineAndroidGuestResolution.DEFAULT,
            WineAndroidGuestResolution.fromPreference("not-a-resolution"),
        )
    }

    @Test
    fun debugDimensionsUsePreferenceUnlessExplicitlyOverridden() {
        assertEquals(
            1024 to 768,
            WineAndroidGuestResolution.resolveDebugDimensions("R1024x768", null, null),
        )
        assertEquals(
            1280 to 720,
            WineAndroidGuestResolution.resolveDebugDimensions("R1024x768", 1280, 720),
        )
    }

    @Test
    fun debugDimensionSourceLabelsPreferExtrasThenPrefThenDefault() {
        assertEquals(
            "extras",
            WineAndroidGuestResolution.describeDebugDimensionSource("R1024x768", 1280, 720),
        )
        assertEquals(
            "mixed",
            WineAndroidGuestResolution.describeDebugDimensionSource("R1024x768", 1280, null),
        )
        assertEquals(
            "pref",
            WineAndroidGuestResolution.describeDebugDimensionSource("R1024x768", null, null),
        )
        assertEquals(
            "default",
            WineAndroidGuestResolution.describeDebugDimensionSource(null, null, null),
        )
        assertEquals(
            "default",
            WineAndroidGuestResolution.describeDebugDimensionSource("", null, null),
        )
    }

    @Test
    fun preferenceNamesMatchHouseEnumStyle() {
        assertEquals("R1280x720", WineAndroidGuestResolution.preferenceName(WineAndroidGuestResolution.HD_1280x720))
        assertEquals("R1024x768", WineAndroidGuestResolution.preferenceName(WineAndroidGuestResolution.XGA_1024x768))
        assertEquals("R1600x900", WineAndroidGuestResolution.preferenceName(WineAndroidGuestResolution.HD_PLUS_1600x900))
        assertEquals("R1920x1080", WineAndroidGuestResolution.preferenceName(WineAndroidGuestResolution.FHD_1920x1080))
    }
}
