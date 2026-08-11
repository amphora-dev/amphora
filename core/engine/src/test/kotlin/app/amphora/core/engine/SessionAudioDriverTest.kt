package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionAudioDriverTest {
    @Test
    fun pulseRequiresBothPlatformAndWineDriverSupport() {
        assertEquals(
            AdvancedRuntimePreferences.AUDIO_DRIVER_PULSEAUDIO,
            resolveSessionAudioDriver(
                AdvancedRuntimePreferences.AUDIO_DRIVER_PULSEAUDIO,
                pulsePlatformSupported = true,
                pulseWineDriverAvailable = true,
            ),
        )
        assertEquals(
            AdvancedRuntimePreferences.AUDIO_DRIVER_ALSA,
            resolveSessionAudioDriver(
                AdvancedRuntimePreferences.AUDIO_DRIVER_PULSEAUDIO,
                pulsePlatformSupported = false,
                pulseWineDriverAvailable = true,
            ),
        )
        assertEquals(
            AdvancedRuntimePreferences.AUDIO_DRIVER_ALSA,
            resolveSessionAudioDriver(
                AdvancedRuntimePreferences.AUDIO_DRIVER_PULSEAUDIO,
                pulsePlatformSupported = true,
                pulseWineDriverAvailable = false,
            ),
        )
    }

    @Test
    fun alsaRemainsTheCompatibleFallback() {
        assertEquals(
            AdvancedRuntimePreferences.AUDIO_DRIVER_ALSA,
            resolveSessionAudioDriver(
                AdvancedRuntimePreferences.AUDIO_DRIVER_ALSA,
                pulsePlatformSupported = true,
                pulseWineDriverAvailable = true,
            ),
        )
        assertEquals(
            AdvancedRuntimePreferences.AUDIO_DRIVER_ALSA,
            resolveSessionAudioDriver(
                "unknown",
                pulsePlatformSupported = true,
                pulseWineDriverAvailable = true,
            ),
        )
    }
}
