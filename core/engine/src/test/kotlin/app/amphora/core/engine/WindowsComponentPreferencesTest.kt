package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowsComponentPreferencesTest {
    @Test
    fun normalizesWinNativeComponentFormatAndOrder() {
        assertEquals(
            "direct3d=0,directsound=0,directmusic=0,directshow=0," +
                "directplay=0,xaudio=0,dinput8=1,vcrun2010=1",
            WindowsComponentPreferences.normalize("xaudio=0,direct3d=0"),
        )
    }

    @Test
    fun recommendedDefaultsUseOnlyCoreNativeComponents() {
        val native =
            WindowsComponentPreferences.componentIds.filter(
                WindowsComponentPreferences::defaultUsesNative,
            )
        assertEquals(listOf("direct3d", "dinput8", "vcrun2010"), native)
    }

    @Test
    fun rejectsUnknownAndInvalidValues() {
        assertEquals(
            WindowsComponentPreferences.DEFAULT_SELECTION,
            WindowsComponentPreferences.normalize("unknown=0,direct3d=yes"),
        )
    }
}
