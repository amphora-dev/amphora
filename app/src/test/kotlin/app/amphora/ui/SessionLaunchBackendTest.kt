package app.amphora.ui

import app.amphora.core.engine.model.DisplayBackend
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionLaunchBackendTest {
    @Test
    fun resolveDisplayBackendMatchesForceWineAndroidHostGate() {
        assertEquals(true, WineAndroidLaunchGate.FORCE_WINEANDROID_HOST)
        assertEquals(DisplayBackend.WINEANDROID, SessionLaunch.resolveDisplayBackend())
    }
}
