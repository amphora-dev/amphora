package app.amphora.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppliedMarksTest {
    @Test
    fun prefixOwnedMarksAreOnlyAppliedKeys() {
        val keys = AppliedMarks.prefixOwnedKeys
        assertTrue(keys.contains("appliedDxwrapper"))
        assertTrue(keys.contains("appliedWincomponents"))
        assertTrue(keys.contains("appliedServices"))
        assertTrue(keys.contains("appliedAppVersion"))
        assertFalse(keys.contains("dxwrapper"))
        assertFalse(keys.contains("appliedBox64"))
        assertFalse(keys.contains("audioDriver"))
    }

    @Test
    fun obsoleteExtraKeysListOldStampNames() {
        val obsolete = AppliedMarks.obsoleteExtraKeys
        assertTrue(obsolete.contains("dxwrapper"))
        assertTrue(obsolete.contains("box64Version"))
        assertTrue(obsolete.contains("audioDriver"))
        assertTrue(obsolete.contains("startupSelection"))
        assertFalse(obsolete.contains("appliedDxwrapper"))
    }
}
