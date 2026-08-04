package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowsComponentPreferencesTest {
    @Test
    fun normalizesWinNativeComponentFormatAndOrder() {
        assertEquals(
            "direct3d=0,directsound=1,directmusic=1,directshow=1," +
                "directplay=1,xaudio=0,dinput8=1,vcrun2010=1",
            WindowsComponentPreferences.normalize("xaudio=0,direct3d=0"),
        )
    }

    @Test
    fun rejectsUnknownAndInvalidValues() {
        assertEquals(
            WindowsComponentPreferences.DEFAULT_SELECTION,
            WindowsComponentPreferences.normalize("unknown=0,direct3d=yes"),
        )
    }
}
