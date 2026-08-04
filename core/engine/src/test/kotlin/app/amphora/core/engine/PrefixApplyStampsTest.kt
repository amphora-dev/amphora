package app.amphora.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefixApplyStampsTest {
    @Test
    fun prefixRepairClearsHeavyStampsAndObsoleteAudio() {
        val keys = PrefixApplyStamps.prefixRepairClearKeys
        assertTrue(keys.contains("dxwrapper"))
        assertTrue(keys.contains("wincomponents"))
        assertTrue(keys.contains("startupSelection"))
        assertTrue(keys.contains(PrefixApplyStamps.OBSOLETE_AUDIO_DRIVER))
        // Content pins live outside the wine prefix — must survive repair.
        assertFalse(keys.contains("box64Version"))
        assertFalse(keys.contains("fexcoreVersion"))
    }
}
