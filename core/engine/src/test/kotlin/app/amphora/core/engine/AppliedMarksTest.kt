package app.amphora.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppliedMarksTest {
    @Test
    fun prefixOwnedMarksDoNotIncludeBox64() {
        val keys = AppliedMarks.prefixOwnedKeys
        assertTrue(keys.contains("appliedDxwrapper"))
        assertTrue(keys.contains("appliedWincomponents"))
        assertTrue(keys.contains("appliedServices"))
        assertTrue(keys.contains("dxwrapper")) // 旧键也要清
        assertFalse(keys.contains("appliedBox64"))
        assertFalse(keys.contains("box64Version"))
        assertFalse(keys.contains("audioDriver"))
    }
}
