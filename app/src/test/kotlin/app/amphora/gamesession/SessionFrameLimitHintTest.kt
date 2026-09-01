package app.amphora.gamesession

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFrameLimitHintTest {
    @Test
    fun noLaunchCapExplainsTheCompositorOnly() {
        assertFalse(sessionFrameLimitHintIsWarning(compositorLimit = 0, launchLimit = 0))
        assertFalse(sessionFrameLimitHintIsWarning(compositorLimit = 60, launchLimit = 0))
        assertTrue(
            sessionFrameLimitHint(0, 0).contains("applies immediately to every API"),
        )
    }

    @Test
    fun turningOffWarnsThatTheSettingsCapStays() {
        val hint = sessionFrameLimitHint(compositorLimit = 0, launchLimit = 30)
        assertTrue(sessionFrameLimitHintIsWarning(0, 30))
        assertTrue(hint.contains("Off here only removes the compositor cap"))
        assertTrue(hint.contains("locked at 30 FPS"))
        assertTrue(hint.contains("Turning this Off cannot lift that launch cap"))
    }

    @Test
    fun raisingAboveTheLaunchCapIsCalledOut() {
        val hint = sessionFrameLimitHint(compositorLimit = 60, launchLimit = 30)
        assertTrue(sessionFrameLimitHintIsWarning(60, 30))
        assertTrue(hint.contains("cannot exceed 30 FPS"))
    }

    @Test
    fun matchingTheLaunchCapStillSaysOffWillNotLiftIt() {
        val hint = sessionFrameLimitHint(compositorLimit = 30, launchLimit = 30)
        assertFalse(sessionFrameLimitHintIsWarning(30, 30))
        assertTrue(hint.contains("locked at 30 FPS"))
        assertTrue(hint.contains("Turning this Off cannot lift that launch cap"))
    }
}
